package com.reubenagent.document.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 策略步骤 VO —— 供方案查询返回双流水线步骤明细。
 *
 * @author reuben
 * @since 2026-06-30
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "策略步骤明细")
public class DocumentStrategyStepVo {

    @Schema(description = "步骤ID")
    private Long stepId;

    @Schema(description = "步骤序号（1 起，父管道在前子管道在后）")
    private Integer stepNo;

    @Schema(description = "管道类型：PARENT / CHILD")
    private String pipelineType;

    @Schema(description = "策略类型：1=结构化 2=递归 3=语义 4=大模型")
    private Integer strategyType;

    @Schema(description = "策略角色：1=主力 2=优化 3=兜底 4=增强")
    private Integer strategyRole;

    @Schema(description = "步骤来源：1=系统推荐 2=用户新增 3=用户保留")
    private Integer sourceType;

    @Schema(description = "推荐理由")
    private String recommendReason;
}
