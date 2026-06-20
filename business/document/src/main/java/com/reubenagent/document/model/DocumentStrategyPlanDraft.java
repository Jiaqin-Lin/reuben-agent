package com.reubenagent.document.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 策略方案草稿 —— {@code DocumentStrategyServiceImpl.recommendStrategy()} 的返回值。
 *
 * @author reuben
 * @since 2026-06-20
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DocumentStrategyPlanDraft {

    /** 策略组合快照（如 PARENT:1;CHILD:3,2） */
    private String strategySnapshot;

    /** 推荐理由（分号分隔的中文描述） */
    private String recommendReason;

    /** 父管道步骤 */
    private List<DocumentStrategyStepDraft> parentSteps;

    /** 子管道步骤 */
    private List<DocumentStrategyStepDraft> childSteps;
}
