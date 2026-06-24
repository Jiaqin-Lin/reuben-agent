package com.reubenagent.chat.model;

import java.util.List;

/**
 * SSE 流式事件统一载荷 —— 前后端约定的流式通信协议。
 *
 * <p>类型（{@link ChatStreamEventType}）：</p>
 * <ul>
 *   <li>{@code text} LLM 文本块（打字机效果）</li>
 *   <li>{@code thinking} 系统状态提示（如"正在检索…"）</li>
 *   <li>{@code status} 执行状态变更</li>
 *   <li>{@code error} 错误信息</li>
 *   <li>{@code reference} 引文来源列表</li>
 *   <li>{@code recommend} 推荐追问列表</li>
 *   <li>{@code done} 流结束标记</li>
 * </ul>
 *
 * <p>序列化由 {@link com.reubenagent.chat.support.ChatStreamEventWriter} 用 FastJSON 完成；
 * 序列化失败降级为 status error 事件，不抛异常中断整个会话。</p>
 *
 * @author reuben
 * @since 2026-06-24
 */
public record ChatStreamEvent(
        ChatStreamEventType type,
        Object content,
        String conversationId,
        Long turnId,
        long timestamp,
        Integer count
) {

    /** 构造不带 count 的事件。 */
    public static ChatStreamEvent of(ChatStreamEventType type, Object content,
                                     String conversationId, Long turnId) {
        return new ChatStreamEvent(type, content, conversationId, turnId, System.currentTimeMillis(), null);
    }

    /** 引用/追问事件带 count。 */
    public static ChatStreamEvent ofCounted(ChatStreamEventType type, List<?> items,
                                            String conversationId, Long turnId) {
        return new ChatStreamEvent(type, items, conversationId, turnId,
                System.currentTimeMillis(), items == null ? 0 : items.size());
    }

    /** done 事件。 */
    public static ChatStreamEvent done(String conversationId, Long turnId) {
        return new ChatStreamEvent(ChatStreamEventType.DONE, "", conversationId, turnId,
                System.currentTimeMillis(), null);
    }
}
