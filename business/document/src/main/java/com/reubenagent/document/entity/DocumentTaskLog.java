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

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("reuben_agent_document_task_log")
@EqualsAndHashCode(callSuper = true)
public class DocumentTaskLog extends BaseTableData {

    /** 主键ID */
    @TableId(value = "id", type = IdType.INPUT)
    private Long id;

    /** 关联的任务ID，关联 super_agent_document_task 表 */
    private Long taskId;

    /** 关联的文档ID，关联 super_agent_document 表（冗余字段，便于查询） */
    private Long documentId;

    /** 阶段类型 1=文件上传, 2=内容解析, 3=策略路由, 4=策略确认, 5=切块执行, 6=切块后处理, 7=向量化, 8=入库完成 */
    private Integer stageType;

    /** 事件类型 1=开始, 2=完成, 3=失败, 4=推荐策略, 5=用户调整, 6=用户确认 */
    private Integer eventType;

    /** 日志级别 1=INFO, 2=WARN, 3=ERROR */
    private Integer logLevel;

    /** 操作人类型 1=系统, 2=用户, 3=管理员 */
    private Integer operatorType;

    /** 操作者ID（系统操作时为null，人工操作时为用户ID） */
    private Long operatorId;

    /** 日志文本内容 */
    private String content;

    /** 详细信息（JSON格式），存储结构化的补充数据，如处理数量、耗时细节等 */
    private String detailJson;
}
