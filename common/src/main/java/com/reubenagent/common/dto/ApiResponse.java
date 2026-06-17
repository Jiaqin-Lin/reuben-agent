package com.reubenagent.common.dto;

import com.reubenagent.common.enums.BaseCode;
import lombok.Data;

import java.io.Serializable;

/**
 * 统一 API 响应包装。{@code code=0} 成功，负数错误，正数业务状态码。
 *
 * @param <T> 响应数据类型
 * @author reuben
 * @since 2026-06-14
 */
@Data
public class ApiResponse<T> implements Serializable {

    /** 状态码，0=成功，负数=错误，正数=业务状态 */
    private Integer code;

    /** 人类可读的提示信息 */
    private String message;

    /** 响应数据体，可为 null */
    private T data;

    private ApiResponse() {}

    // ============ error 工厂方法 ============

    /**
     * 通过 code + message 构造错误响应。
     *
     * @param code    错误码
     * @param message 错误消息
     */
    public static <T> ApiResponse<T> error(Integer code, String message) {
        ApiResponse<T> apiResponse = new ApiResponse<>();
        apiResponse.code = code;
        apiResponse.message = message;
        return apiResponse;
    }

    /**
     * 通过纯消息构造错误响应，code 默认为 -100。
     *
     * @param message 错误消息
     */
    public static <T> ApiResponse<T> error(String message) {
        ApiResponse<T> apiResponse = new ApiResponse<>();
        apiResponse.code = -100;
        apiResponse.message = message;
        return apiResponse;
    }

    /**
     * 通过 code + data 构造错误响应（无 message）。
     *
     * @param code 错误码
     * @param data 错误附带的数据
     */
    public static <T> ApiResponse<T> error(Integer code, T data) {
        ApiResponse<T> apiResponse = new ApiResponse<>();
        apiResponse.code = code;
        apiResponse.data = data;
        return apiResponse;
    }

    /**
     * 通过 {@link BaseCode} 枚举构造错误响应。
     *
     * @param baseCode 错误码枚举
     */
    public static <T> ApiResponse<T> error(BaseCode baseCode) {
        ApiResponse<T> apiResponse = new ApiResponse<>();
        apiResponse.code = baseCode.getCode();
        apiResponse.message = baseCode.getMsg();
        return apiResponse;
    }

    /**
     * 通过 {@link BaseCode} 枚举 + 附加数据构造错误响应。
     *
     * @param baseCode 错误码枚举
     * @param data     错误附带的数据
     */
    public static <T> ApiResponse<T> error(BaseCode baseCode, T data) {
        ApiResponse<T> apiResponse = new ApiResponse<>();
        apiResponse.code = baseCode.getCode();
        apiResponse.message = baseCode.getMsg();
        apiResponse.data = data;
        return apiResponse;
    }

    /**
     * 构造默认系统错误响应（code=-100，message="系统错误，请稍后重试!"）。
     */
    public static <T> ApiResponse<T> error() {
        ApiResponse<T> apiResponse = new ApiResponse<>();
        apiResponse.code = -100;
        apiResponse.message = "系统错误，请稍后重试!";
        return apiResponse;
    }

    // ============ ok 工厂方法 ============

    /**
     * 构造成功响应（code=0），无 data。
     */
    public static <T> ApiResponse<T> ok() {
        ApiResponse<T> apiResponse = new ApiResponse<>();
        apiResponse.code = 0;
        return apiResponse;
    }

    /**
     * 构造成功响应（code=0），携带 data。
     *
     * @param data 响应数据
     */
    public static <T> ApiResponse<T> ok(T data) {
        ApiResponse<T> apiResponse = new ApiResponse<>();
        apiResponse.code = 0;
        apiResponse.data = data;
        return apiResponse;
    }
}
