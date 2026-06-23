package com.reubenagent.chat.enums;

import com.reubenagent.common.enums.BaseEnum;
import com.reubenagent.common.enums.EnumUtils;
import lombok.Getter;

/**
 * 模型调用状态 —— 替代 super-agent 的字符串字面量 "COMPLETED"/"FAILED"。
 *
 * @author reuben
 * @since 2026-06-23
 */
public enum ChatModelCallStatus implements BaseEnum {

    RUNNING(1, "执行中"),
    COMPLETED(2, "已完成"),
    FAILED(3, "失败");

    @Getter
    private final Integer code;
    private final String msg;

    ChatModelCallStatus(Integer code, String msg) {
        this.code = code;
        this.msg = msg;
    }

    @Override
    public String getMsg() {
        return msg;
    }

    public static ChatModelCallStatus getFromCode(Integer code) {
        return EnumUtils.getFromCode(ChatModelCallStatus.class, code);
    }
}
