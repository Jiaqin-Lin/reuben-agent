package com.reubenagent.common.enums;

import lombok.Getter;

/**
 * 通用响应码枚举。
 *
 * <p>所有领域通用错误码定义在这里。各业务模块（document/knowledge/auth）可定义自己的错误码枚举。</p>
 *
 * @author reuben
 * @since 2026-06-14
 */
public enum BaseCode {

    SUCCESS(0, "OK"),

    SYSTEM_ERROR(-1, "系统异常，请稍后重试"),

    PARAMETER_ERROR(10054, "参数校验失败"),

    UNAUTHORIZED(401, "未登录或登录已过期"),

    FORBIDDEN(403, "无权访问"),

    NOT_FOUND(404, "资源不存在");

    @Getter
    private final Integer code;
    private final String msg;

    BaseCode(Integer code, String msg) {
        this.code = code;
        this.msg = msg;
    }

    public String getMsg() {
        return msg;
    }
}
