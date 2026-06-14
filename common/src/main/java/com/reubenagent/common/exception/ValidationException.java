package com.reubenagent.common.exception;

import com.reubenagent.common.enums.BaseCode;

import java.util.Collections;
import java.util.List;

/**
 * 参数校验异常 —— 携带字段级错误列表。
 *
 * <h3>使用示例</h3>
 * <pre>{@code
 * // 单字段
 * throw new ValidationException("file", "文件不能为空");
 *
 * // 多字段
 * throw new ValidationException(List.of(
 *     new ValidationError("name", "名称不能为空"),
 *     new ValidationError("type", "类型不支持")
 * ));
 * }</pre>
 *
 * <p>全局异常处理器会将 {@link #getErrors()} 作为 {@code data} 返回给前端，
 * 前端可直接遍历展示校验提示。</p>
 *
 * @author reuben
 * @since 2026-06-14
 */
public class ValidationException extends BusinessException {

    private final List<ValidationError> errors;

    // ============ 单字段 ============

    public ValidationException(String field, String message) {
        super(BaseCode.PARAMETER_ERROR, message);
        this.errors = List.of(new ValidationError(field, message));
    }

    // ============ 多字段 ============

    public ValidationException(List<ValidationError> errors) {
        super(BaseCode.PARAMETER_ERROR, formatMessage(errors));
        this.errors = Collections.unmodifiableList(errors);
    }

    // ============ getter ============

    /** 校验错误列表（不可修改） */
    public List<ValidationError> getErrors() {
        return errors;
    }

    // ============ 内部 ============

    private static String formatMessage(List<ValidationError> errors) {
        if (errors == null || errors.isEmpty()) return "参数校验失败";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < errors.size(); i++) {
            if (i > 0) sb.append("; ");
            sb.append(errors.get(i).getField()).append(": ").append(errors.get(i).getMessage());
        }
        return sb.toString();
    }
}
