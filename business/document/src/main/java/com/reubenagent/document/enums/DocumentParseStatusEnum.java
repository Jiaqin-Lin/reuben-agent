package com.reubenagent.document.enums;

import lombok.Getter;

/**
 * 文档解析状态枚举 —— 文件内容提取（Tika/OCR）的生命周期。
 *
 * @author reuben
 * @since 2026-06-14
 */
public enum DocumentParseStatusEnum {
    WAIT_TO_PARSE(1, "待解析"),
    PARSING(2, "解析中"),
    PARSE_SUCCESS(3, "解析成功"),
    PARSE_FAIL(4, "解析失败");

    @Getter
    private final Integer code;

    private final String msg;

    DocumentParseStatusEnum(Integer code, String msg) {
        this.code = code;
        this.msg = msg;
    }

    public String getMsg() {
        return msg;
    }

    public static DocumentParseStatusEnum getFromCode(Integer code) {
        if (code == null) { return null; }
        for (DocumentParseStatusEnum item : DocumentParseStatusEnum.values()) {
            if (item.code.intValue() == code.intValue()) {
                return item;
            }
        }
        return null;
    }
}