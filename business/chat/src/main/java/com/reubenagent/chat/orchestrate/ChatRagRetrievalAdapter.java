package com.reubenagent.chat.orchestrate;

import com.reubenagent.chat.config.ChatProperties;
import com.reubenagent.chat.enums.ChatErrorCode;
import com.reubenagent.chat.enums.ChatTraceStageCode;
import com.reubenagent.chat.exception.ChatException;
import com.reubenagent.chat.model.SearchReference;
import com.reubenagent.chat.model.orchestrate.ChatRetrievalResult;
import com.reubenagent.chat.model.orchestrate.ChatRewriteResult;
import com.reubenagent.chat.model.orchestrate.ConversationExecutionPlan;
import com.reubenagent.chat.support.IChatRetrievalObserveStoreInternal;
import com.reubenagent.chat.support.SearchReferenceMapper;
import com.reubenagent.chat.trace.ChatTraceRecorder;
import com.reubenagent.rag.dto.RagRetrieveRequest;
import com.reubenagent.rag.model.RetrievalResult;
import com.reubenagent.rag.service.IRagRetrievalService;
import com.reubenagent.rag.vo.RagRetrieveResponse;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 检索适配层 —— chat 侧调 reuben-agent rag 模块，不重造引擎。
 *
 * <p>核心修正：super-agent 在 chat 模块重造了 {@code RagRetrievalEngine}（含双通道 + RRF + 父块提升
 * + rerank），reuben 全部复用 {@link IRagRetrievalService}，chat 侧只负责：
 * <ol>
 *   <li>把 {@link ChatRewriteResult#getSubQuestions()} 逐个转 {@link RagRetrieveRequest}
 *       （query + topK + filterFields=documentId）</li>
 *   <li>调用 rag 服务，聚合 {@link RagRetrieveResponse}</li>
 *   <li>门控：分数阈值 + 数量 budget（来自 {@link ChatProperties.Rag}）</li>
 *   <li>落可观测：检索结果 + 通道执行（noop 占位，Phase 8 接入 MyBatis）</li>
 *   <li>映射为 {@link ChatRetrievalResult}（含 {@link SearchReference} 列表）</li>
 * </ol></p>
 *
 * <p>失败处理：单子问题检索失败 warn 不中断（落 trace error），返回空结果；
 * 整体调用层（如 documentId 为 null 但 DOCUMENT 模式）抛 {@link ChatException}({@link ChatErrorCode#RETRIEVE_FAILED})。</p>
 *
 * @author reuben
 * @since 2026-06-25
 */
@Slf4j
@Service
@AllArgsConstructor
public class ChatRagRetrievalAdapter {

    private final IRagRetrievalService ragRetrievalService;
    private final SearchReferenceMapper referenceMapper;
    private final IChatRetrievalObserveStoreInternal observeStore;
    private final ChatProperties properties;

    /**
     * 按 plan 检索证据。
     *
     * <p>无子问题 → 用 {@code rewrittenQuery} 作单次检索；有子问题 → 逐个检索后聚合。
     * 文档模式（{@code selectedDocumentId != null}）→ 带 {@code filterFields=documentId}。</p>
     */
    public List<ChatRetrievalResult> retrieve(ConversationExecutionPlan plan, ChatTaskInfo taskInfo) {
        return retrieveWithNodeFilter(plan, taskInfo, null);
    }

    /**
     * 按 plan 检索证据，可选传入 nodeId 作为 {@code filterFields} 缩小检索范围。
     *
     * <p>{@code scopeNodeId} 非空时，{@code filterFields} 同时带 {@code documentId} 与
     * {@code structureNodeId}，供 rag 侧按节点过滤；为 null 退化为纯文档过滤。</p>
     *
     * <p>channel_type 恒为 "hybrid"：rag 模块返回融合结果不暴露 per-channel，per-channel 保真
     * 需 rag 侧响应携带 channel 元数据，本期登记待测。</p>
     */
    public List<ChatRetrievalResult> retrieveWithNodeFilter(ConversationExecutionPlan plan,
                                                            ChatTaskInfo taskInfo,
                                                            Long scopeNodeId) {
        ChatTraceRecorder recorder = taskInfo == null ? null : taskInfo.getTraceRecorder();
        ChatTraceRecorder.StageHandle stage = recorder == null ? null
                : recorder.startStage(ChatTraceStageCode.RAG_RETRIEVE,
                        plan == null ? "RETRIEVAL" : plan.getExecutionMode().name(),
                        "RAG 检索", null);

        try {
            List<ChatRetrievalResult> results = doRetrieve(plan, recorder, taskInfo, scopeNodeId);
            if (recorder != null) {
                int totalEvidence = results.stream().mapToInt(r ->
                        r.getResults() == null ? 0 : r.getResults().size()).sum();
                Map<String, Object> snapshot = new HashMap<>(3);
                snapshot.put("subQuestionCount", results.size());
                snapshot.put("totalEvidence", totalEvidence);
                snapshot.put("scopeNodeId", scopeNodeId);
                recorder.completeStage(stage, "检索完成", snapshot);
            }
            // 阶段：落可观测（noop 占位，Phase 8 接入）
            safeObserve(taskInfo, results);
            return results;
        } catch (ChatException e) {
            if (recorder != null) {
                recorder.failStage(stage, "检索失败", e.getMessage(), null);
            }
            throw e;
        } catch (Exception e) {
            if (recorder != null) {
                recorder.failStage(stage, "检索失败", e.getMessage(), null);
            }
            log.warn("RAG 检索失败 → conversationId={} err={}",
                    taskInfo == null ? null : taskInfo.getConversationId(), e.getMessage());
            throw new ChatException(ChatErrorCode.RETRIEVE_FAILED, e.getMessage(), e);
        }
    }

    private List<ChatRetrievalResult> doRetrieve(ConversationExecutionPlan plan, ChatTraceRecorder recorder,
                                                 ChatTaskInfo taskInfo, Long scopeNodeId) {
        ChatRewriteResult rewrite = plan.getRewriteResult();
        List<String> subQuestions = pickSubQuestions(plan, rewrite);
        Long docId = plan.getSelectedDocumentId();
        String docName = plan.getSelectedDocumentName();
        Map<String, String> filter = buildFilter(docId, scopeNodeId);

        List<ChatRetrievalResult> aggregated = new ArrayList<>(subQuestions.size());
        int itemIndex = 1;
        for (int i = 0; i < subQuestions.size(); i++) {
            String query = subQuestions.get(i);
            ChatRetrievalResult result = retrieveOne(i, query, filter, docName, itemIndex, recorder, taskInfo);
            aggregated.add(result);
            itemIndex += result.getReferences() == null ? 0 : result.getReferences().size();
        }
        return aggregated;
    }

    private ChatRetrievalResult retrieveOne(int index, String query, Map<String, String> filter,
                                            String documentName, int startIndex,
                                            ChatTraceRecorder recorder, ChatTaskInfo taskInfo) {
        long t0 = System.currentTimeMillis();
        RagRetrieveRequest req = RagRetrieveRequest.builder()
                .query(query)
                .topK(properties.getRag().getTopK())
                .filterFields(filter)
                .build();
        RagRetrieveResponse resp;
        try {
            resp = ragRetrievalService.retrieve(req);
        } catch (Exception e) {
            log.warn("子问题检索失败 → subIndex={} query={} err={}", index, query, e.getMessage());
            safeRecordChannel(taskInfo, index, "hybrid",
                    System.currentTimeMillis() - t0, 0, 0, null, null);
            return ChatRetrievalResult.builder()
                    .subQuestionIndex(index)
                    .subQuestion(query)
                    .query(query)
                    .totalCostMs(System.currentTimeMillis() - t0)
                    .results(Collections.emptyList())
                    .references(Collections.emptyList())
                    .build();
        }
        long costMs = System.currentTimeMillis() - t0;
        List<RetrievalResult> raw = resp == null || resp.getResults() == null
                ? Collections.emptyList() : resp.getResults();
        List<RetrievalResult> gated = gateEvidence(raw);
        List<SearchReference> refs = referenceMapper.fromRetrievals(gated, documentName,
                index, query, startIndex);

        // 阶段：落通道执行（noop 占位）
        BigDecimal maxScore = maxScore(gated);
        BigDecimal minScore = minScore(gated);
        safeRecordChannel(taskInfo, index, "hybrid",
                costMs, raw.size(), gated.size(), maxScore, minScore);

        return ChatRetrievalResult.builder()
                .subQuestionIndex(index)
                .subQuestion(query)
                .query(query)
                .totalCostMs(costMs)
                .results(gated)
                .references(refs)
                .build();
    }

    /** 证据门控：分数阈值 + 数量 budget。 */
    private List<RetrievalResult> gateEvidence(List<RetrievalResult> raw) {
        if (raw == null || raw.isEmpty()) {
            return Collections.emptyList();
        }
        ChatProperties.Rag cfg = properties.getRag();
        double minScore = cfg.getMinScore() == null ? 0.0 : cfg.getMinScore();
        int maxCount = cfg.getMaxEvidenceCount() == null ? Integer.MAX_VALUE : cfg.getMaxEvidenceCount();
        List<RetrievalResult> passed = new ArrayList<>(raw.size());
        for (RetrievalResult r : raw) {
            if (r == null || r.getScore() == null) {
                continue;
            }
            if (r.getScore() >= minScore) {
                passed.add(r);
            }
        }
        if (passed.size() > maxCount) {
            passed = new ArrayList<>(passed.subList(0, maxCount));
        }
        return passed;
    }

    private List<String> pickSubQuestions(ConversationExecutionPlan plan, ChatRewriteResult rewrite) {
        if (rewrite != null && rewrite.hasSubQuestions()) {
            return rewrite.getSubQuestions();
        }
        String q = plan.getRewrittenQuery() != null ? plan.getRewrittenQuery() : plan.getOriginalQuestion();
        return List.of(q);
    }

    private Map<String, String> buildFilter(Long documentId, Long scopeNodeId) {
        if (documentId == null && scopeNodeId == null) {
            return null;
        }
        Map<String, String> filter = new HashMap<>(4);
        if (documentId != null) {
            filter.put("documentId", String.valueOf(documentId));
        }
        if (scopeNodeId != null) {
            filter.put("structureNodeId", String.valueOf(scopeNodeId));
        }
        return filter;
    }

    private BigDecimal maxScore(List<RetrievalResult> results) {
        if (results == null || results.isEmpty()) {
            return null;
        }
        double max = Double.NEGATIVE_INFINITY;
        for (RetrievalResult r : results) {
            if (r != null && r.getScore() != null && r.getScore() > max) {
                max = r.getScore();
            }
        }
        return Double.isInfinite(max) ? null : BigDecimal.valueOf(max);
    }

    private BigDecimal minScore(List<RetrievalResult> results) {
        if (results == null || results.isEmpty()) {
            return null;
        }
        double min = Double.POSITIVE_INFINITY;
        for (RetrievalResult r : results) {
            if (r != null && r.getScore() != null && r.getScore() < min) {
                min = r.getScore();
            }
        }
        return Double.isInfinite(min) ? null : BigDecimal.valueOf(min);
    }

    private void safeObserve(ChatTaskInfo taskInfo, List<ChatRetrievalResult> results) {
        if (taskInfo == null || observeStore == null) {
            return;
        }
        try {
            observeStore.recordRetrievalResults(taskInfo.getConversationId(),
                    taskInfo.getTurnId(), taskInfo.getTraceId(), results);
        } catch (Exception e) {
            log.warn("检索结果落库失败（不中断）→ conversationId={} err={}",
                    taskInfo.getConversationId(), e.getMessage());
        }
    }

    private void safeRecordChannel(ChatTaskInfo taskInfo,
                                   Integer subQuestionIndex, String channelType,
                                   long durationMs, int recalledCount, int acceptedCount,
                                   BigDecimal maxScore, BigDecimal minScore) {
        if (observeStore == null) {
            return;
        }
        try {
            String conversationId = taskInfo == null ? null : taskInfo.getConversationId();
            Long turnId = taskInfo == null ? null : taskInfo.getTurnId();
            String traceId = taskInfo == null ? null : taskInfo.getTraceId();
            observeStore.recordChannelExecution(conversationId, turnId, traceId,
                    subQuestionIndex, channelType, durationMs, recalledCount, acceptedCount,
                    maxScore, minScore);
        } catch (Exception e) {
            log.warn("通道执行落库失败（不中断）→ channel={} err={}", channelType, e.getMessage());
        }
    }
}