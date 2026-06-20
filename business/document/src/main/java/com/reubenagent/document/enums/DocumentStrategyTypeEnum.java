package com.reubenagent.document.enums;

import com.reubenagent.common.enums.BaseEnum;
import com.reubenagent.common.enums.EnumUtils;
import lombok.Getter;

/**
 * 分块策略类型枚举 —— 四种切分方式。
 *
 * @author reuben
 * @since 2026-06-20
 */
public enum DocumentStrategyTypeEnum implements BaseEnum {
    STRUCTURE(1, "结构化切分"),
    RECURSIVE(2, "递归切分"),
    SEMANTIC(3, "语义切分"),
    LLM(4, "大模型切分");

    @Getter
    private final Integer code;
    private final String msg;

    DocumentStrategyTypeEnum(Integer code, String msg) {
        this.code = code;
        this.msg = msg;
    }

    public String getMsg() {
        return msg;
    }

    public static DocumentStrategyTypeEnum getFromCode(Integer code) {
        return EnumUtils.getFromCode(DocumentStrategyTypeEnum.class, code);
    }
}
