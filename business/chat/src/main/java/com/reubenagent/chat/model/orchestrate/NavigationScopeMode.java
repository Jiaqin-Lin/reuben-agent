package com.reubenagent.chat.model.orchestrate;

import com.reubenagent.common.enums.BaseEnum;
import com.reubenagent.common.enums.EnumUtils;
import lombok.Getter;

/**
 * 导航检索范围模式 —— 配合 {@link DocumentNavigationAction} 决定检索 filter。
 *
 * <p>对标 super-agent：在基础整篇/章节/父块范围之上，补 NONE（无范围）、SOFT（软提示）、
 * HARD_SECTION/HARD_ITEM/HARD_PARENT_WITH_SIBLINGS（硬约束）。</p>
 *
 * @author reuben
 * @since 2026-06-25
 */
public enum NavigationScopeMode implements BaseEnum {

    /** 无范围约束（GRAPH_ONLY 等不检索证据的动作） */
    NONE(0, "无范围"),
    /** 整篇文档检索 */
    WHOLE_DOCUMENT(1, "整篇文档"),
    /** 限定到指定章节子树检索 */
    SECTION_SCOPE(2, "章节范围"),
    /** 限定到指定父块下检索 */
    PARENT_BLOCK_SCOPE(3, "父块范围"),
    /** 软提示：检索时不硬过滤，仅作为关键词提示加权 */
    SOFT(4, "软提示"),
    /** 硬约束到指定章节（结构定位确定） */
    HARD_SECTION(5, "硬章节约束"),
    /** 硬约束到指定条目（条目引用确定） */
    HARD_ITEM(6, "硬条目约束"),
    /** 硬约束到父块含兄弟（章节邻接场景） */
    HARD_PARENT_WITH_SIBLINGS(7, "父块含兄弟约束");

    @Getter
    private final Integer code;
    private final String msg;

    NavigationScopeMode(Integer code, String msg) {
        this.code = code;
        this.msg = msg;
    }

    @Override
    public String getMsg() {
        return msg;
    }

    public static NavigationScopeMode getFromCode(Integer code) {
        return EnumUtils.getFromCode(NavigationScopeMode.class, code);
    }
}
