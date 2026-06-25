package com.reubenagent.chat.model.orchestrate;

import com.reubenagent.common.enums.BaseEnum;
import com.reubenagent.common.enums.EnumUtils;
import lombok.Getter;

/**
 * 导航检索范围模式 —— 配合 {@link DocumentNavigationAction} 决定检索 filter。
 *
 * @author reuben
 * @since 2026-06-25
 */
public enum NavigationScopeMode implements BaseEnum {

    /** 整篇文档检索 */
    WHOLE_DOCUMENT(1, "整篇文档"),
    /** 限定到指定章节子树检索 */
    SECTION_SCOPE(2, "章节范围"),
    /** 限定到指定父块下检索 */
    PARENT_BLOCK_SCOPE(3, "父块范围");

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
