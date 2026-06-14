package com.reubenagent.document.enums;

import lombok.Getter;

public enum DocumentTaskTypeEnum {
    PARSE_ROUTE(1, "解析路由"),
    BUILD_INDEX(2, "构建索引");

    @Getter
    private final Integer code;
    private final String msg;

    DocumentTaskTypeEnum(Integer code, String msg) {
        this.code = code;
        this.msg = msg;
    }

    public String getMsg() {
        return msg == null ? "" : msg;
    }

    public static DocumentTaskTypeEnum getFromCode(Integer code) {
        for (DocumentTaskTypeEnum item : DocumentTaskTypeEnum.values()) {
            if (item.code.intValue() == code.intValue()) {
                return item;
            }
        }
        return null;
    }
}