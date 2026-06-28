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
 * 知识主题节点 —— 映射 {@code reuben_agent_knowledge_topic_node} 表。
 *
 * <p>知识主题是路由的第二级分类（如"MySQL 安装/Redis 配置/Kafka 调优"），
 * 一个主题属于一个范围，可关联多个文档。</p>
 *
 * @author reuben
 * @since 2026-06-28
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("reuben_agent_knowledge_topic_node")
@EqualsAndHashCode(callSuper = true)
public class KnowledgeTopicNode extends BaseTableData {

    @TableId(type = IdType.INPUT)
    private Long id;

    /** 主题编码（唯一） */
    private String topicCode;

    /** 主题名称 */
    private String topicName;

    /** 所属知识范围编码 */
    private String scopeCode;

    /** 主题描述 */
    private String description;

    /** 别名（逗号分隔，用于路由召回） */
    private String aliases;

    /** 示例问题（JSON 数组） */
    private String examples;

    /** 建议回答形态：explain/list/steps/compare/structure */
    private String answerShape;

    /** 执行偏好：retrieval/graph_only/graph_then_evidence */
    private String executionPreference;

    /** 排序值 */
    private Integer sortOrder;
}
