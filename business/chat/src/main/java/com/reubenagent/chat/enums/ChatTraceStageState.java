package com.reubenagent.chat.enums;

import com.reubenagent.common.enums.BaseEnum;
import com.reubenagent.common.enums.EnumUtils;
import lombok.Getter;

/**
 * 追踪阶段运行状态。
 *
 * @author reuben
 * @since 2026-06-23
 */
public enum ChatTraceStageState implements BaseEnum {

    RUNNING(1, "执行中"),
    COMPLETED(2, "已完成"),
    FAILED(3, "失败"),
    SKIPPED(4, "跳过");

    @Getter
    private final Integer code;
    private final String msg;

    ChatTraceStageState(Integer code, String msg) {
        this.code = code;
        this.msg = msg;
    }

    @Override
    public String getMsg() {
        return msg;
    }

    public static ChatTraceStageState getFromCode(Integer code) {
        return EnumUtils.getFromCode(ChatTraceStageState.class, code);
    }
}
