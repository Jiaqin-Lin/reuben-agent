package com.reubenagent.document.enums;

import com.reubenagent.common.enums.BaseEnum;
import com.reubenagent.common.enums.EnumUtils;
import lombok.Getter;

/**
 * 主题-文档关联来源。
 *
 * @author reuben
 * @since 2026-06-28
 */
public enum KnowledgeRelationSource implements BaseEnum {
    AUTO(1, "自动关联"),
    MANUAL(2, "人工配置"),
    MIXED(3, "混合来源");

    @Getter
    private final Integer code;
    private final String msg;

    KnowledgeRelationSource(Integer code, String msg) {
        this.code = code;
        this.msg = msg;
    }

    public String getMsg() {
        return msg;
    }

    public static KnowledgeRelationSource getFromCode(Integer code) {
        return EnumUtils.getFromCode(KnowledgeRelationSource.class, code);
    }
}
