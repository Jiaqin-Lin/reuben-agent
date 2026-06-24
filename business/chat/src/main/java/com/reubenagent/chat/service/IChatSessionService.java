package com.reubenagent.chat.service;

import com.reubenagent.chat.dto.ChatSessionCreateDto;
import com.reubenagent.chat.dto.ChatSessionListDto;
import com.reubenagent.chat.dto.ChatRenameDto;
import com.reubenagent.chat.vo.ConversationSessionListVo;
import com.reubenagent.chat.vo.ConversationView;
import com.reubenagent.common.dto.PageVo;

/**
 * 会话业务编排层 —— 会话/轮次的创建、查询、删除、重命名。
 *
 * @author reuben
 * @since 2026-06-24
 */
public interface IChatSessionService {

    /** 创建会话。 */
    ConversationView createConversation(ChatSessionCreateDto dto);

    /** 会话详情。 */
    ConversationView getConversationDetail(String conversationId);

    /** 分页列表。 */
    PageVo<ConversationSessionListVo> listConversations(ChatSessionListDto dto);

    /** 软删会话（连带轮次）。 */
    void deleteConversation(String conversationId);

    /**
     * 重命名会话 —— title 非空时直接改名，为空时 LLM 自动生成标题。
     */
    String renameConversation(ChatRenameDto dto);

    /**
     * 基于首条提问自动生成会话标题（建会话时若提问已知可触发）。
     *
     * @return 生成失败时返回 null
     */
    String generateTitle(String firstQuestion);
}
