package com.reubenagent.document.model.route;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/**
 * Document 路由候选。
 *
 * @author reuben
 * @since 2026-06-28
 */
@Data
@Builder
public class DocumentRouteCandidate {

    /** 文档 ID */
    private Long documentId;

    /** 文档名称 */
    private String documentName;

    /** 最近一次索引任务 ID */
    private Long lastIndexTaskId;

    /** 知识范围编码 */
    private String knowledgeScopeCode;

    /** 知识范围名称 */
    private String knowledgeScopeName;

    /** 业务分类 */
    private String businessCategory;

    /** 文档标签 */
    private List<String> documentTags;

    /** 综合得分 */
    private BigDecimal score;

    /** 得分原因 */
    private String reason;
}
