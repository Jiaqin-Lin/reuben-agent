package com.reubenagent.document.enums;

import com.reubenagent.common.enums.BaseEnum;
import com.reubenagent.common.enums.EnumUtils;
import lombok.Getter;

/**
 * 知识主题的建议回答形态。
 *
 * @author reuben
 * @since 2026-06-28
 */
public enum KnowledgeAnswerShape implements BaseEnum {
    EXPLAIN(1, "解释说明"),
    LIST(2, "列表展示"),
    STEPS(3, "步骤引导"),
    COMPARE(4, "对比分析"),
    STRUCTURE(5, "结构导航");

    @Getter
    private final Integer code;
    private final String msg;

    KnowledgeAnswerShape(Integer code, String msg) {
        this.code = code;
        this.msg = msg;
    }

    public String getMsg() {
        return msg;
    }

    public static KnowledgeAnswerShape getFromCode(Integer code) {
        return EnumUtils.getFromCode(KnowledgeAnswerShape.class, code);
    }
}
