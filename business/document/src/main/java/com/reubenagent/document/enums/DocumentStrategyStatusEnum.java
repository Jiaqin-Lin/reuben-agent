package com.reubenagent.document.enums;

import lombok.Getter;

/**
 * 文档策略状态枚举 —— 解析策略（切分方案/LLM 路由）的推荐-确认-失效生命周期。
 *
 * @author reuben
 * @since 2026-06-14
 */
public enum DocumentStrategyStatusEnum {
    WAIT_TO_RECOMMEND(1, "待推荐"),
    RECOMMENDED(2, "已推荐"),
    CONFIRMED(3, "已确认"),
    EXPIRED(4, "已失效");

    @Getter
    private final Integer code;
    private final String msg;

    DocumentStrategyStatusEnum(Integer code, String msg) {
        this.code = code;
        this.msg = msg;
    }

    public String getMsg() {
        return msg;
    }

    public static DocumentStrategyStatusEnum getFromCode(Integer code) {
        if (code == null) { return null; }
        for (DocumentStrategyStatusEnum item : DocumentStrategyStatusEnum.values()) {
            if (item.code.intValue() == code.intValue()) {
                return item;
            }
        }
        return null;
    }
}