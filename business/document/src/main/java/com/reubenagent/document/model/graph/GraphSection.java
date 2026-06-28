package com.reubenagent.document.model.graph;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 图章节节点 —— 对应结构树中的 SECTION 类型节点。
 *
 * @author reuben
 * @since 2026-06-28
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GraphSection {

    private Long nodeId;
    private Long documentId;
    private Long parseTaskId;
    private Integer nodeNo;
    private Integer depth;
    private Long parentNodeId;
    private Long prevSiblingNodeId;
    private Long nextSiblingNodeId;
    private String nodeCode;
    private String title;
    private String anchorText;
    private String sectionPath;
    private String canonicalPath;
    private String contentText;

    /** 展示标题：sectionPath > (nodeCode + title) > title */
    public String displayTitle() {
        if (sectionPath != null && !sectionPath.isBlank()) {
            return sectionPath;
        }
        if (nodeCode != null && !nodeCode.isBlank() && title != null && !title.isBlank()) {
            return nodeCode + " " + title;
        }
        return title;
    }
}
