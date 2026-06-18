package com.reubenagent.document.enums;

import com.reubenagent.common.enums.BaseEnum;
import com.reubenagent.common.enums.EnumUtils;
import lombok.Getter;

/**
 * 支持的文件类型枚举 —— 定义系统可处理的文档格式。
 *
 * @author reuben
 * @since 2026-06-14
 */
public enum DocumentFileTypeEnum implements BaseEnum {
    PDF(1, "PDF"),
    DOC(2, "DOC"),
    DOCX(3, "DOCX"),
    TXT(4, "TXT"),
    MD(5, "MD"),
    HTML(6, "HTML");

    @Getter
    private final Integer code;

    private final String msg;

    public String getMsg() {
        return msg;
    }

    DocumentFileTypeEnum(Integer code, String msg) {
        this.code = code;
        this.msg = msg;
    }

    public static DocumentFileTypeEnum getFromCode(Integer code) {
        return EnumUtils.getFromCode(DocumentFileTypeEnum.class, code);
    }

    public static DocumentFileTypeEnum getEnumFromFileName(String fileName) {
        if (fileName == null) {return null;}
        String lower = fileName.toLowerCase();
        if (lower.endsWith(".pdf"))  return PDF;
        if (lower.endsWith(".doc"))  return DOC;
        if (lower.endsWith(".docx")) return DOCX;
        if (lower.endsWith(".txt"))  return TXT;
        if (lower.endsWith(".md"))   return MD;
        if (lower.endsWith(".html") || lower.endsWith(".htm")) return HTML;
        return null;
    }
}
