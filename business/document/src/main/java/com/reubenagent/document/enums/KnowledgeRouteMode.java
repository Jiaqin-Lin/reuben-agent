package com.reubenagent.document.enums;

import com.reubenagent.common.enums.BaseEnum;
import com.reubenagent.common.enums.EnumUtils;
import lombok.Getter;

/**
 * 知识路由运行模式。
 *
 * @author reuben
 * @since 2026-06-28
 */
public enum KnowledgeRouteMode implements BaseEnum {
    /** 影子模式：记录路由结果但不干预实际选择 */
    SHADOW(1, "影子评估"),
    /** 自动模式：路由结果自动注入文档选择 */
    AUTO(2, "自动路由");

    @Getter
    private final Integer code;
    private final String msg;

    KnowledgeRouteMode(Integer code, String msg) {
        this.code = code;
        this.msg = msg;
    }

    public String getMsg() {
        return msg;
    }

    public static KnowledgeRouteMode getFromCode(Integer code) {
        return EnumUtils.getFromCode(KnowledgeRouteMode.class, code);
    }
}
