package com.reubenagent.chat.model.orchestrate;

import com.reubenagent.chat.enums.ExecutionMode;
import com.reubenagent.common.enums.BaseEnum;
import com.reubenagent.common.enums.EnumUtils;
import lombok.Getter;

/**
 * 文档导航动作 —— 决定 DOCUMENT / AUTO_DOCUMENT 模式下检索的范围与执行路径。
 *
 * <p>对标 super-agent 全动作集：基础检索 / 先定位再检索 / 纯结构，叠加导航全动作
 * （主题延续 / 切换 / 全新主题 / 兄弟章节切换 / 子章节下钻 / 祖先章节回溯 / 条目引用 / 章节邻接查询）。</p>
 *
 * @author reuben
 * @since 2026-06-25
 */
public enum DocumentNavigationAction implements BaseEnum {

    /** 不需要结构定位，直接证据检索 */
    DIRECT_RETRIEVAL(1, "直接证据检索"),
    /** 先定位章节结构，缩小检索范围再检索证据 */
    LOCATE_THEN_RETRIEVE(2, "先定位章节再检索"),
    /** 仅展示结构摘要，不检索证据 */
    GRAPH_ONLY(3, "纯结构摘要"),
    /** 主题延续：沿用上一轮的章节锚点继续深入 */
    TOPIC_CONTINUE(4, "主题延续"),
    /** 主题切换：在同一文档内切换到另一主题/章节 */
    TOPIC_SWITCH(5, "主题切换"),
    /** 全新主题：与历史无关，重新定位章节 */
    FRESH_TOPIC(6, "全新主题"),
    /** 兄弟章节切换：在同级章节间跳转 */
    SIBLING_SECTION_SWITCH(7, "兄弟章节切换"),
    /** 子章节下钻：列出/进入目标章节的子章节 */
    CHILD_SECTION_DESCEND(8, "子章节下钻"),
    /** 祖先章节回溯：返回上级/根章节 */
    ANCESTOR_SECTION_RETURN(9, "祖先章节回溯"),
    /** 条目引用：定位章节内的具体步骤/条目 */
    ITEM_REFERENCE(10, "条目引用"),
    /** 章节邻接查询：上一节/下一节/属于哪个章节 */
    SECTION_ADJACENCY_LOOKUP(11, "章节邻接查询");

    @Getter
    private final Integer code;
    private final String msg;

    DocumentNavigationAction(Integer code, String msg) {
        this.code = code;
        this.msg = msg;
    }

    @Override
    public String getMsg() {
        return msg;
    }

    public static DocumentNavigationAction getFromCode(Integer code) {
        return EnumUtils.getFromCode(DocumentNavigationAction.class, code);
    }

    /**
     * 映射到执行模式：
     * <ul>
     *   <li>纯结构类（GRAPH_ONLY / SECTION_ADJACENCY_LOOKUP / CHILD_SECTION_DESCEND / SIBLING_SECTION_SWITCH / ANCESTOR_SECTION_RETURN）→ GRAPH_ONLY；</li>
     *   <li>条目类（ITEM_REFERENCE / LOCATE_THEN_RETRIEVE）→ GRAPH_THEN_EVIDENCE；</li>
     *   <li>其余检索类 → RETRIEVAL。</li>
     * </ul>
     */
    public ExecutionMode toExecutionMode() {
        return switch (this) {
            case GRAPH_ONLY, SECTION_ADJACENCY_LOOKUP, CHILD_SECTION_DESCEND,
                    SIBLING_SECTION_SWITCH, ANCESTOR_SECTION_RETURN -> ExecutionMode.GRAPH_ONLY;
            case ITEM_REFERENCE, LOCATE_THEN_RETRIEVE -> ExecutionMode.GRAPH_THEN_EVIDENCE;
            default -> ExecutionMode.RETRIEVAL;
        };
    }
}
