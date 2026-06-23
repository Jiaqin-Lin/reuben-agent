package com.reubenagent.chat.enums;

import com.reubenagent.common.enums.BaseEnum;
import com.reubenagent.common.enums.EnumUtils;
import lombok.Getter;

/**
 * 对话模式 —— code 严格按声明顺序。
 *
 * @author reuben
 * @since 2026-06-23
 */
public enum ChatMode implements BaseEnum {

    DOCUMENT(1, "定向文档问答"),
    OPEN_CHAT(2, "开放式对话"),
    AUTO_DOCUMENT(3, "自动选文档问答");

    @Getter
    private final Integer code;
    private final String msg;

    ChatMode(Integer code, String msg) {
        this.code = code;
        this.msg = msg;
    }

    @Override
    public String getMsg() {
        return msg;
    }

    public static ChatMode getFromCode(Integer code) {
        return EnumUtils.getFromCode(ChatMode.class, code);
    }
}
