package com.reubenagent.document.enums;

import com.reubenagent.common.enums.BaseEnum;
import com.reubenagent.common.enums.EnumUtils;
import lombok.Getter;

/**
 * 知识主题的执行偏好（与 chat 侧 ExecutionMode 对应）。
 *
 * @author reuben
 * @since 2026-06-28
 */
public enum KnowledgeExecutionPreference implements BaseEnum {
    RETRIEVAL(1, "纯检索"),
    GRAPH_ONLY(2, "纯图结构"),
    GRAPH_THEN_EVIDENCE(3, "图定位+证据检索");

    @Getter
    private final Integer code;
    private final String msg;

    KnowledgeExecutionPreference(Integer code, String msg) {
        this.code = code;
        this.msg = msg;
    }

    public String getMsg() {
        return msg;
    }

    public static KnowledgeExecutionPreference getFromCode(Integer code) {
        return EnumUtils.getFromCode(KnowledgeExecutionPreference.class, code);
    }
}
