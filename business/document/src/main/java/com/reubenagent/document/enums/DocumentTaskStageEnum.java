package com.reubenagent.document.enums;

import lombok.Getter;

/**
 * 任务阶段枚举 —— 从文件上传到入库完成的 8 步流水线阶段。
 *
 * @author reuben
 * @since 2026-06-14
 */
public enum DocumentTaskStageEnum {
    FILE_UPLOAD(1, "文件上传"),
    CONTENT_PARSE(2, "内容解析"),
    STRATEGY_ROUTE(3, "策略路由"),
    STRATEGY_CONFIRM(4, "策略确认"),
    CHUNK_EXECUTE(5, "切块执行"),
    CHUNK_POST_PROCESS(6, "切块后处理"),
    VECTORIZE(7, "向量化"),
    STORE_COMPLETE(8, "入库完成");

    @Getter
    private final Integer code;
    private final String msg;

    DocumentTaskStageEnum(Integer code, String msg) {
        this.code = code;
        this.msg = msg;
    }

    public String getMsg() {
        return msg;
    }

    public static DocumentTaskStageEnum getFromCode(Integer code) {
        if (code == null) { return null; }
        for (DocumentTaskStageEnum item : DocumentTaskStageEnum.values()) {
            if (item.code.intValue() == code.intValue()) {
                return item;
            }
        }
        return null;
    }
}