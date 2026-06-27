package com.reubenagent.chat.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 会话重置结果 —— 删除轮次 + 清理检查点 + 清理长期摘要的计数。
 *
 * @author reuben
 * @since 2026-06-27
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "会话重置结果")
public class ChatResetVo {

    @Schema(description = "会话ID")
    private String conversationId;

    @Schema(description = "删除的轮次数")
    private int removedTurnCount;

    @Schema(description = "清理的检查点数")
    private int removedCheckpointCount;

    @Schema(description = "是否清理了长期摘要")
    private boolean summaryCleared;

    @Schema(description = "说明")
    private String message;
}
