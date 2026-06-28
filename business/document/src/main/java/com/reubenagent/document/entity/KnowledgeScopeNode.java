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
 * 知识范围节点 —— 映射 {@code reuben_agent_knowledge_scope_node} 表。
 *
 * <p>知识范围是路由的第一级分类（如"产品文档/运维手册/API参考"），
 * 一个范围下可包含多个主题。</p>
 *
 * @author reuben
 * @since 2026-06-28
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("reuben_agent_knowledge_scope_node")
@EqualsAndHashCode(callSuper = true)
public class KnowledgeScopeNode extends BaseTableData {

    @TableId(type = IdType.INPUT)
    private Long id;

    /** 知识范围编码（唯一） */
    private String scopeCode;

    /** 知识范围名称 */
    private String scopeName;

    /** 父级范围编码（支持范围树） */
    private String parentScopeCode;

    /** 范围描述 */
    private String description;

    /** 别名（逗号分隔，用于路由召回） */
    private String aliases;

    /** 示例问题（JSON 数组） */
    private String examples;

    /** 排序值 */
    private Integer sortOrder;
}
