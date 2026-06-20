package com.reubenagent.document.enums;

import com.reubenagent.common.enums.BaseEnum;
import com.reubenagent.common.enums.EnumUtils;
import lombok.Getter;

/**
 * 策略步骤执行状态枚举。
 *
 * @author reuben
 * @since 2026-06-20
 */
public enum DocumentStrategyExecuteStatusEnum implements BaseEnum {
    WAIT_EXECUTE(1, "待执行"),
    EXECUTING(2, "执行中"),
    SUCCESS(3, "执行成功"),
    FAILED(4, "执行失败"),
    SKIPPED(5, "已跳过");

    @Getter
    private final Integer code;
    private final String msg;

    DocumentStrategyExecuteStatusEnum(Integer code, String msg) {
        this.code = code;
        this.msg = msg;
    }

    public String getMsg() {
        return msg;
    }

    public static DocumentStrategyExecuteStatusEnum getFromCode(Integer code) {
        return EnumUtils.getFromCode(DocumentStrategyExecuteStatusEnum.class, code);
    }
}
