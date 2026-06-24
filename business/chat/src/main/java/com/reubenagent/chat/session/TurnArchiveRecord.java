package com.reubenagent.chat.session;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Date;

/**
 * 轮次归档记录。完成时用 {@link Builder} 精准设置需更新字段，
 * 其余字段为 null，由 MyBatis-Plus {@code updateById} 跳过。
 *
 * @author reuben
 * @since 2026-06-24
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TurnArchiveRecord {

    private Long id;
    private String conversationId;
    private String userPrompt;
    private String replyContent;
    /** JSON 列统一以 String 透传，由 ChatJsonCodec 在 Service 层序列化 */
    private String reasoningNoteList;
    private String sourceSnapshotList;
    private String followupSuggestionList;
    private String toolTraceList;
    private String debugTraceJson;
    private Integer turnStatus;
    private Integer executionMode;
    private String finishNote;
    private Long firstTokenLatencyMs;
    private Long totalLatencyMs;
    private Date createTime;
    private Date updateTime;
}
