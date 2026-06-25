package com.reubenagent.chat.model.memory;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * 单轮对话加载到的完整记忆上下文 —— 供改写 / Agent / RAG 组装 prompt 使用。
 *
 * <p>由 {@code recent window}（近 {@code keepRecentTurns} 轮 transcript）与
 * {@code long-term summary}（已压缩摘要）两部分构成。</p>
 *
 * @author reuben
 * @since 2026-06-25
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChatMemoryContext {

    /** 原始会话ID */
    private String conversationId;

    /** 组装好的历史 transcript（含 question/answer），用于注入 prompt */
    private String assembledHistory;

    /** 长期摘要文本（summary_payload.summary），无摘要时为空 */
    private String longTermSummary;

    /** 近期 transcript（仅 recent window，裁剪到 recentTranscriptMaxChars） */
    private String recentTranscript;

    /**
     * 仅含答案的近期 transcript —— RAG 回答时复用，省 token。
     */
    private String answerRecentTranscript;

    /** 结构化摘要负载（含 stable_facts / pending_questions 等），无摘要时为 null */
    private ChatSummaryPayload summaryPayload;

    /** 已被摘要覆盖的轮次数 */
    private Integer coverage;

    /** 已发生的压缩次数 */
    private Integer compressionCount;

    /** 是否触发了异步摘要刷新 */
    @Builder.Default
    private boolean summaryRefreshTriggered = false;

    /** 参与近期窗口的轮次快照（用于后续刷新判断覆盖范围） */
    @Builder.Default
    private List<RecentTurnSnapshot> recentTurns = new ArrayList<>();

    /**
     * 近期窗口单轮快照。
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RecentTurnSnapshot {
        private Long turnId;
        private String userPrompt;
        private String replyContent;
        private Integer turnStatus;
    }
}
