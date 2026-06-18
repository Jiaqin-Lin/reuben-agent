package com.reubenagent.document.enums;

import com.reubenagent.common.enums.BaseEnum;
import com.reubenagent.common.enums.EnumUtils;
import lombok.Getter;

/**
 * 任务类型枚举 —— 区分异步任务的处理类型（解析路由 vs 构建索引）。
 *
 * @author reuben
 * @since 2026-06-14
 */
public enum DocumentTaskTypeEnum implements BaseEnum {
    PARSE_ROUTE(1, "解析路由"),
    BUILD_INDEX(2, "构建索引");

    @Getter
    private final Integer code;
    private final String msg;

    DocumentTaskTypeEnum(Integer code, String msg) {
        this.code = code;
        this.msg = msg;
    }

    public String getMsg() {
        return msg;
    }

    public static DocumentTaskTypeEnum getFromCode(Integer code) {
        return EnumUtils.getFromCode(DocumentTaskTypeEnum.class, code);
    }
}