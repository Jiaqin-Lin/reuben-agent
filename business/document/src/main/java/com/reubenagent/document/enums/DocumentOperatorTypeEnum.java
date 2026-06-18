package com.reubenagent.document.enums;

import com.reubenagent.common.enums.BaseEnum;
import com.reubenagent.common.enums.EnumUtils;
import lombok.Getter;

/**
 * 操作者类型枚举 —— 区分操作日志的触发方。
 *
 * @author reuben
 * @since 2026-06-14
 */
public enum DocumentOperatorTypeEnum implements BaseEnum {
    SYSTEM(1, "系统"),
    USER(2, "用户"),
    ADMIN(3, "管理员")
    ;

    @Getter
    private final Integer code;
    private final String msg;

    DocumentOperatorTypeEnum(Integer code, String msg) {
        this.code = code;
        this.msg = msg;
    }

    public String getMsg() {
        return msg;
    }

    public static DocumentOperatorTypeEnum getFromCode(Integer code) {
        return EnumUtils.getFromCode(DocumentOperatorTypeEnum.class, code);
    }
}