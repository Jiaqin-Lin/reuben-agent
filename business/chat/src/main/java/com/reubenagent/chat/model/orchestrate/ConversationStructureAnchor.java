package com.reubenagent.chat.model.orchestrate;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 对话结构锚点 —— 导航决策中对"目标章节"的定位信息。
 *
 * <p>由 {@code DocumentQuestionRouter} 在 Section Resolution 后产出，供 executor 通过
 * {@link com.reubenagent.chat.orchestrate.StructureGraphQueryEngine} 直查目标章节，无需再次猜图。</p>
 *
 * @author reuben
 * @since 2026-06-28
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ConversationStructureAnchor {

    /** 根章节编码（如 "1.2"），用于跨章节上下文锚定 */
    private String rootSectionCode;

    /** 根章节标题 */
    private String rootSectionTitle;

    /** 目标章节提示文本（问题中提取的章节线索，如引用的标题） */
    private String targetSectionHint;

    /** 定位到的结构节点 ID（落图查询的精确锚点，未定位时为 null） */
    private Long structureNodeId;

    /** 规范化路径（如 /document/h1_2），用于唯一标识章节 */
    private String canonicalPath;

    /** 范围模式（SOFT / HARD_SECTION 等，供检索 filter 决策） */
    private String scopeMode;
}
