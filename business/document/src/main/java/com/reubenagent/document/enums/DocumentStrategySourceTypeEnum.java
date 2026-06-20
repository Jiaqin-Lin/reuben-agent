package com.reubenagent.document.enums;

import com.reubenagent.common.enums.BaseEnum;
import com.reubenagent.common.enums.EnumUtils;
import lombok.Getter;

/**
 * 策略步骤来源枚举 —— 系统推荐或用户增留。
 *
 * @author reuben
 * @since 2026-06-20
 */
public enum DocumentStrategySourceTypeEnum implements BaseEnum {
    SYSTEM_RECOMMEND(1, "系统推荐"),
    USER_ADD(2, "用户新增"),
    USER_KEEP(3, "用户保留");

    @Getter
    private final Integer code;
    private final String msg;

    DocumentStrategySourceTypeEnum(Integer code, String msg) {
        this.code = code;
        this.msg = msg;
    }

    public String getMsg() {
        return msg;
    }

    public static DocumentStrategySourceTypeEnum getFromCode(Integer code) {
        return EnumUtils.getFromCode(DocumentStrategySourceTypeEnum.class, code);
    }
}
