package com.reubenagent.chat.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.Date;

/**
 * 通道执行观测视图。
 *
 * @author reuben
 * @since 2026-06-27
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "通道执行观测")
public class ChannelExecutionView {

    @Schema(description = "记录ID")
    private Long id;

    @Schema(description = "会话ID")
    private String conversationId;

    @Schema(description = "轮次ID")
    private Long turnId;

    @Schema(description = "子问题序号")
    private Integer subQuestionIndex;

    @Schema(description = "通道类型")
    private String channelType;

    @Schema(description = "执行状态")
    private String executionState;

    @Schema(description = "开始时间")
    private Date startTime;

    @Schema(description = "结束时间")
    private Date endTime;

    @Schema(description = "耗时毫秒")
    private Long durationMs;

    @Schema(description = "召回数")
    private Integer recalledCount;

    @Schema(description = "接受数")
    private Integer acceptedCount;

    @Schema(description = "最终选中数")
    private Integer finalSelectedCount;

    @Schema(description = "最高分")
    private BigDecimal maxScore;

    @Schema(description = "最低分")
    private BigDecimal minScore;
}
