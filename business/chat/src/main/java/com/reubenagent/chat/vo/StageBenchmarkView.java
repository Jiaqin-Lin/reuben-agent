package com.reubenagent.chat.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * stage 基准（P50/P90/P99）观测视图。
 *
 * @author reuben
 * @since 2026-06-27
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "阶段基准")
public class StageBenchmarkView {

    @Schema(description = "阶段编码")
    private Integer stageCode;

    @Schema(description = "执行模式")
    private Integer executionMode;

    @Schema(description = "P50 毫秒")
    private Long p50;

    @Schema(description = "P90 毫秒")
    private Long p90;

    @Schema(description = "P99 毫秒")
    private Long p99;

    @Schema(description = "平均毫秒")
    private Long avg;

    @Schema(description = "最大毫秒")
    private Long max;

    @Schema(description = "最小毫秒")
    private Long min;

    @Schema(description = "样本数")
    private Integer sampleCount;

    @Schema(description = "近期耗时（逗号分隔）")
    private String recentDurations;
}
