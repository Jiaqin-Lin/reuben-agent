package com.reubenagent.document.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 策略步骤草稿 —— 单个分块步骤的推荐信息。
 *
 * @author reuben
 * @since 2026-06-20
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DocumentStrategyStepDraft {

    /** 管道类型：PARENT / CHILD */
    private String pipelineType;

    /** 策略类型：1=结构化 2=递归 3=语义 4=大模型 */
    private Integer strategyType;

    /** 策略角色：1=主力 2=优化 3=兜底 4=增强 */
    private Integer strategyRole;

    /** 步骤来源：1=系统推荐 */
    private Integer sourceType;

    /** 推荐理由 */
    private String recommendReason;
}
