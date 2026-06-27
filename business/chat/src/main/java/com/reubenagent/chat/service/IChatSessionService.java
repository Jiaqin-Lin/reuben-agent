package com.reubenagent.chat.service;

import com.reubenagent.chat.dto.ChatSessionCreateDto;
import com.reubenagent.chat.dto.ChatSessionListDto;
import com.reubenagent.chat.dto.ChatRenameDto;
import com.reubenagent.chat.vo.ChannelExecutionView;
import com.reubenagent.chat.vo.ChatResetVo;
import com.reubenagent.chat.vo.ChatTurnDetailView;
import com.reubenagent.chat.vo.ConversationSessionListVo;
import com.reubenagent.chat.vo.ConversationView;
import com.reubenagent.chat.vo.RetrievalResultView;
import com.reubenagent.chat.vo.StageBenchmarkView;
import com.reubenagent.common.dto.PageVo;

import java.util.List;

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

    /**
     * 重置会话 —— 删除全部轮次 + 清理 ReAct 检查点 + 删除长期摘要，会话保留并置回 IDLE。
     *
     * @return 重置结果（含删除计数）
     */
    ChatResetVo resetConversation(String conversationId);

    /**
     * 同步重建会话长期摘要 —— 把全部历史轮次重新压缩。
     */
    void rebuildSummary(String conversationId);

    /** 轮次详情（含全链路 stage 追踪）。 */
    ChatTurnDetailView getTurnDetail(String conversationId, Long turnId);

    /** 某轮的检索结果观测列表。 */
    List<RetrievalResultView> getRetrievalResults(String conversationId, Long turnId);

    /** 某轮的通道执行观测列表。 */
    List<ChannelExecutionView> getChannelExecutions(String conversationId, Long turnId);

    /** stage 基准列表（executionMode 可空取全部）。 */
    List<StageBenchmarkView> getStageBenchmarks(Integer executionMode);
}
