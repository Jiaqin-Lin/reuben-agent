package com.reubenagent.document.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 策略方案 VO —— 供查询接口使用。
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
}
