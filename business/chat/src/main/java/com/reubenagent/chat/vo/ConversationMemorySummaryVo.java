package com.reubenagent.chat.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 会话长期摘要快照 —— 供观测页展示记忆压缩状态。
 *
 * @author reuben
 * @since 2026-07-01
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "会话长期摘要快照")
public class ConversationMemorySummaryVo {

    @Schema(description = "是否已形成长期摘要")
    private Boolean compressionApplied;

    @Schema(description = "已覆盖轮次数")
    private Integer coveredExchangeCount;

    @Schema(description = "摘要版本")
    private Integer summaryVersion;

    @Schema(description = "累计压缩次数")
    private Integer compressionCount;

    @Schema(description = "摘要文本")
    private String summaryText;
}
