package com.reubenagent.document.enums;

import com.reubenagent.common.enums.BaseEnum;
import com.reubenagent.common.enums.EnumUtils;
import lombok.Getter;

/**
 * 策略方案生命周期状态枚举。
 *
 * @author reuben
 * @since 2026-06-20
 */
public enum DocumentPlanStatusEnum implements BaseEnum {
    WAIT_CONFIRM(1, "待确认"),
    CONFIRMED(2, "已确认"),
    EXECUTED(3, "已执行"),
    DISCARDED(4, "已废弃");

    @Getter
    private final Integer code;
    private final String msg;

    DocumentPlanStatusEnum(Integer code, String msg) {
        this.code = code;
        this.msg = msg;
    }

    public String getMsg() {
        return msg;
    }

    public static DocumentPlanStatusEnum getFromCode(Integer code) {
        return EnumUtils.getFromCode(DocumentPlanStatusEnum.class, code);
    }
}
