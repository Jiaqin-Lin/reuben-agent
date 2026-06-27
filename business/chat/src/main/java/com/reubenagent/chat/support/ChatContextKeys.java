package com.reubenagent.chat.support;

/**
 * 一次对话请求贯穿 orchestrator / executor / trace 的上下文键常量。
 *
 * <p>Phase 2 阶段用于在 {@link com.reubenagent.chat.orchestrate.ChatTaskInfo} 之外
 * 传递少量执行态（如 sink 引用、原始问题）。Phase 7 接入 Alibaba ReactAgent 后，
 * 同样的键会放进 {@code RunnableConfig.context()} 供工具拦截器读取。</p>
 *
 * @author reuben
 * @since 2026-06-24
 */
public final class ChatContextKeys {

    public static final String QUESTION = "chat.question";
    public static final String REFERENCES = "chat.references";
    public static final String USED_TOOLS = "chat.used.tools";
    public static final String THINKING_STEPS = "chat.thinking.steps";
    public static final String TRACE_ID = "chat.trace.id";
    public static final String STREAM_SINK = "chat.stream.sink";
    public static final String TASK_INFO = "chat.task.info";
    public static final String CHAT_MODE = "chat.mode";
    public static final String CURRENT_DATE = "chat.current.date";
    public static final String CURRENT_DATE_TEXT = "chat.current.date.text";
    public static final String SELECTED_DOCUMENT_ID = "chat.selected.document.id";
    public static final String SELECTED_DOCUMENT_NAME = "chat.selected.document.name";

    /** Tavily 工具用：当前 conversationId（注入到 ToolContext） */
    public static final String CONVERSATION_ID = "chat.conversation.id";
    /** Tavily 工具用：当前 turnId（注入到 ToolContext） */
    public static final String TURN_ID = "chat.turn.id";

    /** ReAct Agent model-usage 追踪 sink：Consumer<ChatModelUsageTrace>，由 ModelUsageTraceInterceptor 写入 */
    public static final String MODEL_USAGE_TRACE_SINK = "chat.model.usage.trace.sink";

    private ChatContextKeys() {
    }
}
