package com.reubenagent.chat.model.orchestrate;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 对话条目锚点 —— 导航决策中对"目标条目/步骤"的定位信息。
 *
 * <p>由 {@code DocumentQuestionRouter} 在识别到条目引用（第几步/哪一项）后产出，供
 * {@link com.reubenagent.chat.orchestrate.GraphThenEvidenceExecutor} 精确定位条目并校验证据。</p>
 *
 * @author reuben
 * @since 2026-06-28
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ConversationItemAnchor {

    /** 条目序号（从问题中提取的"第几步/项"） */
    private Integer itemIndex;

    /** 条目文本提示（引用的条目内容线索） */
    private String itemText;

    /** 条目所属章节的结构节点 ID */
    private Long structureNodeId;

    /** 条目所属章节的规范化路径 */
    private String canonicalPath;
}
