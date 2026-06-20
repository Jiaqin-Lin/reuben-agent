package com.reubenagent.document.enums;

import com.reubenagent.common.enums.BaseEnum;
import com.reubenagent.common.enums.EnumUtils;

/**
 * 策略管道类型枚举 —— 父管道产出大纲块，子管道在父块内精细切分。
 *
 * @author reuben
 * @since 2026-06-20
 */
public enum DocumentStrategyPipelineTypeEnum implements BaseEnum {
    PARENT("PARENT", "父管道"),
    CHILD("CHILD", "子管道");

    private final String code;
    private final String msg;

    DocumentStrategyPipelineTypeEnum(String code, String msg) {
        this.code = code;
        this.msg = msg;
    }

    @Override
    public Integer getCode() {
        return null;
    }

    public String getStringCode() {
        return code;
    }

    public String getMsg() {
        return msg;
    }

    public static DocumentStrategyPipelineTypeEnum getFromCode(String code) {
        for (DocumentStrategyPipelineTypeEnum constant : values()) {
            if (constant.code.equals(code)) {
                return constant;
            }
        }
        return null;
    }
}
