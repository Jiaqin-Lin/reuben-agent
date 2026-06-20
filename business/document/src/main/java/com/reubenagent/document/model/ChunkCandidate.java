package com.reubenagent.document.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 切块候选 —— 策略执行引擎的内存中间结果。
 *
 * @author reuben
 * @since 2026-06-20
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChunkCandidate {

    /** 章节路径 */
    private String sectionPath;

    /** 关联结构节点id */
    private Long structureNodeId;

    /** 关联结构节点类型 */
    private Integer structureNodeType;

    /** 结构节点稳定路径 */
    private String canonicalPath;

    /** 列表项/步骤项序号 */
    private Integer itemIndex;

    /** 切块文本 */
    private String text;

    /** 来源类型 */
    private Integer sourceType;

    /** 便捷构造器 */
    public ChunkCandidate(String sectionPath, String text, Integer sourceType) {
        this.sectionPath = sectionPath;
        this.text = text;
        this.sourceType = sourceType;
    }
}
