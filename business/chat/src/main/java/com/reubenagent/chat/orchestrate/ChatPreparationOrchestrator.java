package com.reubenagent.chat.orchestrate;

import com.reubenagent.chat.config.ChatProperties;
import com.reubenagent.chat.enums.ChatMode;
import com.reubenagent.chat.enums.ChatTraceStageCode;
import com.reubenagent.chat.enums.ExecutionMode;
import com.reubenagent.chat.model.memory.ChatMemoryContext;
import com.reubenagent.chat.model.orchestrate.ChatRewriteResult;
import com.reubenagent.chat.model.orchestrate.ConversationExecutionPlan;
import com.reubenagent.chat.model.orchestrate.DocumentNavigationAction;
import com.reubenagent.chat.model.orchestrate.DocumentNavigationDecision;
import com.reubenagent.chat.model.orchestrate.NavigationScopeMode;
import com.reubenagent.chat.service.IChatMemoryService;
import com.reubenagent.chat.support.ChatIntentHints;
import com.reubenagent.chat.trace.ChatTraceRecorder;
import com.reubenagent.rag.dto.RagRetrieveRequest;
import com.reubenagent.rag.model.RetrievalResult;
import com.reubenagent.rag.service.IRagRetrievalService;
import com.reubenagent.rag.vo.RagRetrieveResponse;
import lombok.extern.slf4j.Slf4j;
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
public class ChatPreparationOrchestrator {

    private final IChatMemoryService memoryService;
    private final ChatQueryRewriteService rewriteService;
    private final IRagRetrievalService ragRetrievalService;
    private final ChatProperties properties;

    public ChatPreparationOrchestrator(IChatMemoryService memoryService,
                                       ChatQueryRewriteService rewriteService,
                                       IRagRetrievalService ragRetrievalService,
                                       ChatProperties properties) {
        this.memoryService = memoryService;
        this.rewriteService = rewriteService;
        this.ragRetrievalService = ragRetrievalService;
        this.properties = properties;
    }

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
            traceRecorder.completeStage(intentStage, "意图识别完成", Map.of(
                    "chatMode", launchPlan.getChatMode().name(),
                    "skipRewrite", skipRewrite));
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
        ChatTraceRecorder.StageHandle stage = traceRecorder == null ? null
                : traceRecorder.startStage(ChatTraceStageCode.MEMORY, "memory", "加载记忆上下文", null);
        try {
            ChatMemoryContext ctx = memoryService.loadMemoryContext(conversationId, traceRecorder);
            if (traceRecorder != null) {
                traceRecorder.completeStage(stage, "记忆加载完成", Map.of(
                        "hasSummary", ctx != null && ctx.getLongTermSummary() != null && !ctx.getLongTermSummary().isBlank(),
                        "recentTurns", ctx == null || ctx.getRecentTurns() == null ? 0 : ctx.getRecentTurns().size()));
            }
            return ctx;
        } catch (Exception e) {
            log.warn("记忆加载失败，降级空上下文 → conversationId={} err={}", conversationId, e.getMessage());
            if (traceRecorder != null) {
                traceRecorder.failStage(stage, "记忆加载失败", e.getMessage(), null);
            }
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
            snapshot.put("executionMode", branch.executionMode.name());
            snapshot.put("selectedDocumentId", branch.selectedDocumentId);
            snapshot.put("isClarification", branch.executionMode == ExecutionMode.CLARIFICATION);
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
        RouteCandidate candidate = routeKnowledge(rewrite.getRewrittenQuery(), traceRecorder);
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
        DocumentNavigationDecision nav = decideNavigation(candidate.documentId, rewrite, traceRecorder);
        return ModeBranch.of(nav.toExecutionMode(), candidate.documentId, candidate.documentName, nav)
                .withRoute(candidate.topScore, candidate.secondScore, candidate.confidence);
    }

    /**
     * 知识路由 —— 委托 {@link IRagRetrievalService} 做候选文档评分。
     *
     * <p>不带 documentId filter 检索 → 按 documentId 聚合 top1/top2 分数 → 取分数最高文档作为候选。
     * 不复制 super-agent 的手搓 n-gram 评分逻辑。</p>
     */
    private RouteCandidate routeKnowledge(String query, ChatTraceRecorder traceRecorder) {
        if (query == null || query.isBlank()) {
            return null;
        }
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
            Map<Long, String> docNames = new HashMap<>();
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
            return new RouteCandidate(top.getKey(), docNames.get(top.getKey()), topScore, secondScore, confidence);
        } catch (Exception e) {
            log.warn("知识路由失败，跳过 AUTO_DOCUMENT 候选评分 → err={}", e.getMessage());
            return null;
        }
    }

    /** 文档导航决策 —— Phase 5 简化版：默认 DIRECT_RETRIEVAL，结构定位类问题升级为 LOCATE_THEN_RETRIEVE。 */
    private DocumentNavigationDecision decideNavigation(Long documentId, ChatRewriteResult rewrite,
                                                        ChatTraceRecorder traceRecorder) {
        String q = rewrite == null ? "" : rewrite.getRewrittenQuery();
        boolean structureHint = q.contains("第几") || q.contains("哪一节") || q.contains("哪个章节")
                || q.contains("目录") || q.contains("结构") || q.contains("在哪一") || q.contains("章节");
        DocumentNavigationAction action = structureHint
                ? DocumentNavigationAction.LOCATE_THEN_RETRIEVE
                : DocumentNavigationAction.DIRECT_RETRIEVAL;
        return DocumentNavigationDecision.builder()
                .action(action)
                .scopeMode(NavigationScopeMode.WHOLE_DOCUMENT)
                .reason(structureHint ? "问题含结构定位词" : "直接证据检索")
                .build();
    }

    private ConversationExecutionPlan buildPlan(StreamLaunchPlan plan, ChatMemoryContext memory,
                                                 ChatRewriteResult rewrite, ModeBranch branch) {
        return ConversationExecutionPlan.builder()
                .executionMode(branch.executionMode)
                .chatMode(plan.getChatMode())
                .originalQuestion(plan.getQuestion())
                .rewrittenQuery(rewrite.getRewrittenQuery())
                .subQuestions(rewrite.getSubQuestions())
                .rewriteResult(rewrite)
                .selectedDocumentId(branch.selectedDocumentId)
                .selectedDocumentName(branch.selectedDocumentName)
                .routeTopScore(branch.topScore)
                .routeSecondScore(branch.secondScore)
                .routeConfidence(branch.confidence)
                .navigationDecision(branch.navigationDecision)
                .clarificationReply(branch.clarificationReply)
                .clarificationOptions(branch.clarificationOptions)
                .clarificationReason(branch.clarificationReason)
                .longTermSummary(memory == null ? null : memory.getLongTermSummary())
                .recentTranscript(memory == null ? null : memory.getRecentTranscript())
                .build();
    }

    // ======================== 内部结构 ========================

    private static class ModeBranch {
        ExecutionMode executionMode;
        Long selectedDocumentId;
        String selectedDocumentName;
        DocumentNavigationDecision navigationDecision;
        String clarificationReply;
        List<String> clarificationOptions;
        String clarificationReason;
        Double topScore;
        Double secondScore;
        Double confidence;

        static ModeBranch of(ExecutionMode mode, Long docId, String docName, DocumentNavigationDecision nav) {
            ModeBranch b = new ModeBranch();
            b.executionMode = mode;
            b.selectedDocumentId = docId;
            b.selectedDocumentName = docName;
            b.navigationDecision = nav;
            b.clarificationOptions = List.of();
            return b;
        }

        static ModeBranch clarification(StreamLaunchPlan plan, String reply, List<String> options, String reason) {
            ModeBranch b = new ModeBranch();
            b.executionMode = ExecutionMode.CLARIFICATION;
            b.selectedDocumentId = plan.getSelectedDocumentId();
            b.selectedDocumentName = plan.getSelectedDocumentName();
            b.clarificationReply = reply;
            b.clarificationOptions = options == null ? List.of() : options;
            b.clarificationReason = reason;
            return b;
        }

        ModeBranch withRoute(Double top, Double second, Double conf) {
            this.topScore = top;
            this.secondScore = second;
            this.confidence = conf;
            return this;
        }
    }

    private record RouteCandidate(Long documentId, String documentName, double topScore, double secondScore, double confidence) {
    }
}