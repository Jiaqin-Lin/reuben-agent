package com.reubenagent.chat.enums;

import com.reubenagent.common.enums.BaseEnum;
import com.reubenagent.common.enums.EnumUtils;
import lombok.Getter;

/**
 * 全链路追踪阶段编码 —— code 即为顺序。
 *
 * @author reuben
 * @since 2026-06-23
 */
public enum ChatTraceStageCode implements BaseEnum {

    MEMORY(1, "记忆加载"),
    INTENT(2, "意图识别"),
    REWRITE(3, "查询改写"),
    ROUTE(4, "模式路由"),
    RAG_RETRIEVE(5, "RAG 检索"),
    EVIDENCE_BUDGET(6, "证据门控"),
    ANSWER_GENERATE(7, "回答生成"),
    REACT_AGENT(8, "ReAct Agent"),
    RECOMMENDATION(9, "追问生成"),
    FINALIZE(10, "收尾落库");

    @Getter
    private final Integer code;
    private final String msg;

    ChatTraceStageCode(Integer code, String msg) {
        this.code = code;
        this.msg = msg;
    }

    @Override
    public String getMsg() {
        return msg;
    }

    public static ChatTraceStageCode getFromCode(Integer code) {
        return EnumUtils.getFromCode(ChatTraceStageCode.class, code);
    }
}
