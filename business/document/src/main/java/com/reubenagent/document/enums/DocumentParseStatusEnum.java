package com.reubenagent.document.enums;

import lombok.Getter;

public enum DocumentParseStatusEnum {
    WAIT_TO_PARSE(1, "待解析"),
    PARSING(2, "解析中"),
    PARSE_SUCCESS(3, "解析成功"),
    PARSE_FAIL(4, "解析失败");

    @Getter
    private final Integer code;

    private final String msg;

    DocumentParseStatusEnum(Integer code, String msg) {
        this.code = code;
        this.msg = msg;
    }

    public String getMsg() {
        return msg == null ? "" : msg;
    }

    public static DocumentParseStatusEnum getFromCode(Integer code) {
        for (DocumentParseStatusEnum item : DocumentParseStatusEnum.values()) {
            if (item.code.intValue() == code.intValue()) {
                return item;
            }
        }
        return null;
    }
}