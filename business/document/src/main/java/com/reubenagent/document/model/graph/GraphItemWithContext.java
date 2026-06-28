package com.reubenagent.document.model.graph;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 条目及其所属章节 + 同级条目聚合视图。
 *
 * @author reuben
 * @since 2026-06-28
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GraphItemWithContext {

    private GraphSection section;
    private GraphItem item;
    private List<GraphItem> siblingItems;
}
