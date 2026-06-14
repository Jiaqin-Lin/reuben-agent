package com.reubenagent.document.enums;

import lombok.Getter;

public enum DocumentTaskEventTypeEnum {
    START(1, "开始"),
    COMPLETE(2, "完成"),
    FAILED(3, "失败"),
    RECOMMEND_STRATEGY(4, "推荐策略"),
    USER_ADJUST(5, "用户调整"),
    USER_CONFIRM(6, "用户确认");

    @Getter
    private final Integer code;
    private final String msg;

    DocumentTaskEventTypeEnum(Integer code, String msg) {
        this.code = code;
        this.msg = msg;
    }

    public String getMsg() {
        return msg == null ? "" : msg;
    }

    public static DocumentTaskEventTypeEnum getFromCode(Integer code) {
        for (DocumentTaskEventTypeEnum item : DocumentTaskEventTypeEnum.values()) {
            if (item.code.intValue() == code.intValue()) {
                return item;
            }
        }
        return null;
    }
}