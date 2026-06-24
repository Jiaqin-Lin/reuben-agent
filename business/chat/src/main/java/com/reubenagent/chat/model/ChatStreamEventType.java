package com.reubenagent.chat.model;

import com.reubenagent.common.enums.BaseEnum;
import com.reubenagent.common.enums.EnumUtils;
import lombok.Getter;

/**
 * SSE 流式事件类型。
 *
 * @author reuben
 * @since 2026-06-24
 */
public enum ChatStreamEventType implements BaseEnum {

    TEXT(1, "文本块"),
    THINKING(2, "思考状态"),
    STATUS(3, "状态变更"),
    ERROR(4, "错误"),
    REFERENCE(5, "引用来源"),
    RECOMMEND(6, "推荐追问"),
    DONE(7, "流结束");

    @Getter
    private final Integer code;
    private final String msg;

    ChatStreamEventType(Integer code, String msg) {
        this.code = code;
        this.msg = msg;
    }

    @Override
    public String getMsg() {
        return msg;
    }

    public static ChatStreamEventType getFromCode(Integer code) {
        return EnumUtils.getFromCode(ChatStreamEventType.class, code);
    }

    /** 协议字段 type 用字符串而非 code，与 super-agent 前端约定一致。 */
    public String protocolName() {
        return name().toLowerCase();
    }
}
