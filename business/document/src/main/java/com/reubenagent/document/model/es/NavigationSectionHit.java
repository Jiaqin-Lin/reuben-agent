package com.reubenagent.document.model.es;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 导航章节搜索命中记录。
 *
 * @author reuben
 * @since 2026-06-28
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class NavigationSectionHit {

    private Long nodeId;
    private String nodeCode;
    private String title;
    private String sectionPath;
    private String canonicalPath;
    private double score;
}
