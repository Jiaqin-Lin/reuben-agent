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
 * 文档结构节点实体 —— 映射 {@code reuben_agent_document_structure_node} 表。
 *
 * <p>通过 parentNodeId / prevSiblingNodeId / nextSiblingNodeId 三个自引用外键
 * 形成树形 + 双向链表结构。nodeNo 为管线内部的逻辑编号，持久化时通过 ID 映射
 * 转换为数据库主键引用。</p>
 *
 * @author reuben
 * @since 2026-06-19
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("reuben_agent_document_structure_node")
@EqualsAndHashCode(callSuper = true)
public class DocumentStructureNode extends BaseTableData {

    /** 主键（雪花 ID，由调用方预分配） */
    @TableId(type = IdType.INPUT)
    private Long id;

    /** 所属文档 ID */
    private Long documentId;

    /** 解析任务 ID */
    private Long parseTaskId;

    /** 节点序号（从 1 递增，管线内部逻辑编号） */
    private Integer nodeNo;

    /** 节点类型：1=ROOT 2=CHAPTER 3=STEP 4=LIST_ITEM */
    private Integer nodeType;

    /** 父节点 ID（根节点为 null） */
    private Long parentNodeId;

    /** 前兄弟节点 ID（同级首项为 null） */
    private Long prevSiblingNodeId;

    /** 后兄弟节点 ID（同级末项为 null） */
    private Long nextSiblingNodeId;

    /** 树深度（根 = 0） */
    private Integer depth;

    /** 节点编码（如 "1.2.3"、"第一章"） */
    private String nodeCode;

    /** 标题文本（章节节点才有值） */
    private String title;

    /** 锚点文本（code + title，用于目录导航） */
    private String anchorText;

    /** 规范路径（如 /document/h1_2），机器可读 */
    private String canonicalPath;

    /** 面包屑路径（如 "第一章 > 1.1 概述"），人类可读 */
    private String sectionPath;

    /** 节点正文（叶子节点累积的文本内容） */
    private String contentText;

    /** 同级序号（列表/步骤项在同级中的位置） */
    private Integer itemIndex;
}
