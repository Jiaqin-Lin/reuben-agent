package com.reubenagent.document.enums;

import lombok.Getter;

public enum DocumentOperatorTypeEnum {
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
        if (code == null) { return null; }
        for (DocumentOperatorTypeEnum item : DocumentOperatorTypeEnum.values()) {
            if (item.code.intValue() == code.intValue()) {
                return item;
            }
        }
        return null;
    }
}