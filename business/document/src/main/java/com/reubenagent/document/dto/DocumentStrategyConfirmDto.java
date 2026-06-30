package com.reubenagent.document.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 策略方案确认请求 DTO —— 支持人工调优后的双流水线提交。
 *
 * <p>当 parentSteps / childSteps 非空且与基础方案签名不一致时，后端会落库新方案版本 +
 * 步骤，使调优真正生效（索引构建按新 planId 加载步骤执行）。</p>
 *
 * @author reuben
 * @since 2026-06-21
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DocumentStrategyConfirmDto {

    @Schema(description = "文档ID", required = true)
    private Long documentId;

    @Schema(description = "策略方案ID（基础方案，兼容旧字段）", required = true)
    private Long planId;

    @Schema(description = "调优基于的基础方案ID（优先于 planId）")
    private Long basePlanId;

    @Schema(description = "调整说明")
    private String adjustNote;

    @Schema(description = "父流水线步骤（调优后顺序）")
    private List<StrategyStepInput> parentSteps;

    @Schema(description = "子流水线步骤（调优后顺序）")
    private List<StrategyStepInput> childSteps;

    @Schema(description = "确认人ID（可选）")
    private Long confirmUserId;

    /**
     * 单个策略步骤入参 —— 前端只传顺序与策略类型，角色由后端按位置推导。
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class StrategyStepInput {

        @Schema(description = "步骤序号（1 起）")
        private Integer stepNo;

        @Schema(description = "策略类型：1=结构化 2=递归 3=语义 4=大模型")
        private Integer strategyType;
    }
}
