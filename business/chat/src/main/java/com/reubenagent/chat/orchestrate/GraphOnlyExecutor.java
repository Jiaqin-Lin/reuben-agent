package com.reubenagent.chat.orchestrate;

import com.reubenagent.chat.config.ChatProperties;
import com.reubenagent.chat.enums.ChatTraceStageCode;
import com.reubenagent.chat.enums.ExecutionMode;
import com.reubenagent.chat.service.ObservedChatModelService;
import com.reubenagent.chat.support.ChatStreamEventWriter;
import com.reubenagent.chat.support.SinkEmitHelper;
import com.reubenagent.chat.trace.ChatTraceRecorder;
import com.reubenagent.document.model.graph.GraphSection;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 结构摘要执行器 —— 仅返回文档目录结构，不检索证据。
 *
 * <p>对标 super-agent {@code GraphOnlyExecutor}，reuben 修正：
 * <ul>
 *   <li>结构读取复用 {@link StructureGraphQueryEngine#safeListSections}（背后是 Composite 图服务，Neo4j 优先 MySQL 回退）；</li>
 *   <li>邻接/大纲类问题经 {@link GraphAnswerRenderer} 精准渲染；</li>
 *   <li>大文档（节点数 &gt; 阈值）加 LLM 压缩摘要，阈值进 {@link ChatProperties.Navigation}；</li>
 *   <li>无证据 → {@code recordEvidenceBudget(0,0)} 落 trace；阻塞读 DB 委托 boundedElastic。</li>
 * </ul></p>
 *
 * @author reuben
 * @since 2026-06-25
 */
@Slf4j
@Component
@AllArgsConstructor
public class GraphOnlyExecutor implements ConversationExecutor {

    private final StructureGraphQueryEngine graphQueryEngine;
    private final GraphAnswerRenderer answerRenderer;
    private final ObservedChatModelService observedChatModelService;
    private final ChatStreamEventWriter eventWriter;
    private final ChatProperties properties;

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
            String question = taskInfo.getExecutionPlan().getOriginalQuestion();
            emitThinking(taskInfo, "正在加载文档结构。");
            return Mono.fromCallable(() -> renderGraphOnly(documentId, question, taskInfo))
                    .subscribeOn(Schedulers.boundedElastic())
                    .onErrorMap(Exception.class, e -> {
                        log.warn("文档结构读取失败 → documentId={} err={}", documentId, e.getMessage());
                        return e;
                    })
                    .flatMapMany(summary -> {
                        recordEvidenceBudget(taskInfo, 0, 0);
                        return Flux.just(summary);
                    });
        });
    }

    /** GRAPH_ONLY 渲染主逻辑：先尝试邻接/大纲精准渲染，否则全结构摘要（大文档 LLM 压缩）。 */
    private String renderGraphOnly(Long documentId, String question, ChatTaskInfo taskInfo) {
        // 邻接查询：按问题里的章节线索定位后渲染兄弟
        if (question != null && answerRenderer.asksAdjacency(question)) {
            GraphSection best = locateSectionByQuestion(documentId, question);
            if (best != null) {
                var siblings = graphQueryEngine.findSectionWithSiblings(documentId, best.getNodeId());
                if (siblings != null) {
                    return answerRenderer.renderAdjacency(siblings);
                }
            }
        }
        // 大纲查询：渲染目标章节的子章节
        if (question != null && answerRenderer.asksChildren(question)) {
            GraphSection best = locateSectionByQuestion(documentId, question);
            Long sectionId = best != null ? best.getNodeId() : null;
            var withChildren = sectionId != null
                    ? graphQueryEngine.findSectionWithChildren(documentId, sectionId)
                    : null;
            if (withChildren != null) {
                return answerRenderer.renderChildren(withChildren);
            }
        }
        // 默认：全结构摘要
        List<GraphSection> sections = graphQueryEngine.safeListSections(documentId);
        return renderStructureSummary(sections, documentId, question, taskInfo);
    }

    /** 在文档章节中按问题关键词粗略定位最佳章节 */
    private GraphSection locateSectionByQuestion(Long documentId, String question) {
        if (question == null || question.isBlank()) {
            return null;
        }
        try {
            return graphQueryEngine.graphService().findBestSection(documentId, question, null);
        } catch (Exception e) {
            log.warn("章节定位失败 → documentId={} err={}", documentId, e.getMessage());
            return null;
        }
    }

    /** 全结构摘要：小文档直接拼接，大文档调用 LLM 压缩 */
    private String renderStructureSummary(List<GraphSection> sections, Long documentId,
                                          String question, ChatTaskInfo taskInfo) {
        if (sections == null || sections.isEmpty()) {
            return "文档（id=" + documentId + "）暂无可用的结构信息。";
        }
        ChatProperties.Navigation nav = properties.getNavigation();
        if (sections.size() > nav.getStructureSummaryNodeThreshold()) {
            String compressed = compressWithLlm(sections, documentId, question, taskInfo);
            if (compressed != null) {
                return compressed;
            }
        }
        int limit = Math.min(sections.size(), nav.getStructureSummaryMaxNodes());
        StringBuilder sb = new StringBuilder(128 * limit);
        sb.append("文档结构如下（共 ").append(sections.size()).append(" 个章节，展示前 ").append(limit).append(" 个）：\n");
        for (int i = 0; i < limit; i++) {
            sb.append(i + 1).append(". ").append(sections.get(i).displayTitle()).append('\n');
        }
        if (sections.size() > limit) {
            sb.append("...（剩余 ").append(sections.size() - limit).append(" 个章节已省略）\n");
        }
        return sb.toString();
    }

    /** 大文档结构摘要 LLM 压缩，失败返回 null 回退拼接 */
    private String compressWithLlm(List<GraphSection> sections, Long documentId,
                                   String question, ChatTaskInfo taskInfo) {
        try {
            StringBuilder outline = new StringBuilder();
            for (GraphSection s : sections) {
                outline.append("- ").append(s.displayTitle()).append('\n');
            }
            String prompt = "以下是文档（共 " + sections.size() + " 个章节）的目录结构，请用简洁的中文归纳其主要内容分组与逻辑结构，控制在 400 字以内：\n"
                    + outline;
            ChatOptions options = ChatOptions.builder().temperature(0.2).build();
            return observedChatModelService.callText(
                    "graph-only-structure-summary", prompt, options,
                    taskInfo.getTraceRecorder() == null ? null : taskInfo.getTraceRecorder().traceSink());
        } catch (Exception e) {
            log.warn("结构摘要 LLM 压缩失败，回退拼接 → documentId={} err={}", documentId, e.getMessage());
            return null;
        }
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
