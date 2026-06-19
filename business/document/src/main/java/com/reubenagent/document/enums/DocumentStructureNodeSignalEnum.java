package com.reubenagent.document.enums;

import com.reubenagent.document.support.DocumentStructureNodeSignalExtractor;

/**
 * 文档结构信号枚举 —— 逐行分类标签，是 {@link DocumentStructureNodeSignalExtractor} 的输出结果。
 *
 * <p>每个常量代表一行文本被分类后的信号类型，含置信度范围和匹配条件。</p>
 *
 * @author reuben
 * @since 2026-06-14
 */
public enum DocumentStructureNodeSignalEnum {
    /** 虚拟信号，文档标题行（lineNo=0，置信度 1.0） */
    DOCUMENT_TITLE,
    /** 确定型标题，正则精确命中，置信度 ≥0.92（Markdown #、中文章节、多级编号 1.2.3） */
    HEADING,
    /** 模糊型标题候选，置信度 0.58~0.62，交 LLM 二次判定 */
    HEADING_CANDIDATE,
    /** 显式步骤标记（"第1步"、"步骤二"），置信度 0.96 */
    STEP_ITEM,
    /** 列表项（有序 1. 2.、无序 - * +、复选框 [ ]、中文序号 一、） */
    LIST_ITEM,
    /** 表格行（| 包裹、\t 分隔、|---| 分隔线） */
    TABLE_ROW,
    /** Markdown 引用行（> 开头） */
    QUOTE,
    /** 正文段落，兜底分类 */
    BODY,
    /** 空白行，分隔段落和列表组 */
    BLANK,
    /** 噪声行（页眉页脚、页码、版权声明），直接丢弃 */
    NOISE
}
