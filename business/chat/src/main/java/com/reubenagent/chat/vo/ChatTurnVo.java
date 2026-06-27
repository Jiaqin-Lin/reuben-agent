package com.reubenagent.chat.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Date;

/**
 * 对话轮次视图。
 *
 * @author reuben
 * @since 2026-06-24
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "对话轮次")
public class ChatTurnVo {

    @Schema(description = "轮次ID")
    private Long turnId;

    @Schema(description = "会话ID")
    private String conversationId;

    @Schema(description = "用户提问")
    private String userPrompt;

    @Schema(description = "回答内容")
    private String replyContent;

    @Schema(description = "引用快照JSON")
    private String sourceSnapshotList;

    @Schema(description = "追问建议JSON")
    private String followupSuggestionList;

    @Schema(description = "工具调用追踪JSON")
    private String toolTraceList;

    @Schema(description = "调试追踪JSON")
    private String debugTraceJson;

    @Schema(description = "收尾说明")
    private String finishNote;

    @Schema(description = "轮次状态：1=执行中 2=完成 3=失败 4=停止")
    private Integer turnStatus;

    @Schema(description = "执行模式")
    private Integer executionMode;

    @Schema(description = "首字延迟毫秒")
    private Long firstTokenLatencyMs;

    @Schema(description = "总耗时毫秒")
    private Long totalLatencyMs;

    @Schema(description = "创建时间")
    private Date createTime;
}
