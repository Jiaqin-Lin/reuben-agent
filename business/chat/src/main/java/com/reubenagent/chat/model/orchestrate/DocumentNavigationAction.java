package com.reubenagent.chat.model.orchestrate;

import com.reubenagent.common.enums.BaseEnum;
import com.reubenagent.common.enums.EnumUtils;
import lombok.Getter;

/**
 * 文档导航动作 —— 决定 DOCUMENT / AUTO_DOCUMENT 模式下检索的范围。
 *
 * @author reuben
 * @since 2026-06-25
 */
public enum DocumentNavigationAction implements BaseEnum {

    /** 不需要结构定位，直接证据检索 */
    DIRECT_RETRIEVAL(1, "直接证据检索"),
    /** 先定位章节结构，缩小检索范围再检索证据 */
    LOCATE_THEN_RETRIEVE(2, "先定位章节再检索");

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
}
