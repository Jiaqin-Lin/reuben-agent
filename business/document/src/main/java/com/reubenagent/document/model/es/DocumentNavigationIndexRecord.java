package com.reubenagent.document.model.es;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 导航章节 ES 索引记录 —— 对应 {@code reuben_agent_document_navigation} 索引的一条文档。
 *
 * <p>由文档结构节点转换而来，供 {@code DocumentQuestionRouter} 做四维章节搜索。</p>
 *
 * @author reuben
 * @since 2026-06-28
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DocumentNavigationIndexRecord {

    private Long nodeId;
    private Long documentId;
    private Long parseTaskId;
    private String nodeType;
    private String nodeCode;
    private Integer nodeNo;
    private Integer depth;
    private Long parentNodeId;
    private String title;
    private String anchorText;
    private String sectionPath;
    private String canonicalPath;
    private String contentText;
    private Integer itemIndex;
}
