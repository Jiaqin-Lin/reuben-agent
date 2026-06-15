package com.reubenagent.document.enums;

import lombok.Getter;

public enum DocumentStorageTypeEnum {
    MINIO(1, "minio");

    @Getter
    private final Integer code;
    private final String msg;

    DocumentStorageTypeEnum(Integer code, String msg) {
        this.code = code;
        this.msg = msg;
    }

    public String getMsg() {
        return msg;
    }

    public static DocumentStorageTypeEnum getFromCode(Integer code) {
        if (code == null) { return null; }
        for (DocumentStorageTypeEnum item : DocumentStorageTypeEnum.values()) {
            if (item.code.intValue() == code.intValue()) {
                return item;
            }
        }
        return null;
    }
}