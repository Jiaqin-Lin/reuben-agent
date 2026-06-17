package com.reubenagent.document.model;

import java.util.List;

/**
 * 信号批量提取结果。
 *
 * @param contextLines 全文各行 trimmed 文本（供上下文参考，与 signals 按行号一一对应）
 * @param signals      逐行分类信号列表（第 0 条为虚拟 DOCUMENT_TITLE）
 */
public record DocumentStructureNodeSignalBatch(
        List<String> contextLines,
        List<DocumentStructureNodeSignal> signals
) {
}