package com.reubenagent.chat.enums;

import com.reubenagent.common.enums.BaseEnum;
import com.reubenagent.common.enums.EnumUtils;
import lombok.Getter;

/**
 * 执行模式 —— 决定一轮对话如何回答。不移植 super-agent 废弃的 RAG_CHAT。
 *
 * @author reuben
 * @since 2026-06-23
 */
public enum ExecutionMode implements BaseEnum {

    GRAPH_ONLY(1, "仅结构图定位"),
    GRAPH_THEN_EVIDENCE(2, "先结构定位再检索证据"),
    RETRIEVAL(3, "直接证据检索"),
    REACT_AGENT(4, "ReAct Agent 开放式"),
    CLARIFICATION(5, "澄清确认");

    @Getter
    private final Integer code;
    private final String msg;

    ExecutionMode(Integer code, String msg) {
        this.code = code;
        this.msg = msg;
    }

    @Override
    public String getMsg() {
        return msg;
    }

    public static ExecutionMode getFromCode(Integer code) {
        return EnumUtils.getFromCode(ExecutionMode.class, code);
    }
}
