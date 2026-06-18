package com.reubenagent.document.enums;

import com.reubenagent.common.enums.BaseEnum;
import com.reubenagent.common.enums.EnumUtils;
import lombok.Getter;

/**
 * 触发来源枚举 —— 区分任务是系统自动触发还是用户手动触发。
 *
 * @author reuben
 * @since 2026-06-14
 */
public enum DocumentTriggerSourceEnum implements BaseEnum {
    SYSTEM(1, "系统自动"),
    USER(2, "用户手动"),
    ;

    @Getter
    private final Integer code;
    private final String msg;

    DocumentTriggerSourceEnum(Integer code, String msg) {
        this.code = code;
        this.msg = msg;
    }

    public String getMsg() {
        return msg;
    }

    public static DocumentTriggerSourceEnum getFromCode(Integer code) {
        return EnumUtils.getFromCode(DocumentTriggerSourceEnum.class, code);
    }
}
