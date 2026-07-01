package com.reubenagent.chat.service;

import com.reubenagent.chat.model.memory.ChatMemoryContext;
import com.reubenagent.chat.model.memory.ChatSummaryPayload;
import com.reubenagent.chat.trace.ChatTraceRecorder;

/**
 * 对话记忆服务 —— 短期 recent window + 长期摘要压缩记忆。
 *
 * <p>对标 super-agent {@code PersistentConversationMemoryService}。</p>
 *
 * @author reuben
 * @since 2026-06-25
 */
public interface IChatMemoryService {

    /**
     * 加载一轮对话所需的完整记忆上下文。
     *
     * <p>同步加载 recent window + 已有长期摘要；当稳定轮次超过 {@code keepRecentTurns} 时，
     * 触发异步摘要刷新（不阻塞当前轮）。</p>
     *
     * @param conversationId 会话ID
     * @param traceRecorder  追踪记录器（可空，落 MEMORY stage）
     */
    ChatMemoryContext loadMemoryContext(String conversationId, ChatTraceRecorder traceRecorder);

    /** 取会话长期摘要负载，无摘要返回 null。 */
    ChatSummaryPayload getConversationSummary(String conversationId);

    /**
     * 同步重建会话长期摘要 —— 把会话全部历史轮次重新压缩。
     *
     * <p>用于会话控制接口（重置摘要后重建）。</p>
     */
    ChatSummaryPayload rebuildConversationSummary(String conversationId);

    /** 删除会话长期摘要（重置会话时调用）。 */
    void deleteConversationSummary(String conversationId);

    /** 异步刷新摘要（若当前无在途刷新）。 */
    void refreshConversationSummaryAsync(String conversationId);

    /**
     * 取会话长期摘要实体（含覆盖率/版本/压缩次数等元数据），无摘要返回 null。
     *
     * <p>观测页展示记忆压缩状态用，默认实现返回 null，由记忆服务实现覆写。</p>
     *
     * @param conversationId 会话ID
     */
    default com.reubenagent.chat.entity.ChatMemorySummary getSummaryEntity(String conversationId) {
        return null;
    }
}
