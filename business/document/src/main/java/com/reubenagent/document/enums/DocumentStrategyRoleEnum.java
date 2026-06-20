package com.reubenagent.document.enums;

import com.reubenagent.common.enums.BaseEnum;
import com.reubenagent.common.enums.EnumUtils;
import lombok.Getter;

/**
 * 策略步骤角色枚举 —— 在同级管道中的主次定位。
 *
 * @author reuben
 * @since 2026-06-20
 */
public enum DocumentStrategyRoleEnum implements BaseEnum {
    PRIMARY(1, "主力策略"),
    OPTIMIZE(2, "优化策略"),
    FALLBACK(3, "兜底策略"),
    ENHANCE(4, "增强策略");

    @Getter
    private final Integer code;
    private final String msg;

    DocumentStrategyRoleEnum(Integer code, String msg) {
        this.code = code;
        this.msg = msg;
    }

    public String getMsg() {
        return msg;
    }

    public static DocumentStrategyRoleEnum getFromCode(Integer code) {
        return EnumUtils.getFromCode(DocumentStrategyRoleEnum.class, code);
    }
}
