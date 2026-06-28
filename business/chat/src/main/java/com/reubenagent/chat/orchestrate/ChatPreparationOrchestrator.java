package com.reubenagent.chat.orchestrate;

import com.reubenagent.chat.config.ChatProperties;
import com.reubenagent.chat.enums.ChatMode;
import com.reubenagent.chat.enums.ChatTraceStageCode;
import com.reubenagent.chat.enums.ExecutionMode;
import com.reubenagent.chat.model.memory.ChatMemoryContext;
import com.reubenagent.chat.model.orchestrate.ChatRewriteResult;
import com.reubenagent.chat.model.orchestrate.ConversationExecutionPlan;
import com.reubenagent.chat.model.orchestrate.DocumentNavigationDecision;
import com.reubenagent.chat.service.IChatMemoryService;
import com.reubenagent.chat.service.ChatDocumentOptionService;
import com.reubenagent.chat.support.ChatIntentHints;
import com.reubenagent.chat.trace.ChatTraceRecorder;
import com.reubenagent.document.model.route.DocumentRouteCandidate;
import com.reubenagent.document.model.route.KnowledgeRouteDecision;
import com.reubenagent.document.service.KnowledgeRouteService;
import com.reubenagent.document.vo.KnowledgeDocumentOptionVo;
import com.reubenagent.rag.dto.RagRetrieveRequest;
import com.reubenagent.rag.model.RetrievalResult;
import com.reubenagent.rag.service.IRagRetrievalService;
import com.reubenagent.rag.vo.RagRetrieveResponse;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
/**
 * 对话准备编排器 —— 决定"这一轮怎么回答"的大脑。
 *
 * <p>对标 super-agent {@code ChatPreparationOrchestrator}（730 行 god-class），reuben 拆到 &lt;400 行：
 * <ol>
 *   <li>{@code loadMemory} —— 加载记忆上下文（MEMORY stage）</li>
 *   <li>{@code rewriteQuery} —— 改写 + 子问题拆分（REWRITE stage，OPEN_CHAT 跳过）</li>
 *   <li>{@code modeBranch} —— 按对话模式分流执行模式（INTENT/ROUTE stage）</li>
 *   <li>{@code navigationDecision} —— DOCUMENT/AUTO 判断 GRAPH_ONLY / GRAPH_THEN_EVIDENCE / RETRIEVAL（ROUTE stage）</li>
 *   <li>{@code emitPlan} —— 组装 {@link ConversationExecutionPlan}</li>
 * </ol></p>
 *
 * <p>核心修正：
 * <ul>
 *   <li>知识路由不复制 super-agent 手搓 n-gram 评分，直接调 {@link IRagRetrievalService} 检索后按 documentId 聚合 top 分数；</li>
 *   <li>{@code shouldAskClarification} 的 {@code 0.55}/{@code 3.0} 阈值进 {@link ChatProperties.Orchestration}；</li>
 *   <li>CAPABILITY / OPEN_CHAT / CHITCHAT 关键词集合外置到 {@link ChatIntentHints}，不散落方法体。</li>
 * </ul></p>
 *
 * @author reuben
 * @since 2026-06-25
 */
@Slf4j
@Service
@AllArgsConstructor
public class ChatPreparationOrchestrator {

    private final IChatMemoryService memoryService;
    private final ChatQueryRewriteService rewriteService;
    private final IRagRetrievalService ragRetrievalService;
    private final ChatProperties properties;
    private final ChatDocumentOptionService documentOptionService;
    private final DocumentQuestionRouter documentQuestionRouter;
    private final ObjectProvider<KnowledgeRouteService> knowledgeRouteServiceProvider;

    /**
     * 准备一轮对话的执行计划。
     *
     * <p>每步落 trace stage，失败 warn 不中断主流程（落 trace error 后走保守 fallback）。</p>
     */
    public ConversationExecutionPlan prepare(StreamLaunchPlan launchPlan, ChatTraceRecorder traceRecorder) {
        String question = launchPlan.getQuestion();
        String conversationId = launchPlan.getConversationId();

        // 第 1 步：加载记忆
        ChatMemoryContext memoryContext = loadMemory(conversationId, traceRecorder);

        // 第 2 步：意图识别（OPEN_CHAT 命中能力/闲聊关键词则跳过改写）
        boolean skipRewrite = looksLikeOpenChat(question, launchPlan.getChatMode());
        ChatTraceRecorder.StageHandle intentStage = traceRecorder == null ? null
                : traceRecorder.startStage(ChatTraceStageCode.INTENT, "intent", "意图识别", null);
        if (traceRecorder != null) {
            Map<String, Object> snapshot = new HashMap<>();
            snapshot.put("chatMode", launchPlan.getChatMode().name());
            snapshot.put("skipRewrite", skipRewrite);
            traceRecorder.completeStage(intentStage, "意图识别完成", snapshot);
        }

        // 第 3 步：查询改写
        ChatRewriteResult rewriteResult = skipRewrite
                ? noRewrite(question)
                : rewriteService.rewrite(question, memoryContext, traceRecorder);

        // 第 4 步：模式路由
        ModeBranch branch = modeBranch(launchPlan, rewriteResult, traceRecorder);

        // 第 5 步：组装 plan
        return buildPlan(launchPlan, memoryContext, rewriteResult, branch);
    }

    private ChatMemoryContext loadMemory(String conversationId, ChatTraceRecorder traceRecorder) {
        // MEMORY stage 由 ChatMemoryServiceImpl.loadMemoryContext 内部负责开关，这里不再重复开 stage
        try {
            ChatMemoryContext ctx = memoryService.loadMemoryContext(conversationId, traceRecorder);
            return ctx;
        } catch (Exception e) {
            log.warn("记忆加载失败，降级空上下文 → conversationId={} err={}", conversationId, e.getMessage());
            return null;
        }
    }

    private boolean looksLikeOpenChat(String question, ChatMode chatMode) {
        if (chatMode == ChatMode.OPEN_CHAT) {
            return true;
        }
        return ChatIntentHints.matchesOpenChat(question);
    }

    private ChatRewriteResult noRewrite(String question) {
        return ChatRewriteResult.builder()
                .rewrittenQuery(question)
                .subQuestions(List.of(question))
                .usedRewrite(false)
                .shouldSplit(false)
                .build();
    }

    private ModeBranch modeBranch(StreamLaunchPlan plan, ChatRewriteResult rewrite, ChatTraceRecorder traceRecorder) {
        ChatTraceRecorder.StageHandle routeStage = traceRecorder == null ? null
                : traceRecorder.startStage(ChatTraceStageCode.ROUTE, "route", "模式路由", null);
        ModeBranch branch = doModeBranch(plan, rewrite, traceRecorder);
        if (traceRecorder != null) {
            Map<String, Object> snapshot = new HashMap<>();
            snapshot.put("executionMode", branch.executionMode().name());
            snapshot.put("selectedDocumentId", branch.selectedDocumentId());
            snapshot.put("isClarification", branch.executionMode() == ExecutionMode.CLARIFICATION);
            traceRecorder.completeStage(routeStage, "路由完成", snapshot);
        }
        return branch;
    }

    private ModeBranch doModeBranch(StreamLaunchPlan plan, ChatRewriteResult rewrite, ChatTraceRecorder traceRecorder) {
        ChatMode chatMode = plan.getChatMode();
        switch (chatMode) {
            case OPEN_CHAT:
                return ModeBranch.of(ExecutionMode.REACT_AGENT, null, null, null);
            case DOCUMENT:
                if (plan.getSelectedDocumentId() == null) {
                    return ModeBranch.clarification(plan, "请选择一个文档后再提问。", List.of(), "DOCUMENT 缺少文档ID");
                }
                // 用户已选文档：影子评估知识路由（不干预选择，仅落 trace 比对）
                recordShadowRoute(plan, rewrite, traceRecorder, plan.getSelectedDocumentId());
                DocumentNavigationDecision nav = decideNavigation(plan.getSelectedDocumentId(), rewrite, traceRecorder);
                return ModeBranch.of(nav.toExecutionMode(), plan.getSelectedDocumentId(),
                        plan.getSelectedDocumentName(), nav);
            case AUTO_DOCUMENT:
                return resolveAutoDocument(plan, rewrite, traceRecorder);
            default:
                return ModeBranch.of(ExecutionMode.REACT_AGENT, null, null, null);
        }
    }

    private ModeBranch resolveAutoDocument(StreamLaunchPlan plan, ChatRewriteResult rewrite,
                                           ChatTraceRecorder traceRecorder) {
        // 阶段：知识路由（scope → topic → document），低置信度 / 模糊 → CLARIFICATION
        RouteCandidate candidate = routeKnowledge(rewrite.getRewrittenQuery(), plan, rewrite, traceRecorder);
        if (candidate == null || candidate.documentId == null) {
            return ModeBranch.clarification(plan,
                    "没有找到与问题匹配的文档，请补充文档信息或选择开放式对话。",
                    List.of(), "AUTO_DOCUMENT 无候选文档");
        }
        ChatProperties.Orchestration cfg = properties.getOrchestration();
        boolean lowConfidence = candidate.confidence < cfg.getClarifyConfidenceThreshold();
        boolean ambiguous = (candidate.topScore - candidate.secondScore) <= cfg.getClarifyTopScoreDiff();
        if (lowConfidence || ambiguous) {
            String reason = lowConfidence ? "候选文档置信度低" : "候选文档评分接近";
            return ModeBranch.clarification(plan,
                    "您是想问关于哪一个文档的问题？",
                    List.of(candidate.documentName == null ? String.valueOf(candidate.documentId) : candidate.documentName),
                    reason);
        }
        // 路由命中：落 AUTO 路由 trace（携带完整 KnowledgeRouteDecision）
        recordAutoRoute(plan, rewrite, traceRecorder, candidate);
        DocumentNavigationDecision nav = decideNavigation(candidate.documentId, rewrite, traceRecorder);
        return ModeBranch.of(nav.toExecutionMode(), candidate.documentId, candidate.documentName, nav)
                .withRoute(candidate.topScore, candidate.secondScore, candidate.confidence);
    }

    /**
     * 知识路由 —— 优先委托 {@link KnowledgeRouteService} 三级路由（scope → topic → document）。
     *
     * <p>路由服务不可用时回退到 RAG 检索聚合评分（修正 super-agent：不强依赖单一通道）。</p>
     */
    private RouteCandidate routeKnowledge(String query, StreamLaunchPlan plan, ChatRewriteResult rewrite,
                                          ChatTraceRecorder traceRecorder) {
        if (query == null || query.isBlank()) {
            return null;
        }
        KnowledgeRouteService routeService = knowledgeRouteServiceProvider.getIfAvailable();
        if (routeService != null) {
            try {
                KnowledgeRouteDecision decision = routeService.route(plan.getQuestion(), query);
                if (decision != null) {
                    DocumentRouteCandidate top = decision.topDocument();
                    if (top != null && top.getDocumentId() != null) {
                        double confidence = decision.getConfidence() == null ? 0.0
                                : decision.getConfidence().doubleValue();
                        double secondScore = 0.0;
                        if (decision.getDocuments() != null && decision.getDocuments().size() > 1) {
                            secondScore = decision.getDocuments().get(1).getScore() == null ? 0.0
                                    : decision.getDocuments().get(1).getScore().doubleValue();
                        }
                        return new RouteCandidate(top.getDocumentId(), top.getDocumentName(),
                                top.getScore() == null ? 0.0 : top.getScore().doubleValue(),
                                secondScore, confidence, decision);
                    }
                }
            } catch (Exception e) {
                log.warn("知识路由服务调用失败，回退 RAG 聚合评分 → err={}", e.getMessage());
            }
        }
        return routeKnowledgeByRag(query, traceRecorder);
    }

    /** RAG 聚合评分回退：不带 documentId filter 检索 → 按 documentId 聚合 top1/top2 分数。 */
    private RouteCandidate routeKnowledgeByRag(String query, ChatTraceRecorder traceRecorder) {
        try {
            RagRetrieveRequest req = RagRetrieveRequest.builder()
                    .query(query)
                    .topK(properties.getOrchestration().getRouteCandidateTopK())
                    .build();
            RagRetrieveResponse resp = ragRetrievalService.retrieve(req);
            if (resp == null || resp.getResults() == null || resp.getResults().isEmpty()) {
                return null;
            }
            Map<Long, List<Double>> docScores = new HashMap<>();
            Map<Long, String> docNames = resolveDocNames(resp.getResults());
            for (RetrievalResult r : resp.getResults()) {
                if (r.getDocumentId() == null || r.getScore() == null) {
                    continue;
                }
                docScores.computeIfAbsent(r.getDocumentId(), k -> new ArrayList<>()).add(r.getScore());
            }
            if (docScores.isEmpty()) {
                return null;
            }
            List<Map.Entry<Long, List<Double>>> ranked = new ArrayList<>(docScores.entrySet());
            ranked.sort(Comparator.comparingDouble((Map.Entry<Long, List<Double>> e) ->
                    e.getValue().stream().max(Double::compare).orElse(0.0)).reversed());
            Map.Entry<Long, List<Double>> top = ranked.get(0);
            double topScore = top.getValue().stream().max(Double::compare).orElse(0.0);
            double secondScore = ranked.size() > 1
                    ? ranked.get(1).getValue().stream().max(Double::compare).orElse(0.0)
                    : 0.0;
            double confidence = ranked.size() == 1 ? 1.0 : topScore / (topScore + secondScore + 1e-6);
            return new RouteCandidate(top.getKey(), docNames.get(top.getKey()), topScore, secondScore, confidence, null);
        } catch (Exception e) {
            log.warn("知识路由 RAG 回退失败，跳过 AUTO_DOCUMENT 候选评分 → err={}", e.getMessage());
            return null;
        }
    }

    /**
     * 解析候选 documentId → documentName 映射。
     *
     * <p>rag 模块的 {@link RetrievalResult} 只携带 documentId，无 documentName，
     * 这里委托 {@link ChatDocumentOptionService} 查已索引文档补名，失败返回空 map（clarification 回退到 documentId 字符串）。</p>
     */
    private Map<Long, String> resolveDocNames(List<RetrievalResult> results) {
        if (results == null || results.isEmpty()) {
            return new HashMap<>();
        }
        Map<Long, String> names = new HashMap<>();
        try {
            for (KnowledgeDocumentOptionVo opt : documentOptionService.listDocumentOptions()) {
                names.put(opt.getDocumentId(), opt.getDocumentName());
            }
        } catch (Exception e) {
            log.warn("文档选项查询失败，clarification 候选名将回退为 documentId → err={}", e.getMessage());
        }
        return names;
    }

    /** DOCUMENT 模式影子路由：用户已选文档，仅落 SHADOW trace 比对路由推荐，不干预选择。 */
    private void recordShadowRoute(StreamLaunchPlan plan, ChatRewriteResult rewrite,
                                   ChatTraceRecorder traceRecorder, Long selectedDocumentId) {
        KnowledgeRouteService routeService = knowledgeRouteServiceProvider.getIfAvailable();
        if (routeService == null || traceRecorder == null) {
            return;
        }
        try {
            routeService.recordShadowRoute(
                    plan.getConversationId(),
                    traceRecorder.getTurnId(),
                    selectedDocumentId,
                    plan.getQuestion(),
                    rewrite == null ? null : rewrite.getRewrittenQuery());
        } catch (Exception e) {
            log.warn("影子路由 trace 落库失败，不中断主流程 → conversationId={} err={}",
                    plan.getConversationId(), e.getMessage());
        }
    }

    /** AUTO_DOCUMENT 模式自动路由：落 AUTO trace（携带完整 KnowledgeRouteDecision）。 */
    private void recordAutoRoute(StreamLaunchPlan plan, ChatRewriteResult rewrite,
                                 ChatTraceRecorder traceRecorder, RouteCandidate candidate) {
        KnowledgeRouteService routeService = knowledgeRouteServiceProvider.getIfAvailable();
        if (routeService == null || traceRecorder == null || candidate.decision() == null) {
            return;
        }
        try {
            routeService.recordAutoRoute(
                    plan.getConversationId(),
                    traceRecorder.getTurnId(),
                    plan.getQuestion(),
                    rewrite == null ? null : rewrite.getRewrittenQuery(),
                    candidate.decision());
        } catch (Exception e) {
            log.warn("自动路由 trace 落库失败，不中断主流程 → conversationId={} err={}",
                    plan.getConversationId(), e.getMessage());
        }
    }

    /**
     * 导航决策 —— 委托 {@link DocumentQuestionRouter} 做规则 + LLM 双引擎判定。
     *
     * <p>替换原 WHOLE_DOCUMENT 占位逻辑，产出完整 DocumentNavigationDecision
     * （action + executionMode + structureAnchor + itemAnchor + retrievalPlan）。</p>
     */
    private DocumentNavigationDecision decideNavigation(Long documentId, ChatRewriteResult rewrite,
                                                        ChatTraceRecorder traceRecorder) {
        return documentQuestionRouter.route(documentId,
                rewrite == null ? null : rewrite.getRewrittenQuery(), rewrite);
    }

    private ConversationExecutionPlan buildPlan(StreamLaunchPlan plan, ChatMemoryContext memory,
                                                 ChatRewriteResult rewrite, ModeBranch branch) {
        return ConversationExecutionPlan.builder()
                .executionMode(branch.executionMode())
                .chatMode(plan.getChatMode())
                .originalQuestion(plan.getQuestion())
                .rewrittenQuery(rewrite.getRewrittenQuery())
                .subQuestions(rewrite.getSubQuestions())
                .rewriteResult(rewrite)
                .selectedDocumentId(branch.selectedDocumentId())
                .selectedDocumentName(branch.selectedDocumentName())
                .routeTopScore(branch.topScore())
                .routeSecondScore(branch.secondScore())
                .routeConfidence(branch.confidence())
                .navigationDecision(branch.navigationDecision())
                .clarificationReply(branch.clarificationReply())
                .clarificationOptions(branch.clarificationOptions())
                .clarificationReason(branch.clarificationReason())
                .longTermSummary(memory == null ? null : memory.getLongTermSummary())
                .recentTranscript(memory == null ? null : memory.getRecentTranscript())
                .build();
    }

    // ======================== 内部结构 ========================

    @lombok.Builder
    private record ModeBranch(
            ExecutionMode executionMode,
            Long selectedDocumentId,
            String selectedDocumentName,
            DocumentNavigationDecision navigationDecision,
            String clarificationReply,
            List<String> clarificationOptions,
            String clarificationReason,
            Double topScore,
            Double secondScore,
            Double confidence
    ) {

        static ModeBranch of(ExecutionMode mode, Long docId, String docName, DocumentNavigationDecision nav) {
            return ModeBranch.builder()
                    .executionMode(mode)
                    .selectedDocumentId(docId)
                    .selectedDocumentName(docName)
                    .navigationDecision(nav)
                    .clarificationOptions(List.of())
                    .build();
        }

        static ModeBranch clarification(StreamLaunchPlan plan, String reply, List<String> options, String reason) {
            return ModeBranch.builder()
                    .executionMode(ExecutionMode.CLARIFICATION)
                    .selectedDocumentId(plan.getSelectedDocumentId())
                    .selectedDocumentName(plan.getSelectedDocumentName())
                    .clarificationReply(reply)
                    .clarificationOptions(options == null ? List.of() : options)
                    .clarificationReason(reason)
                    .build();
        }

        ModeBranch withRoute(Double top, Double second, Double conf) {
            return new ModeBranch(executionMode, selectedDocumentId, selectedDocumentName,
                    navigationDecision, clarificationReply, clarificationOptions, clarificationReason,
                    top, second, conf);
        }
    }

    private record RouteCandidate(Long documentId, String documentName, double topScore,
                                  double secondScore, double confidence,
                                  KnowledgeRouteDecision decision) {
    }
}