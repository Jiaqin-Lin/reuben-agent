package com.reubenagent.chat.enums;

import com.reubenagent.common.enums.BaseEnum;
import com.reubenagent.common.enums.EnumUtils;
import lombok.Getter;

/**
 * 会话状态。
 *
 * @author reuben
 * @since 2026-06-23
 */
public enum ChatSessionStatus implements BaseEnum {

    IDLE(1, "空闲"),
    RUNNING(2, "执行中");

    @Getter
    private final Integer code;
    private final String msg;

    ChatSessionStatus(Integer code, String msg) {
        this.code = code;
        this.msg = msg;
    }

    @Override
    public String getMsg() {
        return msg;
    }

    public static ChatSessionStatus getFromCode(Integer code) {
        return EnumUtils.getFromCode(ChatSessionStatus.class, code);
    }
}
