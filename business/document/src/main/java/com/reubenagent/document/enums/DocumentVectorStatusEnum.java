package com.reubenagent.document.enums;

import com.reubenagent.common.enums.BaseEnum;
import com.reubenagent.common.enums.EnumUtils;
import lombok.Getter;

/**
 * 文档向量化状态枚举。
 *
 * @author reuben
 * @since 2026-06-20
 */
public enum DocumentVectorStatusEnum implements BaseEnum {
    WAIT_VECTOR(1, "待向量化"),
    VECTORIZING(2, "向量化中"),
    VECTOR_SUCCESS(3, "向量化成功"),
    VECTOR_FAILED(4, "向量化失败");

    @Getter
    private final Integer code;
    private final String msg;

    DocumentVectorStatusEnum(Integer code, String msg) {
        this.code = code;
        this.msg = msg;
    }

    public String getMsg() {
        return msg;
    }

    public static DocumentVectorStatusEnum getFromCode(Integer code) {
        return EnumUtils.getFromCode(DocumentVectorStatusEnum.class, code);
    }
}
