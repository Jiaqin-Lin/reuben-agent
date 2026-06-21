package com.reubenagent.rag.enums;

import com.reubenagent.common.enums.BaseEnum;
import com.reubenagent.common.enums.EnumUtils;
import lombok.Getter;

/**
 * RAG 检索模块错误码 —— 区间 10001 ~ 10099。
 *
 * @author reuben
 * @since 2026-06-21
 */
public enum RagErrorCode implements BaseEnum {

    RETRIEVE_FAILED(10001, "检索失败"),
    EMBEDDING_FAILED(10002, "向量化失败"),
    CHANNEL_TIMEOUT(10003, "检索通道超时"),
    INVALID_QUERY(10004, "查询参数无效"),
    ;

    @Getter
    private final Integer code;
    private final String msg;

    RagErrorCode(Integer code, String msg) {
        this.code = code;
        this.msg = msg;
    }

    @Override
    public String getMsg() {
        return msg;
    }

    public static RagErrorCode getFromCode(Integer code) {
        return EnumUtils.getFromCode(RagErrorCode.class, code);
    }
}
