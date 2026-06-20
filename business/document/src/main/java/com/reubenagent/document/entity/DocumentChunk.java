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
 * 文档切块实体 —— 映射 {@code reuben_agent_document_chunk} 表。
 *
 * @author reuben
 * @since 2026-06-20
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("reuben_agent_document_chunk")
@EqualsAndHashCode(callSuper = true)
public class DocumentChunk extends BaseTableData {

    /** 主键 */
    @TableId(type = IdType.INPUT)
    private Long id;

    /** 文档id */
    private Long documentId;

    /** 索引任务id */
    private Long taskId;

    /** 策略方案id */
    private Long planId;

    /** 所属父块id */
    private Long parentBlockId;

    /** 块序号 */
    private Integer chunkNo;

    /** 来源类型：1=原文切块 2=后处理补全 */
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

    /** 切块内容 */
    @TableField("chunk_text")
    private String chunkText;

    /** 字符数 */
    private Integer charCount;

    /** token数 */
    private Integer tokenCount;

    /** 向量状态：1=待向量化 2=向量化中 3=成功 4=失败 */
    private Integer vectorStatus;

    /** 向量库类型：1=Milvus 2=PGVector 3=Elasticsearch */
    private Integer vectorStoreType;

    /** 向量库主键 */
    private String vectorId;
}
