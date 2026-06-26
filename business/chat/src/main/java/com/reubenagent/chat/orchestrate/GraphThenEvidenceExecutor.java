package com.reubenagent.chat.orchestrate;

import com.reubenagent.chat.enums.ChatErrorCode;
import com.reubenagent.chat.enums.ChatTraceStageCode;
import com.reubenagent.chat.enums.ExecutionMode;
import com.reubenagent.chat.exception.ChatException;
import com.reubenagent.chat.model.SearchReference;
import com.reubenagent.chat.model.orchestrate.ChatRetrievalResult;
import com.reubenagent.chat.model.orchestrate.ConversationExecutionPlan;
import com.reubenagent.chat.support.ChatStreamEventWriter;
import com.reubenagent.chat.support.SinkEmitHelper;
import com.reubenagent.chat.trace.ChatTraceRecorder;
import com.reubenagent.document.entity.DocumentStructureNode;
import com.reubenagent.document.service.IDocumentStructureNodeService;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 先定位结构节点 → 再带 nodeId 过滤检索证据 → 复用 {@link RagAnswerExecutor#assembleAndStream} 流式生成。
 *
 * <p>对标 super-agent {@code GraphThenEvidenceExecutor}，reuben 修正：
 * <ul>
 *   <li>结构读取复用 {@link IDocumentStructureNodeService#listDocumentNodes}；</li>
 *   <li>节点匹配用简单 substring（title contains query 关键词），复杂分词留 Phase 9；</li>
 *   <li>检索委托 {@link ChatRagRetrievalAdapter#retrieveWithNodeFilter}，不重造引擎；</li>
 *   <li>组装 + 流式段复用 {@link RagAnswerExecutor#assembleAndStream}，避免重复 prompt 拼装。</li>
 * </ul></p>
 *
 * @author reuben
 * @since 2026-06-25
 */
@Slf4j
@Component
@AllArgsConstructor
public class GraphThenEvidenceExecutor implements ConversationExecutor {

    private final IDocumentStructureNodeService structureNodeService;
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
            return Mono.fromCallable(() -> structureNodeService.listDocumentNodes(documentId, null))
                    .subscribeOn(Schedulers.boundedElastic())
                    .onErrorMap(Exception.class, e -> {
                        log.warn("文档结构读取失败 → documentId={} err={}", documentId, e.getMessage());
                        return new ChatException(ChatErrorCode.RETRIEVE_FAILED, e.getMessage(), e);
                    })
                    .flatMapMany(nodes -> {
                        Long scopeNodeId = locateNode(nodes, plan);
                        if (scopeNodeId != null) {
                            emitThinking(taskInfo, "已定位到目标章节，开始检索证据。");
                        } else {
                            emitThinking(taskInfo, "未定位到具体章节，全文档检索证据。");
                        }
                        return Mono.fromCallable(() -> retrievalAdapter.retrieveWithNodeFilter(
                                        plan, taskInfo, scopeNodeId))
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

    /** 在 nodes 中按 title contains query token 找最佳匹配节点；找不到返回 null。 */
    private Long locateNode(List<DocumentStructureNode> nodes, ConversationExecutionPlan plan) {
        if (nodes == null || nodes.isEmpty()) {
            return null;
        }
        String query = plan.getRewrittenQuery() != null ? plan.getRewrittenQuery() : plan.getOriginalQuestion();
        if (query == null || query.isBlank()) {
            return null;
        }
        String normalized = query.trim();
        DocumentStructureNode best = null;
        int bestLen = Integer.MAX_VALUE;
        for (DocumentStructureNode node : nodes) {
            if (node == null || node.getTitle() == null) {
                continue;
            }
            String title = node.getTitle();
            if (title.contains(normalized) && title.length() < bestLen) {
                best = node;
                bestLen = title.length();
            }
        }
        if (best == null) {
            // 简单关键词扫描：取问题中长度 >=2 的子串匹配
            for (DocumentStructureNode node : nodes) {
                if (node == null || node.getTitle() == null) {
                    continue;
                }
                String title = node.getTitle();
                for (int i = 0; i + 2 <= normalized.length(); i++) {
                    String token = normalized.substring(i, i + 2);
                    if (title.contains(token)) {
                        return node.getId();
                    }
                }
            }
            return null;
        }
        return best.getId();
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
