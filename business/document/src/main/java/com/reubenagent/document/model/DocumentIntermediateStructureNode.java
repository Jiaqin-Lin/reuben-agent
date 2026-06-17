package com.reubenagent.document.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class DocumentIntermediateStructureNode {

    /** 节点编号（从1开始递增，同一篇文档内唯一），持久化时映射为 DB 主键 ID */
    private Integer nodeNo;

    /** 节点类型：1=文档根节点(ROOT), 2=章节节点(CHAPTER), 3=步骤节点(STEP), 4=列表项(LIST_ITEM) */
    private Integer nodeType;

    /** 父节点的 nodeNo（根节点此值为 null），持久化时映射为 parentNodeId */
    private Integer parentNodeNo;

    /** 前一个兄弟节点的 nodeNo（同级第一个为 null），与 nextSiblingNodeNo 组成双向链表，持久化时映射为 prevSiblingNodeId */
    private Integer prevSiblingNodeNo;

    /** 后一个兄弟节点的 nodeNo（同级最后一个为 null），持久化时映射为 nextSiblingNodeId */
    private Integer nextSiblingNodeNo;

    /** 节点在文档树中的深度（根节点 depth=0，每嵌套一层 +1） */
    private Integer depth;

    /** 节点编码（标准化格式），如 "H1"、"H2_3"（第3个二级标题）、"P_1_5"（第1章第5段）。与 Signal 层的原始编码（如 "1.2.3"）不同，此处已规范化为统一前缀格式 */
    private String nodeCode;

    /** 节点标题文本（章节标题节点才有值，叶子节点和根节点通常为空） */
    private String title;

    /** 锚点文本，nodeCode + title 的组合（如 "H2_3 背景介绍"），用于目录跳转和导航定位 */
    private String anchorText;

    /** 规范路径，机器可读的精确路径表示，如 "/document/section[0]/h2[1]"，用于程序定位和精确检索 */
    private String canonicalPath;

    /** 章节路径，人类可读的面包屑路径，如 "第一章 > 1.1 概述 > 1.1.1 背景"，用于前端展示 */
    private String sectionPath;

    /** 节点下的正文内容（段落、列表项等叶子节点才有值，纯标题节点此字段为空） */
    private String contentText;

    /** 在同级结构中的位置索引（如列表第3项则为 3），用于排序和序号展示 */
    private Integer itemIndex;
}
