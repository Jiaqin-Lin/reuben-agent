package com.reubenagent.document.enums;

import com.reubenagent.common.enums.BaseEnum;
import com.reubenagent.common.enums.EnumUtils;
import lombok.Getter;

/**
 * 策略方案来源枚举 —— 系统自动推荐或用户手动调整。
 *
 * @author reuben
 * @since 2026-06-20
 */
public enum DocumentPlanSourceEnum implements BaseEnum {
    SYSTEM_RECOMMEND(1, "系统推荐"),
    USER_ADJUST(2, "用户调整");

    @Getter
    private final Integer code;
    private final String msg;

    DocumentPlanSourceEnum(Integer code, String msg) {
        this.code = code;
        this.msg = msg;
    }

    public String getMsg() {
        return msg;
    }

    public static DocumentPlanSourceEnum getFromCode(Integer code) {
        return EnumUtils.getFromCode(DocumentPlanSourceEnum.class, code);
    }
}
