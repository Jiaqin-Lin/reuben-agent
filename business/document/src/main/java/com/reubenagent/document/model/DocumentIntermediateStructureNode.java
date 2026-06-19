package com.reubenagent.document.model;

import com.reubenagent.document.enums.DocumentStructureNodeTypeEnum;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.apache.commons.lang3.StringUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * 文档树节点 — Stage 3 草稿载体 &amp; 管线最终输出。
 *
 * <p>在 {@code DocumentStructureHierarchyResolver} 中作为可变草稿使用（通过
 * {@link #appendLine(String)} 累积正文、setter 调整层级），Stage 4 验证后直接返回，
 * 无需转换。</p>
 *
 * @author reuben
 * @since 2026-06-14
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DocumentIntermediateStructureNode {

    /** 唯一节点编号（从1递增） */
    private Integer nodeNo;

    /** 源行号（对应 {@link DocumentStructureNodeSignal#getLogicalLineNo()}），根节点为 null */
    private Integer logicalLineNo;

    /** 节点类型：1=ROOT 2=CHAPTER 3=STEP 4=LIST_ITEM */
    private Integer nodeType;

    /** 父节点编号（根为 null） */
    private Integer parentNodeNo;

    /** 前兄弟编号（同级首项为 null） */
    private Integer prevSiblingNodeNo;

    /** 后兄弟编号（同级末项为 null） */
    private Integer nextSiblingNodeNo;

    /** 树深度（根=0） */
    private Integer depth;

    /** 节点编码（如 "1.2.3"、"第一章"），列表项可能为空 */
    private String nodeCode;

    /** 标题文本（章节节点才有值） */
    private String title;

    /** 锚点文本（code + title，用于目录导航） */
    private String anchorText;

    /** 规范路径（如 /document/h1_2），机器可读 */
    private String canonicalPath;

    /** 面包屑路径（如 "第一章 > 1.1 概述"），人类可读 */
    private String sectionPath;

    /** 正文内容，逐行累积 */
    private String contentText;

    /** 同级序号（如列表第3项为3），对应 {@link DocumentStructureNodeSignal#getSequenceNo()} */
    private Integer sequenceNo;

    /** 数字路径 [1,2,3]，用于父标题精确匹配 */
    @Builder.Default
    private List<Integer> numericPath = new ArrayList<>();

    /** 标题体系：markdown / chapter / appendix / decimal / plain */
    private String sourceFamily;

    /** 置信度 0.0~1.0 */
    private double confidence;

    // ========================================================================
    // 便捷方法
    // ========================================================================

    /** @return true 如果是章节节点 */
    public boolean isSection() {
        return DocumentStructureNodeTypeEnum.CHAPTER.getCode().equals(nodeType);
    }

    /** @return true 如果是列表/步骤节点 */
    public boolean isListLike() {
        return DocumentStructureNodeTypeEnum.STEP.getCode().equals(nodeType)
                || DocumentStructureNodeTypeEnum.LIST_ITEM.getCode().equals(nodeType);
    }

    /**
     * 追加一行正文。用于 hierarchyResolver 构建阶段。
     *
     * @param line 待追加文本（null/blank 忽略）
     */
    public void appendLine(String line) {
        if (line == null || line.isBlank()) {
            return;
        }
        String normalized = line.trim();
        if (StringUtils.isEmpty(this.contentText)) {
            this.contentText = normalized;
        } else {
            this.contentText = this.contentText + "\n" + normalized;
        }
    }
}
