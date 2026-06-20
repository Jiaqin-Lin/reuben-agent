package com.reubenagent.document.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.reubenagent.common.data.BaseTableData;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

/**
 * 文档策略步骤 —— 方案中的单个分块步骤，归属父管道或子管道。
 *
 * @author reuben
 * @since 2026-06-20
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("reuben_agent_document_strategy_step")
@EqualsAndHashCode(callSuper = true)
public class DocumentStrategyStep extends BaseTableData {

    /** 主键 */
    @TableId(type = IdType.INPUT)
    private Long id;

    /** 所属方案ID */
    private Long planId;

    /** 所属文档ID（冗余，便于按文档查询） */
    private Long documentId;

    /** 步骤序号（从 1 开始） */
    private Integer stepNo;

    /** 管道类型：PARENT / CHILD */
    private String pipelineType;

    /** 策略类型：1=结构化 2=递归 3=语义 4=大模型 */
    private Integer strategyType;

    /** 策略角色：1=主力 2=优化 3=兜底 4=增强 */
    private Integer strategyRole;

    /** 步骤来源：1=系统推荐 2=用户新增 3=用户保留 */
    private Integer sourceType;

    /** 执行状态：1=待执行 2=执行中 3=成功 4=失败 5=跳过 */
    private Integer executeStatus;

    /** 推荐理由 */
    private String recommendReason;
}
