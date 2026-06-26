package com.reubenagent.chat.orchestrate;

import com.reubenagent.chat.enums.ChatErrorCode;
import com.reubenagent.chat.enums.ChatTraceStageCode;
import com.reubenagent.chat.enums.ExecutionMode;
import com.reubenagent.chat.exception.ChatException;
import com.reubenagent.chat.model.SearchReference;
import com.reubenagent.chat.model.orchestrate.ChatRetrievalResult;
import com.reubenagent.chat.model.orchestrate.ConversationExecutionPlan;
import com.reubenagent.chat.service.ObservedChatModelService;
import com.reubenagent.chat.support.ChatStreamEventWriter;
import com.reubenagent.chat.support.SinkEmitHelper;
import com.reubenagent.chat.trace.ChatTraceRecorder;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * RAG 回答执行器 —— 检索证据 → 组装 prompt → 流式生成带引用的回答。
 *
 * <p>对标 super-agent {@code RagChatExecutor}，reuben 修正与简化：
 * <ul>
 *   <li>检索委托 {@link ChatRagRetrievalAdapter}，不重造引擎（修正 super-agent 重造
 *       {@code RagRetrievalEngine} 的问题）；</li>
 *   <li>Prompt 组装委托 {@link ChatRagPromptAssemblyService}，executor 只串接流程；</li>
 *   <li>无证据命中：emit 模板拒答文案 + 落 trace evidence_budget=0，仍正常完成 Flux；</li>
 *   <li>失败：emit error 事件 + 抛 {@link ChatException}({@link ChatErrorCode#RETRIEVE_FAILED})，
 *       由 orchestrator finalize 收尾；</li>
 *   <li>引用去重：跨子问题同 chunk 只保留首次出现的 {@link SearchReference}。</li>
 * </ul></p>
 *
 * <p><b>Phase 8 修正</b>：
 * <ul>
 *   <li>检索委托 {@code Mono.fromCallable + subscribeOn(boundedElastic)}，避免阻塞 reactor 线程；</li>
 *   <li>组装 + 流式段抽成 {@link #assembleAndStream}，供 {@link GraphThenEvidenceExecutor} 复用；</li>
 *   <li>model-usage trace 通过 {@code recorder.traceSink()} 传给 streamText。</li>
 * </ul></p>
 *
 * @author reuben
 * @since 2026-06-25
 */
@Slf4j
@Component
@AllArgsConstructor
public class RagAnswerExecutor implements ConversationExecutor {

    private static final String NO_EVIDENCE_REPLY =
            "未能在所选文档中找到与问题相关的资料，请补充更多信息或换一种问法。";

    private final ChatRagRetrievalAdapter retrievalAdapter;
    private final ChatRagPromptAssemblyService promptAssemblyService;
    private final ObservedChatModelService observedChatModelService;
    private final ChatStreamEventWriter eventWriter;

    @Override
    public ExecutionMode mode() {
        return ExecutionMode.RETRIEVAL;
    }

    @Override
    public Flux<String> execute(ChatTaskInfo taskInfo) {
        return Flux.defer(() -> {
            ConversationExecutionPlan plan = taskInfo.getExecutionPlan();
            if (plan == null) {
                return Flux.<String>error(new ChatException(ChatErrorCode.PARAM_INVALID,
                        "RagAnswerExecutor 缺少 executionPlan"));
            }

            // 阶段 1：检索证据（boundedElastic）
            emitThinking(taskInfo, "正在检索相关资料。");
            return Mono.fromCallable(() -> retrievalAdapter.retrieve(plan, taskInfo))
                    .subscribeOn(Schedulers.boundedElastic())
                    .onErrorMap(ChatException.class, e -> e)
                    .onErrorMap(Exception.class,
                            e -> new ChatException(ChatErrorCode.RETRIEVE_FAILED, e.getMessage(), e))
                    .flatMapMany(results -> afterRetrieval(taskInfo, results))
                    .onErrorResume(error -> {
                        ChatException wrapped = error instanceof ChatException
                                ? (ChatException) error
                                : new ChatException(ChatErrorCode.GENERATION_FAILED, error.getMessage(), error);
                        emitError(taskInfo, wrapped.getMessage());
                        return Flux.error(wrapped);
                    });
        });
    }

    /** 检索后流程：去重 → reference 事件 → 无证据拒答 → 组装 + 流式生成。 */
    Flux<String> afterRetrieval(ChatTaskInfo taskInfo, List<ChatRetrievalResult> retrievalResults) {
        // 阶段 2：去重证据 + emit reference 事件
        List<SearchReference> references = dedupReferences(retrievalResults);
        if (!references.isEmpty()) {
            SinkEmitHelper.emitNext(taskInfo.getSink(),
                    eventWriter.references(references, taskInfo.getConversationId(), taskInfo.getTurnId()));
            taskInfo.getReferences().addAll(references);
        }

        // 阶段 3：无证据 → 拒答
        if (references.isEmpty()) {
            recordEvidenceBudget(taskInfo, 0, 0);
            return Flux.just(NO_EVIDENCE_REPLY);
        }
        return assembleAndStream(taskInfo, retrievalResults);
    }

    /** 组装 prompt + 流式生成（共享给 GraphThenEvidenceExecutor）。 */
    Flux<String> assembleAndStream(ChatTaskInfo taskInfo, List<ChatRetrievalResult> retrievalResults) {
        ConversationExecutionPlan plan = taskInfo.getExecutionPlan();
        ChatRagPromptAssemblyService.AssemblyResult assembly = promptAssemblyService.assemble(
                plan, retrievalResults, taskInfo.getCurrentDateText());
        recordEvidenceBudget(taskInfo,
                assembly.getRenderedReferenceCount(), assembly.getOmittedReferenceCount());

        ChatOptions options = buildChatOptions();
        ChatTraceRecorder recorder = taskInfo.getTraceRecorder();
        return observedChatModelService.streamText("RAG_ANSWER", assembly.getUserPrompt(), options,
                        recorder == null ? null : recorder.traceSink())
                .onErrorResume(error -> {
                    ChatException wrapped = error instanceof ChatException
                            ? (ChatException) error
                            : new ChatException(ChatErrorCode.GENERATION_FAILED, error.getMessage(), error);
                    emitError(taskInfo, wrapped.getMessage());
                    return Flux.error(wrapped);
                });
    }

    private List<SearchReference> dedupReferences(List<ChatRetrievalResult> groups) {
        Map<String, SearchReference> unique = new LinkedHashMap<>();
        if (groups == null) {
            return new ArrayList<>();
        }
        for (ChatRetrievalResult group : groups) {
            if (group == null || group.getReferences() == null) {
                continue;
            }
            for (SearchReference ref : group.getReferences()) {
                if (ref == null) {
                    continue;
                }
                unique.putIfAbsent(ref.uniqueKey(), ref);
            }
        }
        return new ArrayList<>(unique.values());
    }

    private ChatOptions buildChatOptions() {
        return ChatOptions.builder()
                .temperature(0.3)
                .build();
    }

    private void recordEvidenceBudget(ChatTaskInfo taskInfo, int rendered, int omitted) {
        ChatTraceRecorder recorder = taskInfo == null ? null : taskInfo.getTraceRecorder();
        if (recorder == null) {
            return;
        }
        ChatTraceRecorder.StageHandle stage = recorder.startStage(
                ChatTraceStageCode.EVIDENCE_BUDGET, "EVIDENCE", "证据门控", null);
        Map<String, Object> snapshot = new java.util.HashMap<>();
        snapshot.put("rendered", rendered);
        snapshot.put("omitted", omitted);
        recorder.completeStage(stage, "证据门控完成", snapshot);
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

