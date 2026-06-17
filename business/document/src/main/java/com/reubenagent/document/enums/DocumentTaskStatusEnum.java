package com.reubenagent.document.enums;

import lombok.Getter;

/**
 * 任务状态枚举 —— 异步任务的通用生命周期。
 *
 * @author reuben
 * @since 2026-06-14
 */
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
        return msg;
    }

    public static DocumentTaskStatusEnum getFromCode(Integer code) {
        if (code == null) { return null; }
        for (DocumentTaskStatusEnum item : DocumentTaskStatusEnum.values()) {
            if (item.code.intValue() == code.intValue()) {
                return item;
            }
        }
        return null;
    }
}