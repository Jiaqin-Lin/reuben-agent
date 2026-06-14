package com.reubenagent.document.enums;

import lombok.Getter;

public enum DocumentTaskStatusEnum {
    NEW(1, "新建"),
    RUNNING(2, "进行中"),
    SUCCESS(3, "成功"),
    FAILED(4, "失败"),
    CANCELED(5, "已取消");

    @Getter
    private final Integer code;
    private final String msg;

    DocumentTaskStatusEnum(Integer code, String msg) {
        this.code = code;
        this.msg = msg;
    }

    public String getMsg() {
        return msg == null ? "" : msg;
    }

    public static DocumentTaskStatusEnum getFromCode(Integer code) {
        for (DocumentTaskStatusEnum item : DocumentTaskStatusEnum.values()) {
            if (item.code.intValue() == code.intValue()) {
                return item;
            }
        }
        return null;
    }
}