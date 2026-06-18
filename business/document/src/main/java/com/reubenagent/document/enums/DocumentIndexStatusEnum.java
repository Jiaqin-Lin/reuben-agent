package com.reubenagent.document.enums;

import com.reubenagent.common.enums.BaseEnum;
import com.reubenagent.common.enums.EnumUtils;
import lombok.Getter;

/**
 * 文档索引状态枚举 —— 向量索引（ES/PgVector）的构建生命周期。
 *
 * @author reuben
 * @since 2026-06-14
 */
public enum DocumentIndexStatusEnum implements BaseEnum {
    WAIT_TO_BUILD(1, "待构建"),
    BUILDING(2, "构建中"),
    BUILD_SUCCESS(3, "构建成功"),
    BUILD_FAIL(4, "构建失败");

    @Getter
    private final Integer code;
    private final String msg;

    DocumentIndexStatusEnum(Integer code, String msg) {
        this.code = code;
        this.msg = msg;
    }

    public String getMsg() {
        return msg;
    }

    public static DocumentIndexStatusEnum getFromCode(Integer code) {
        return EnumUtils.getFromCode(DocumentIndexStatusEnum.class, code);
    }
}