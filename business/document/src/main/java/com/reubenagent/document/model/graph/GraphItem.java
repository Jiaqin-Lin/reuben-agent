package com.reubenagent.document.model.graph;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 图条目节点 —— 对应结构树中的 STEP / LIST_ITEM 类型节点。
 *
 * @author reuben
 * @since 2026-06-28
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GraphItem {

    private Long nodeId;
    private Long documentId;
    private Long parseTaskId;
    private Integer nodeNo;
    private Integer nodeType;
    private Long sectionNodeId;
    private Long prevSiblingNodeId;
    private Long nextSiblingNodeId;
    private String title;
    private String anchorText;
    private String sectionPath;
    private String canonicalPath;
    private String contentText;
    private Integer itemIndex;

    /** 展示文本：contentText > anchorText > title */
    public String displayText() {
        if (contentText != null && !contentText.isBlank()) {
            return contentText;
        }
        if (anchorText != null && !anchorText.isBlank()) {
            return anchorText;
        }
        return title;
    }
}
