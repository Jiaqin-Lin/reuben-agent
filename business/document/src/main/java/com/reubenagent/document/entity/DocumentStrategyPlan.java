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

import java.util.Date;

/**
 * 文档策略方案 —— 一次解析完成后系统推荐的完整分块计划。
 *
 * @author reuben
 * @since 2026-06-20
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("reuben_agent_document_strategy_plan")
@EqualsAndHashCode(callSuper = true)
public class DocumentStrategyPlan extends BaseTableData {

    /** 主键 */
    @TableId(type = IdType.INPUT)
    private Long id;

    /** 所属文档ID */
    private Long documentId;

    /** 方案版本号（同一文档递增） */
    private Integer planVersion;

    /** 方案来源：1=系统推荐 2=用户调整 */
    private Integer planSource;

    /** 方案状态：1=待确认 2=已确认 3=已执行 4=已废弃 */
    private Integer planStatus;

    /** 策略步骤总数 */
    private Integer strategyCount;

    /** 策略组合快照（如 PARENT:1;CHILD:3,2） */
    private String strategySnapshot;

    /** 推荐理由（分号分隔的中文描述） */
    private String recommendReason;

    /** 用户调整说明 */
    private String adjustNote;

    /** 确认人ID */
    private Long confirmUserId;

    /** 确认时间 */
    private Date confirmTime;
}
