package com.reubenagent.document.enums;

import com.reubenagent.common.enums.BaseEnum;
import com.reubenagent.common.enums.EnumUtils;
import lombok.Getter;

/**
 * 文档切块来源类型枚举。
 *
 * @author reuben
 * @since 2026-06-20
 */
public enum DocumentChunkSourceTypeEnum implements BaseEnum {
    ORIGINAL(1, "原始分块"),
    ENRICHED(2, "增强分块");

    @Getter
    private final Integer code;
    private final String msg;

    DocumentChunkSourceTypeEnum(Integer code, String msg) {
        this.code = code;
        this.msg = msg;
    }

    public String getMsg() {
        return msg;
    }

    public static DocumentChunkSourceTypeEnum getFromCode(Integer code) {
        return EnumUtils.getFromCode(DocumentChunkSourceTypeEnum.class, code);
    }
}
