package com.reubenagent.document.model.es;

import lombok.Builder;
import lombok.Data;

/**
 * ES 词法搜索命中的路由条目。
 *
 * @author reuben
 * @since 2026-06-28
 */
@Data
@Builder
public class RouteLexicalHit {

    /** 路由记录 ID：scope:{code} / topic:{code} / document:{id} */
    private String routeId;

    /** 实体编码 */
    private String entityCode;

    /** 实体类型：scope / topic / document */
    private String entityType;

    /** 文档 ID（仅 document 类型） */
    private Long documentId;

    /** 所属 scopeCode */
    private String scopeCode;

    /** 所属 topicCode */
    private String topicCode;

    /** 文档名称 */
    private String documentName;

    /** ES 相关性分数 */
    private double score;
}
