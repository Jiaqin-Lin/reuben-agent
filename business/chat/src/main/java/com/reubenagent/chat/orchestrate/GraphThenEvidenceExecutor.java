package com.reubenagent.chat.orchestrate;

import com.reubenagent.chat.enums.ChatErrorCode;
import com.reubenagent.chat.enums.ExecutionMode;
import com.reubenagent.chat.exception.ChatException;
import com.reubenagent.chat.model.orchestrate.ConversationExecutionPlan;
import com.reubenagent.chat.support.ChatStreamEventWriter;
import com.reubenagent.chat.support.SinkEmitHelper;
import com.reubenagent.document.model.graph.GraphSection;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * 先定位结构节点 → 再带 nodeId 过滤检索证据 → 复用 {@link RagAnswerExecutor#afterRetrieval} 流式生成。
 *
 * <p>对标 super-agent {@code GraphThenEvidenceExecutor}，reuben 修正：
 * <ul>
 *   <li>章节定位委托 {@link StructureGraphQueryEngine}（Neo4j 优先 MySQL 回退），替代原直接读 MySQL 全量节点；</li>
 *   <li>定位到的 structureNodeId 通过 {@link ChatRagRetrievalAdapter#retrieveWithNodeFilter} 缩小检索范围；</li>
 *   <li>组装 + 流式段复用 {@link RagAnswerExecutor#afterRetrieval}，避免重复 prompt 拼装。</li>
 * </ul></p>
 *
 * @author reuben
 * @since 2026-06-25
 */
@Slf4j
@Component
@AllArgsConstructor
public class GraphThenEvidenceExecutor implements ConversationExecutor {

    private final StructureGraphQueryEngine graphQueryEngine;
    private final ChatRagRetrievalAdapter retrievalAdapter;
    private final RagAnswerExecutor ragAnswerExecutor;
    private final ChatStreamEventWriter eventWriter;

    @Override
    public ExecutionMode mode() {
        return ExecutionMode.GRAPH_THEN_EVIDENCE;
    }

    @Override
    public Flux<String> execute(ChatTaskInfo taskInfo) {
        return Flux.defer(() -> {
            ConversationExecutionPlan plan = taskInfo.getExecutionPlan();
            if (plan == null) {
                return Flux.<String>error(new ChatException(ChatErrorCode.PARAM_INVALID,
                        "GraphThenEvidenceExecutor 缺少 executionPlan"));
            }
            Long documentId = plan.getSelectedDocumentId();
            if (documentId == null) {
                return Flux.<String>error(new ChatException(ChatErrorCode.PARAM_INVALID,
                        "GRAPH_THEN_EVIDENCE 模式缺少 selectedDocumentId"));
            }
            emitThinking(taskInfo, "正在加载文档结构并定位相关章节。");
            return Mono.fromCallable(() -> locateSection(documentId, plan))
                    .subscribeOn(Schedulers.boundedElastic())
                    .onErrorMap(Exception.class, e -> {
                        log.warn("章节定位失败 → documentId={} err={}", documentId, e.getMessage());
                        return new ChatException(ChatErrorCode.RETRIEVE_FAILED, e.getMessage(), e);
                    })
                    .flatMapMany(scopeNodeId -> {
                        if (scopeNodeId != null) {
                            emitThinking(taskInfo, "已定位到目标章节，开始检索证据。");
                        } else {
                            emitThinking(taskInfo, "未定位到具体章节，全文档检索证据。");
                        }
                        final Long nodeId = scopeNodeId;
                        return Mono.fromCallable(() -> retrievalAdapter.retrieveWithNodeFilter(
                                        plan, taskInfo, nodeId))
                                .subscribeOn(Schedulers.boundedElastic())
                                .onErrorMap(ChatException.class, e -> e)
                                .onErrorMap(Exception.class,
                                        e -> new ChatException(ChatErrorCode.RETRIEVE_FAILED, e.getMessage(), e))
                                .flatMapMany(results -> ragAnswerExecutor.afterRetrieval(taskInfo, results))
                                .onErrorResume(error -> {
                                    ChatException wrapped = error instanceof ChatException
                                            ? (ChatException) error
                                            : new ChatException(ChatErrorCode.GENERATION_FAILED,
                                                    error.getMessage(), error);
                                    emitError(taskInfo, wrapped.getMessage());
                                    return Flux.error(wrapped);
                                });
                    });
        });
    }

    /**
     * 优先用导航决策已 resolve 的章节锚点（itemAnchor / structureAnchor），
     * 否则通过图查询引擎按问题定位最佳章节 nodeId，找不到返回 null。
     */
    private Long locateSection(Long documentId, ConversationExecutionPlan plan) {
        // 锚点优先：导航决策已 resolve 出精确章节，直接用，避免重复定位
        if (plan.getNavigationDecision() != null) {
            var nav = plan.getNavigationDecision();
            if (nav.getItemAnchor() != null && nav.getItemAnchor().getStructureNodeId() != null) {
                return nav.getItemAnchor().getStructureNodeId();
            }
            if (nav.getStructureAnchor() != null && nav.getStructureAnchor().getStructureNodeId() != null) {
                return nav.getStructureAnchor().getStructureNodeId();
            }
        }
        String query = plan.getRewrittenQuery() != null ? plan.getRewrittenQuery() : plan.getOriginalQuestion();
        if (query == null || query.isBlank()) {
            return null;
        }
        try {
            GraphSection best = graphQueryEngine.graphService().findBestSection(documentId, query, null);
            return best == null ? null : best.getNodeId();
        } catch (Exception e) {
            log.warn("图查询定位章节失败，回退全文档检索 → documentId={} err={}", documentId, e.getMessage());
            return null;
        }
    }

    private void emitThinking(ChatTaskInfo taskInfo, String text) {
        if (taskInfo == null || taskInfo.getSink() == null) {
            return;
        }
        SinkEmitHelper.emitNext(taskInfo.getSink(),
                eventWriter.thinking(text, taskInfo.getConversationId(), taskInfo.getTurnId()));
    }

    private void emitError(ChatTaskInfo taskInfo, String message) {
        if (taskInfo == null || taskInfo.getSink() == null) {
            return;
        }
        SinkEmitHelper.emitNext(taskInfo.getSink(),
                eventWriter.error(message, taskInfo.getConversationId(), taskInfo.getTurnId()));
    }
}
