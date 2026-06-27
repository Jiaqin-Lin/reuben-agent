package com.reubenagent.chat.orchestrate;

import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.exception.GraphRunnerException;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.streaming.OutputType;
import com.alibaba.cloud.ai.graph.streaming.StreamingOutput;
import com.alibaba.cloud.ai.graph.NodeOutput;
import com.reubenagent.chat.agent.TimeSensitiveQueryHelper;
import com.reubenagent.chat.enums.ChatErrorCode;
import com.reubenagent.chat.enums.ChatTraceStageCode;
import com.reubenagent.chat.enums.ExecutionMode;
import com.reubenagent.chat.exception.ChatException;
import com.reubenagent.chat.model.orchestrate.ConversationExecutionPlan;
import com.reubenagent.chat.support.ChatContextKeys;
import com.reubenagent.chat.support.ChatPromptNames;
import com.reubenagent.chat.support.ChatPromptTemplateService;
import com.reubenagent.chat.support.ChatStreamEventWriter;
import com.reubenagent.chat.support.ChatTexts;
import com.reubenagent.chat.support.SinkEmitHelper;
import com.reubenagent.chat.trace.ChatTraceRecorder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * ReAct Agent 执行器 —— 开放式对话（OPEN_CHAT）走 Agent loop。
 *
 * <p>对标 super-agent {@code ReactAgentExecutor}，reuben 修正与简化：
 * <ul>
 *   <li>用 Alibaba {@link ReactAgent}（已装配 Bean）驱动 loop，不自实现 ReAct；</li>
 *   <li>agent question 由 {@code agent-question.st} 渲染，注入 currentDateText / 时间敏感提示 /
 *       历史摘要块 / 原始问题，渲染失败抛 {@link ChatException}(PROMPT_LOAD_FAILED)；</li>
 *   <li><b>关键：上下文透传</b>。Tavily 工具 / fallback 拦截器需要从 {@code ToolContext} 读
 *       sink / conversationId / turnId / toolTraces / usedTools / question，而
 *       {@code AgentToolNode} 用 {@code config.metadata()} 作为
 *       {@code ToolCallRequest.context} 来源。因此本执行器在 stream() 调用前把这些 key
 *       写入 {@link RunnableConfig} 的 metadata（不可变快照，运行期只读）；</li>
 *   <li>stream 输出按 {@link OutputType#AGENT_MODEL_STREAMING} 过滤文本 chunk（AssistantMessage
 *       无 toolCalls 时取 {@code getText()}），其余 NodeOutput 静默；thinking 事件由
 *       Tavily 工具自身 emit，本执行器不再叠加；</li>
 *   <li>{@link GraphRunnerException} → {@link ChatException}({@link ChatErrorCode#MODEL_CALL_FAILED})，
 *       由 orchestrator finalize 收尾；</li>
 *   <li>threadId 用 conversationId，保证 ReAct 检查点按会话维度隔离。</li>
 * </ul></p>
 *
 * <p><b>publishOn</b>：orchestrator 在 {@code buildExecution} 已统一 {@code publishOn(boundedElastic)}，
 * 本执行器不再重复切换线程，避免重复调度开销。</p>
 *
 * @author reuben
 * @since 2026-06-25
 */
@Slf4j
@Component
public class ReactAgentExecutor implements ConversationExecutor {

    private static final String TIME_SENSITIVE_HINT =
            "注意：用户问题可能涉及实时信息，请按需调用 tavily_search 工具联网核实，"
                    + "不要凭模型记忆给出可能过期的数据。";

    private static final String STAGE_EXECUTION_MODE = "REACT_AGENT";

    private final ReactAgent reactAgent;
    private final ChatPromptTemplateService promptTemplateService;
    private final TimeSensitiveQueryHelper timeSensitiveQueryHelper;
    private final ChatStreamEventWriter eventWriter;

    public ReactAgentExecutor(@Qualifier("reactAgent") ReactAgent reactAgent,
                              ChatPromptTemplateService promptTemplateService,
                              TimeSensitiveQueryHelper timeSensitiveQueryHelper,
                              ChatStreamEventWriter eventWriter) {
        this.reactAgent = reactAgent;
        this.promptTemplateService = promptTemplateService;
        this.timeSensitiveQueryHelper = timeSensitiveQueryHelper;
        this.eventWriter = eventWriter;
    }

    @Override
    public ExecutionMode mode() {
        return ExecutionMode.REACT_AGENT;
    }

    @Override
    public Flux<String> execute(ChatTaskInfo taskInfo) {
        return Flux.defer(() -> {
            ConversationExecutionPlan plan = taskInfo.getExecutionPlan();
            if (plan == null) {
                return Flux.<String>error(new ChatException(ChatErrorCode.PARAM_INVALID,
                        "ReactAgentExecutor 缺少 executionPlan"));
            }

            ChatTraceRecorder.StageHandle stageHandle = startStage(taskInfo);

            String agentQuestion;
            try {
                agentQuestion = assembleAgentQuestion(taskInfo, plan);
            } catch (ChatException e) {
                failStage(taskInfo, stageHandle, e.getMessage());
                return Flux.<String>error(e);
            }

            RunnableConfig runnableConfig = buildRunnableConfig(taskInfo, plan);

            Flux<NodeOutput> agentStream;
            try {
                agentStream = reactAgent.stream(agentQuestion, runnableConfig);
            } catch (GraphRunnerException e) {
                ChatException wrapped = new ChatException(ChatErrorCode.MODEL_CALL_FAILED, e.getMessage(), e);
                failStage(taskInfo, stageHandle, wrapped.getMessage());
                return Flux.<String>error(wrapped);
            }

            // 标记是否已流式输出过文本：FINISHED 的兜底整段答案只在无 STREAMING 时 emit，避免重复
            java.util.concurrent.atomic.AtomicBoolean streamedText = new java.util.concurrent.atomic.AtomicBoolean(false);

            return agentStream
                    .ofType(StreamingOutput.class)
                    .cast(StreamingOutput.class)
                    .filter(so -> so.getOutputType() == OutputType.AGENT_MODEL_STREAMING
                            || so.getOutputType() == OutputType.AGENT_MODEL_FINISHED)
                    // 用 concatMap 跳过不需要的输出（Reactor 的 map 不允许返回 null，否则报错）
                    .concatMap(so -> {
                        org.springframework.ai.chat.messages.AssistantMessage msg =
                                so.message() instanceof AssistantMessage
                                        ? (AssistantMessage) so.message() : null;
                        if (msg == null || msg.hasToolCalls()
                                || msg.getText() == null || msg.getText().isBlank()) {
                            return Flux.<String>empty();
                        }
                        // FINISHED 的兜底整段答案只在无 STREAMING 时 emit，避免重复
                        if (so.getOutputType() == OutputType.AGENT_MODEL_FINISHED && streamedText.get()) {
                            return Flux.<String>empty();
                        }
                        streamedText.set(true);
                        return Flux.just(msg.getText());
                    })
                    .onErrorResume(error -> {
                        ChatException wrapped = error instanceof ChatException
                                ? (ChatException) error
                                : new ChatException(ChatErrorCode.MODEL_CALL_FAILED, error.getMessage(), error);
                        failStage(taskInfo, stageHandle, wrapped.getMessage());
                        emitError(taskInfo, wrapped.getMessage());
                        return Flux.error(wrapped);
                    })
                    .doOnComplete(() -> completeStage(taskInfo, stageHandle));
        });
    }

    // ======================== agent question 组装 ========================

    private String assembleAgentQuestion(ChatTaskInfo taskInfo, ConversationExecutionPlan plan) {
        String question = pickQuestion(plan, taskInfo.getQuestion());
        boolean timeSensitive = timeSensitiveQueryHelper.isTimeSensitive(question, taskInfo.getCurrentDate());
        String freshSearchHint = timeSensitive ? TIME_SENSITIVE_HINT : "";
        if (timeSensitive) {
            log.info("时间敏感查询触发联网 hint 注入 → conversationId={} question={}",
                    taskInfo.getConversationId(), ChatTexts.clip(question, 80));
        }
        String historySummaryBlock = formatHistoryBlock(plan);

        Map<String, String> vars = new LinkedHashMap<>();
        vars.put("currentDateText", taskInfo.getCurrentDateText());
        vars.put("freshSearchHint", freshSearchHint);
        vars.put("historySummaryBlock", historySummaryBlock);
        vars.put("question", question);
        return promptTemplateService.render(ChatPromptNames.AGENT_QUESTION, vars);
    }

    /** 优先用改写后的主问题；缺失时回退原始问题，保证 agent 拿到非空输入。 */
    private String pickQuestion(ConversationExecutionPlan plan, String originalQuestion) {
        String rewritten = plan.getRewrittenQuery();
        if (rewritten != null && !rewritten.isBlank()) {
            return rewritten;
        }
        if (plan.getOriginalQuestion() != null && !plan.getOriginalQuestion().isBlank()) {
            return plan.getOriginalQuestion();
        }
        return originalQuestion == null ? "" : originalQuestion;
    }

    private String formatHistoryBlock(ConversationExecutionPlan plan) {
        String summary = plan.getLongTermSummary();
        String transcript = plan.getRecentTranscript();
        boolean hasSummary = summary != null && !summary.isBlank();
        boolean hasTranscript = transcript != null && !transcript.isBlank();
        if (!hasSummary && !hasTranscript) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        if (hasSummary) {
            sb.append("【历史摘要】\n").append(summary.trim()).append("\n\n");
        }
        if (hasTranscript) {
            sb.append("【近期对话】\n").append(transcript.trim()).append("\n\n");
        }
        return sb.toString();
    }

    // ======================== RunnableConfig 装配（metadata 透传 ToolContext）========================

    private RunnableConfig buildRunnableConfig(ChatTaskInfo taskInfo, ConversationExecutionPlan plan) {
        Map<String, Object> meta = new HashMap<>();
        meta.put(ChatContextKeys.STREAM_SINK, taskInfo.getSink());
        meta.put(ChatContextKeys.CONVERSATION_ID, taskInfo.getConversationId());
        meta.put(ChatContextKeys.TURN_ID, taskInfo.getTurnId());
        meta.put(ChatContextKeys.USED_TOOLS, taskInfo.getUsedTools());
        meta.put(ChatContextKeys.THINKING_STEPS, taskInfo.getThinkingSteps());
        meta.put(ChatContextKeys.QUESTION, pickQuestion(plan, taskInfo.getQuestion()));
        meta.put(ChatContextKeys.CURRENT_DATE, taskInfo.getCurrentDate());
        meta.put(ChatContextKeys.CURRENT_DATE_TEXT, taskInfo.getCurrentDateText());
        // 阶段：注入 per-turn model-usage trace sink，供 ModelUsageTraceInterceptor 落 token/成本
        ChatTraceRecorder recorder = taskInfo.getTraceRecorder();
        if (recorder != null) {
            meta.put(ChatContextKeys.MODEL_USAGE_TRACE_SINK, recorder.traceSink());
        }

        RunnableConfig.Builder builder = RunnableConfig.builder()
                .threadId(taskInfo.getConversationId());
        meta.forEach(builder::addMetadata);
        return builder.build();
    }

    // ======================== trace / SSE 副作用 ========================

    private ChatTraceRecorder.StageHandle startStage(ChatTaskInfo taskInfo) {
        ChatTraceRecorder recorder = taskInfo.getTraceRecorder();
        if (recorder == null) {
            return ChatTraceRecorder.StageHandle.noop(ChatTraceStageCode.REACT_AGENT);
        }
        return recorder.startStage(ChatTraceStageCode.REACT_AGENT, STAGE_EXECUTION_MODE,
                "ReAct Agent 开放式对话", null);
    }

    private void completeStage(ChatTaskInfo taskInfo, ChatTraceRecorder.StageHandle handle) {
        ChatTraceRecorder recorder = taskInfo.getTraceRecorder();
        if (recorder == null) {
            return;
        }
        recorder.completeStage(handle, "ReAct Agent 完成", null);
    }

    private void failStage(ChatTaskInfo taskInfo, ChatTraceRecorder.StageHandle handle, String errorMessage) {
        ChatTraceRecorder recorder = taskInfo.getTraceRecorder();
        if (recorder == null) {
            return;
        }
        recorder.failStage(handle, "ReAct Agent 失败", errorMessage, null);
    }

    private void emitError(ChatTaskInfo taskInfo, String message) {
        if (taskInfo == null || taskInfo.getSink() == null) {
            return;
        }
        SinkEmitHelper.emitNext(taskInfo.getSink(),
                eventWriter.error(message, taskInfo.getConversationId(), taskInfo.getTurnId()));
    }
}
