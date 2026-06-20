package com.reubenagent.document.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.reubenagent.common.data.BaseTableData;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

/**
 * 文档父块实体 —— 映射 {@code reuben_agent_document_parent_block} 表。
 *
 * @author reuben
 * @since 2026-06-20
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("reuben_agent_document_parent_block")
@EqualsAndHashCode(callSuper = true)
public class DocumentParentBlock extends BaseTableData {

    /** 主键 */
    @TableId(type = IdType.INPUT)
    private Long id;

    /** 文档id */
    private Long documentId;

    /** 索引任务id */
    private Long taskId;

    /** 策略方案id */
    private Long planId;

    /** 父块序号 */
    private Integer parentNo;

    /** 来源类型：1=原文 2=后处理 */
    private Integer sourceType;

    /** 章节路径 */
    private String sectionPath;

    /** 关联结构节点id */
    private Long structureNodeId;

    /** 关联结构节点类型 */
    private Integer structureNodeType;

    /** 结构节点稳定路径 */
    private String canonicalPath;

    /** 列表项序号 */
    private Integer itemIndex;

    /** 父块完整内容 */
    @TableField("parent_text")
    private String parentText;

    /** 字符数 */
    private Integer charCount;

    /** token数 */
    private Integer tokenCount;

    /** child数量 */
    private Integer childCount;

    /** 第一个child序号 */
    private Integer startChunkNo;

    /** 最后一个child序号 */
    private Integer endChunkNo;
}
