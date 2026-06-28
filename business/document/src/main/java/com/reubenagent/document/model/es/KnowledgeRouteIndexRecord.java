package com.reubenagent.document.model.es;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * ES 知识路由索引记录 —— 用于写入 {@code reuben_agent_knowledge_route} 索引。
 *
 * <p>三种实体类型（scope/topic/document）共用同一索引结构，通过 {@code entityType}
 * 区分。{@code routeText} 是语义 embedding 的输入文本，{@code entityTerms} 是
 * 实体词命中用的分词列表。</p>
 *
 * @author reuben
 * @since 2026-06-28
 */
@Data
@Builder
public class KnowledgeRouteIndexRecord {

    /** 路由记录唯一标识：scope:{code} / topic:{code} / document:{id} */
    private String routeId;

    /** 实体类型：scope / topic / document */
    private String entityType;

    /** 实体编码（scopeCode 或 topicCode，document 类型可为空） */
    private String entityCode;

    /** 文档 ID（仅 document 类型） */
    private Long documentId;

    /** 所属 scopeCode（topic/document 类型冗余父级） */
    private String scopeCode;

    /** 所属 scopeName（冗余） */
    private String scopeName;

    /** 所属 topicCode（仅 document 类型冗余） */
    private String topicCode;

    /** 所属 topicName（冗余） */
    private String topicName;

    /** 文档名称（仅 document 类型） */
    private String documentName;

    /** 业务分类 */
    private String businessCategory;

    /** 展示名称（scopeName / topicName / documentName） */
    private String displayName;

    /** 描述文本 */
    private String descriptionText;

    /** 别名文本（逗号分隔扁平化） */
    private String aliasesText;

    /** 示例问题文本 */
    private String examplesText;

    /** 摘要文本（仅 document 类型，来自 DocumentProfile.summary） */
    private String summaryText;

    /** 语义路由文本（所有文本字段拼接） */
    private String routeText;

    /** 实体分词列表（code + name + aliases 分词去重） */
    private List<String> entityTerms;

    /** 标签列表 */
    private List<String> tags;
}
