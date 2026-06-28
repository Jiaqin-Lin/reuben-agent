package com.reubenagent.document.model.graph;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 图查询聚合结果 —— 一次图查询返回的目标章节 + 父/兄弟 + 子章节 + 条目集合。
 *
 * @author reuben
 * @since 2026-06-28
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GraphQueryResult {

    private GraphSection targetSection;
    private GraphSection parentSection;
    private GraphSection previousSibling;
    private GraphSection nextSibling;
    private GraphItem targetItem;
    /** 目标章节的直接子章节 */
    private List<GraphSection> children;
    /** 命中关键词的条目 */
    private List<GraphItem> matchedItems;
    /** 目标章节下的全部条目 */
    private List<GraphItem> allItems;
}
