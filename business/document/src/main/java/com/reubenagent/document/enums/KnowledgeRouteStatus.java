package com.reubenagent.document.enums;

import com.reubenagent.common.enums.BaseEnum;
import com.reubenagent.common.enums.EnumUtils;
import lombok.Getter;

/**
 * 知识路由状态。
 *
 * @author reuben
 * @since 2026-06-28
 */
public enum KnowledgeRouteStatus implements BaseEnum {
    SUCCESS(1, "路由成功"),
    LOW_CONFIDENCE(2, "低置信度"),
    FAILED(3, "路由失败");

    @Getter
    private final Integer code;
    private final String msg;

    KnowledgeRouteStatus(Integer code, String msg) {
        this.code = code;
        this.msg = msg;
    }

    public String getMsg() {
        return msg;
    }

    public static KnowledgeRouteStatus getFromCode(Integer code) {
        return EnumUtils.getFromCode(KnowledgeRouteStatus.class, code);
    }
}
