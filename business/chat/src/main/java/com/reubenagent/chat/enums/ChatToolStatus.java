package com.reubenagent.chat.enums;

import com.reubenagent.common.enums.BaseEnum;
import com.reubenagent.common.enums.EnumUtils;
import lombok.Getter;

/**
 * 工具调用状态。
 *
 * @author reuben
 * @since 2026-06-23
 */
public enum ChatToolStatus implements BaseEnum {

    CALLING(1, "调用中"),
    SUCCESS(2, "成功"),
    FAILED(3, "失败");

    @Getter
    private final Integer code;
    private final String msg;

    ChatToolStatus(Integer code, String msg) {
        this.code = code;
        this.msg = msg;
    }

    @Override
    public String getMsg() {
        return msg;
    }

    public static ChatToolStatus getFromCode(Integer code) {
        return EnumUtils.getFromCode(ChatToolStatus.class, code);
    }
}
