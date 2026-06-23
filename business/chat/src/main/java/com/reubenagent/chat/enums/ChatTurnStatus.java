package com.reubenagent.chat.enums;

import com.reubenagent.common.enums.BaseEnum;
import com.reubenagent.common.enums.EnumUtils;
import lombok.Getter;

/**
 * 对话轮次状态。
 *
 * @author reuben
 * @since 2026-06-23
 */
public enum ChatTurnStatus implements BaseEnum {

    RUNNING(1, "执行中"),
    COMPLETED(2, "已完成"),
    FAILED(3, "失败"),
    STOPPED(4, "已停止");

    @Getter
    private final Integer code;
    private final String msg;

    ChatTurnStatus(Integer code, String msg) {
        this.code = code;
        this.msg = msg;
    }

    @Override
    public String getMsg() {
        return msg;
    }

    public static ChatTurnStatus getFromCode(Integer code) {
        return EnumUtils.getFromCode(ChatTurnStatus.class, code);
    }
}
