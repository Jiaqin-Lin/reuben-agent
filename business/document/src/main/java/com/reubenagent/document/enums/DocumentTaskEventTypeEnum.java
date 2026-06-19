package com.reubenagent.document.enums;

import com.reubenagent.common.enums.BaseEnum;
import com.reubenagent.common.enums.EnumUtils;
import com.reubenagent.document.entity.DocumentTaskLog;
import lombok.Getter;

/**
 * 任务事件类型枚举 —— 记录任务生命周期中的关键事件，用于 {@link DocumentTaskLog}。
 *
 * @author reuben
 * @since 2026-06-14
 */
public enum DocumentTaskEventTypeEnum implements BaseEnum {
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
        return msg;
    }

    public static DocumentTaskEventTypeEnum getFromCode(Integer code) {
        return EnumUtils.getFromCode(DocumentTaskEventTypeEnum.class, code);
    }
}