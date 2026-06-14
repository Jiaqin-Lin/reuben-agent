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

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("reuben_agent_document_task")
@EqualsAndHashCode(callSuper = true)
public class DocumentTask extends BaseTableData {

    /** 主键ID */
    @TableId(value = "id", type = IdType.INPUT)
    private Long id;

    /** 所属文档ID，关联 document 表 */
    private Long documentId;

    /** 使用的策略方案ID，关联 document_strategy_plan 表 */
    private Long strategyPlanId;

    /** 任务类型 1=解析路由, 2=构建索引 */
    private Integer taskType;

    /** 任务状态 1=新建, 2=进行中, 3=成功, 4=失败, 5=已取消 */
    private Integer taskStatus;

    /** 当前阶段 1=文件上传, 2=内容解析, 3=策略路由, 4=策略确认, 5=切块执行, 6=切块后处理, 7=向量化, 8=入库完成 */
    private Integer currentStage;

    /** 触发来源 1=系统自动, 2=用户手动 */
    private Integer triggerSource;

    /** 任务执行时使用的策略快照，用于可追溯性 */
    private String strategySnapshot;

    /** 重试次数（初始为0，每次重试递增） */
    private Integer retryCount;

    /** 任务开始执行时间 */
    private Date startTime;

    /** 任务结束时间 */
    private Date finishTime;

    /** 任务总耗时（毫秒） */
    private Long costMillis;

    /** 失败时的错误码 */
    private String errorCode;

    /** 失败时的错误信息 */
    private String errorMsg;

    /** 扩展信息（JSON格式），存储任务执行过程中的补充数据 */
    private String extJson;
}
