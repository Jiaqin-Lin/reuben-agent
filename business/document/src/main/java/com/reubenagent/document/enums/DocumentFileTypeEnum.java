package com.reubenagent.document.enums;

import lombok.Getter;

public enum DocumentFileTypeEnum {
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
        if (code == null) { return null; }
        for (DocumentFileTypeEnum item : DocumentFileTypeEnum.values()) {
            if (item.code.intValue() == code.intValue()) {
                return item;
            }
        }
        return null;
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
