package com.reubenagent.document.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 策略方案 VO —— 供查询接口使用，含双流水线步骤明细。
 *
 * @author reuben
 * @since 2026-06-21
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DocumentStrategyPlanVo {

    private Long planId;
    private Long documentId;
    private Integer planVersion;
    private Integer planSource;
    private Integer planStatus;
    private Integer strategyCount;
    private String strategySnapshot;
    private String recommendReason;

    /** 调整说明（用户调优时填写） */
    private String adjustNote;

    /** 双流水线步骤明细（按 stepNo 升序，前端按 pipelineType 分组） */
    private List<DocumentStrategyStepVo> steps;
}
