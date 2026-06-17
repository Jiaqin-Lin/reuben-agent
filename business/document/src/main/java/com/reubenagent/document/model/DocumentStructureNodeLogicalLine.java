package com.reubenagent.document.model;

/**
 * 逻辑行 —— 原始行经分段拆分后的最小处理单元。一行原文可能拆为多条逻辑行。
 */
public record DocumentStructureNodeLogicalLine(
        /** 逻辑行号（从 1 开始） */
        int logicalLineNo,
        /** 原始行号（源文本行号） */
        int rawLineNo,
        /** 段内序号（同原始行内的分段序号，未分段为 0） */
        int segmentSequence,
        /** 缩进层级（tab 按 4 空格计） */
        int indentLevel,
        /** 原始文本 */
        String rawText,
        /** trimmed 文本 */
        String trimmedText
) {
}