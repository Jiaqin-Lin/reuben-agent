package com.reubenagent.chat.session;

import com.reubenagent.chat.enums.ChatMode;
import com.reubenagent.chat.enums.ChatTurnStatus;

import java.util.List;

/**
 * 对话会话/轮次持久化仓储 —— 对标 super-agent ConversationArchiveStore。
 *
 * <p>仅承担纯持久化职责：会话 upsert、轮次完成、列表分页、近 N 轮查询、软删。
 * 业务编排（标题生成、状态机）由 {@code ChatSessionService} 负责。</p>
 *
 * @author reuben
 * @since 2026-06-24
 */
public interface ChatArchiveStore {

    /** 落库或更新会话（按 conversation_id 唯一约束 upsert）。 */
    ConversationArchiveRecord saveConversation(ConversationArchiveRecord record);

    /** 按 conversation_id 查询会话，不存在返回 null。 */
    ConversationArchiveRecord getConversation(String conversationId);

    /** 分页查询会话列表。 */
    ConversationArchivePage listConversations(ConversationListQuery query);

    /** 软删会话（连带关联轮次）。 */
    ConversationRemovalResult deleteConversation(String conversationId);

    /** 创建一轮（RUNNING），返回 turnId。 */
    Long startTurn(TurnArchiveRecord record);

    /** 完成一轮：只更非 null 字段（builder 精准设置）。 */
    void completeTurn(String conversationId, Long turnId, TurnArchiveRecord patch);

    /** 查询会话最近 N 轮（按 id 倒序）。 */
    List<TurnArchiveRecord> listRecentTurns(String conversationId, int limit);

    /** 统计会话轮次数。 */
    long countTurns(String conversationId);

    /**
     * 会话列表查询条件。
     *
     * @param pageNo    页码（1 起）
     * @param pageSize  每页条数
     * @param keyword   标题模糊匹配（可空）
     * @param chatMode  对话模式（可空）
     * @param turnStatus 最近轮次状态过滤（可空）
     */
    record ConversationListQuery(int pageNo, int pageSize, String keyword, ChatMode chatMode,
                                 ChatTurnStatus turnStatus) {
    }
}
