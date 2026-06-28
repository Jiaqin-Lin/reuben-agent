package com.reubenagent.document.model.graph;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 章节及其子章节聚合视图。
 *
 * @author reuben
 * @since 2026-06-28
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GraphSectionWithChildren {

    private GraphSection section;
    private List<GraphSection> children;
}
