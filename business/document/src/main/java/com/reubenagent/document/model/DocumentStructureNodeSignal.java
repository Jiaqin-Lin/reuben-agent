package com.reubenagent.document.model;

import com.reubenagent.document.enums.DocumentStructureNodeSignalEnum;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * 文档结构信号 —— 逐行分类的结果载体，由 {@code DocumentStructureNodeSignalExtractor} 输出。
 *
 * @author reuben
 * @since 2026-06-14
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DocumentStructureNodeSignal {

    /** 行号，从1开始（0=虚拟的文档标题行） */
    private int logicalLineNo;

    /** 原始文本（保留缩进和格式，用于日志和调试） */
    private String rawText;

    /** 规范化文本（trim 后，用于分类匹配和拼接） */
    private String trimmedText;

    /** 信号类型（HEADING / LIST_ITEM / BODY / BLANK / ...） */
    private DocumentStructureNodeSignalEnum kind;

    /** 标题编码（Signal 层保留解析时的原始编码，如 "1.2.3"、"第一章"、"附录A"），与 IntermediateNode 层的标准化编码（如 "H2_3"）不同 */
    private String headingCode;

    /** 序列号（如 "第3步" → 3、"5. xxx" → 5），非列表/步骤信号为 null */
    private Integer sequenceNo;

    /** 数字路径如 [1,2,3]，用于精确 parent matching */
    @Builder.Default
    private List<Integer> numericPath = new ArrayList<>();

    /** 节点标题（去除编码前缀后的纯标题文本） */
    private String title;

    /** 层级提示（Markdown # 数、多级编号的深度），非标题为 null */
    private Integer levelHint;

    /** 缩进空格数（用于判断嵌套列表的父子关系） */
    private Integer indentLevel;

    /** 分类依据如 "markdown-heading"，用于体系判断和调试 */
    @Builder.Default
    private List<String> reasons = new ArrayList<>();

    /** 置信度 0.0~1.0 */
    private double confidence;

    /**
     * 是否为标题型信号（确定标题或候选标题）。
     *
     * @return true 如果 kind 为 HEADING 或 HEADING_CANDIDATE
     */
    public boolean isHeadingLike() {
        return kind == DocumentStructureNodeSignalEnum.HEADING || kind == DocumentStructureNodeSignalEnum.HEADING_CANDIDATE;
    }

    /**
     * 是否为列表型信号（步骤项或列表项）。
     *
     * @return true 如果 kind 为 STEP_ITEM 或 LIST_ITEM
     */
    public boolean isListLike() {
        return kind == DocumentStructureNodeSignalEnum.STEP_ITEM || kind == DocumentStructureNodeSignalEnum.LIST_ITEM;
    }

    /**
     * 是否为模糊待判定信号（需 LLM 二次确认）。
     *
     * @return true 如果 kind 为 HEADING_CANDIDATE
     */
    public boolean isAmbiguous() {
        return kind == DocumentStructureNodeSignalEnum.HEADING_CANDIDATE;
    }
}
