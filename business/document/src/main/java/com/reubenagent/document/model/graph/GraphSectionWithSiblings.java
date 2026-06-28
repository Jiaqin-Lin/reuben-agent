package com.reubenagent.document.model.graph;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 章节及其兄弟/父节点聚合视图，用于邻接查询。
 *
 * @author reuben
 * @since 2026-06-28
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GraphSectionWithSiblings {

    private GraphSection section;
    private GraphSection parent;
    private GraphSection previousSibling;
    private GraphSection nextSibling;
}
