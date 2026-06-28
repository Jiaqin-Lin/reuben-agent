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

import java.math.BigDecimal;

/**
 * 主题-文档关联 —— 映射 {@code reuben_agent_topic_document_relation} 表。
 *
 * <p>表示某个知识主题下包含哪些文档，以及关联强度和来源。</p>
 *
 * @author reuben
 * @since 2026-06-28
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("reuben_agent_topic_document_relation")
@EqualsAndHashCode(callSuper = true)
public class TopicDocumentRelation extends BaseTableData {

    @TableId(type = IdType.INPUT)
    private Long id;

    /** 主题编码 */
    private String topicCode;

    /** 文档 ID */
    private Long documentId;

    /** 关联分数（0~1） */
    private BigDecimal relationScore;

    /** 关联来源：auto/manual/mixed */
    private String relationSource;

    /** 关联原因 */
    private String reason;
}
