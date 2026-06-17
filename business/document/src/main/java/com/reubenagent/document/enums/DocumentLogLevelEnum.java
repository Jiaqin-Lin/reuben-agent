package com.reubenagent.document.enums;

import lombok.Getter;

/**
 * 文档任务日志级别枚举 —— 对应 {@link DocumentTaskLog} 的日志等级。
 *
 * @author reuben
 * @since 2026-06-14
 */
public enum DocumentLogLevelEnum {
    INFO(1, "INFO"),
    WARN(2, "WARN"),
    ERROR(3, "ERROR");

    @Getter
    private final Integer code;
    private final String msg;

    DocumentLogLevelEnum(Integer code, String msg) {
        this.code = code;
        this.msg = msg;
    }

    public String getMsg() {
        return msg;
    }

    public static DocumentLogLevelEnum getFromCode(Integer code) {
        if (code == null) { return null; }
        for (DocumentLogLevelEnum item : DocumentLogLevelEnum.values()) {
            if (item.code.intValue() == code.intValue()) {
                return item;
            }
        }
        return null;
    }
}