package com.reubenagent.document.enums;

import com.reubenagent.common.enums.BaseEnum;
import com.reubenagent.common.enums.EnumUtils;
import lombok.Getter;

/**
 * 文档向量存储类型枚举。
 *
 * @author reuben
 * @since 2026-06-20
 */
public enum DocumentVectorStoreTypeEnum implements BaseEnum {
    MILVUS(1, "Milvus"),
    PG_VECTOR(2, "PgVector"),
    ELASTICSEARCH(3, "Elasticsearch");

    @Getter
    private final Integer code;
    private final String msg;

    DocumentVectorStoreTypeEnum(Integer code, String msg) {
        this.code = code;
        this.msg = msg;
    }

    public String getMsg() {
        return msg;
    }

    public static DocumentVectorStoreTypeEnum getFromCode(Integer code) {
        return EnumUtils.getFromCode(DocumentVectorStoreTypeEnum.class, code);
    }
}
