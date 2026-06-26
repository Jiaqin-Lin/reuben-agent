package com.reubenagent.chat.orchestrate;

import com.reubenagent.chat.enums.ChatTraceStageCode;
import com.reubenagent.chat.enums.ExecutionMode;
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
 * 结构摘要执行器 —— 仅返回文档目录结构，不调用 LLM、不检索证据。
 *
 * <p>对标 super-agent {@code GraphOnlyExecutor}，reuben 修正：
 * <ul>
 *   <li>直接复用 {@link IDocumentStructureNodeService#listDocumentNodes}，不重造结构读取；</li>
 *   <li>结构摘要以纯文本拼接（前 N 条 nodeNo + title + sectionPath），不调用 LLM；</li>
 *   <li>无证据 → {@code recordEvidenceBudget(0,0)} 落 trace；</li>
 *   <li>阻塞读 DB 委托 {@code Mono.fromCallable + subscribeOn(boundedElastic)}。</li>
 * </ul></p>
 *
 * @author reuben
 * @since 2026-06-25
 */
@Slf4j
@Component
@AllArgsConstructor
public class GraphOnlyExecutor implements ConversationExecutor {

    /** 结构摘要最大节点数，避免超长文档刷屏。 */
    private static final int MAX_NODES_IN_SUMMARY = 50;

    private final IDocumentStructureNodeService structureNodeService;
    private final ChatStreamEventWriter eventWriter;

    @Override
    public ExecutionMode mode() {
        return ExecutionMode.GRAPH_ONLY;
    }

    @Override
    public Flux<String> execute(ChatTaskInfo taskInfo) {
        return Flux.defer(() -> {
            Long documentId = taskInfo.getExecutionPlan() == null
                    ? null : taskInfo.getExecutionPlan().getSelectedDocumentId();
            if (documentId == null) {
                SinkEmitHelper.emitNext(taskInfo.getSink(),
                        eventWriter.error("GRAPH_ONLY 模式缺少 selectedDocumentId",
                                taskInfo.getConversationId(), taskInfo.getTurnId()));
                return Flux.just("未选择文档，无法展示结构。");
            }
            emitThinking(taskInfo, "正在加载文档结构。");
            return Mono.fromCallable(() -> structureNodeService.listDocumentNodes(documentId, null))
                    .subscribeOn(Schedulers.boundedElastic())
                    .onErrorMap(Exception.class, e -> {
                        log.warn("文档结构读取失败 → documentId={} err={}",
                                documentId, e.getMessage());
                        return e;
                    })
                    .flatMapMany(nodes -> {
                        String summary = buildSummary(nodes, documentId);
                        recordEvidenceBudget(taskInfo, 0, 0);
                        return Flux.just(summary);
                    });
        });
    }

    /** 拼接结构摘要：前 N 条 `nodeNo + " " + title + " (path=" + sectionPath + ")"`。 */
    private String buildSummary(List<DocumentStructureNode> nodes, Long documentId) {
        if (nodes == null || nodes.isEmpty()) {
            return "文档（id=" + documentId + "）暂无可用的结构信息。";
        }
        int limit = Math.min(nodes.size(), MAX_NODES_IN_SUMMARY);
        StringBuilder sb = new StringBuilder(128 * limit);
        sb.append("文档结构如下（共 ").append(nodes.size()).append(" 个节点，展示前 ")
                .append(limit).append(" 个）：\n");
        for (int i = 0; i < limit; i++) {
            DocumentStructureNode node = nodes.get(i);
            sb.append(node.getNodeNo() == null ? "-" : node.getNodeNo()).append(' ')
                    .append(node.getTitle() == null ? "" : node.getTitle());
            if (node.getSectionPath() != null && !node.getSectionPath().isBlank()) {
                sb.append(" (path=").append(node.getSectionPath()).append(')');
            }
            sb.append('\n');
        }
        if (nodes.size() > limit) {
            sb.append("...（剩余 ").append(nodes.size() - limit).append(" 个节点已省略）\n");
        }
        return sb.toString();
    }

    private void recordEvidenceBudget(ChatTaskInfo taskInfo, int rendered, int omitted) {
        ChatTraceRecorder recorder = taskInfo == null ? null : taskInfo.getTraceRecorder();
        if (recorder == null) {
            return;
        }
        ChatTraceRecorder.StageHandle stage = recorder.startStage(
                ChatTraceStageCode.EVIDENCE_BUDGET, "GRAPH_ONLY", "证据门控", null);
        Map<String, Object> snapshot = new HashMap<>();
        snapshot.put("rendered", rendered);
        snapshot.put("omitted", omitted);
        recorder.completeStage(stage, "结构摘要无需证据", snapshot);
    }

    private void emitThinking(ChatTaskInfo taskInfo, String text) {
        if (taskInfo == null || taskInfo.getSink() == null) {
            return;
        }
        SinkEmitHelper.emitNext(taskInfo.getSink(),
                eventWriter.thinking(text, taskInfo.getConversationId(), taskInfo.getTurnId()));
    }
}
