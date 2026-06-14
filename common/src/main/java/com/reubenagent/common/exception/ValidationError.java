package com.reubenagent.common.exception;

/**
 * 单个字段的校验错误 —— DTO，不是异常。
 *
 * <h3>使用</h3>
 * 配合 {@link ValidationException}：
 * <pre>{@code
 * throw new ValidationException(List.of(
 *     new ValidationError("file", "文件不能为空"),
 *     new ValidationError("fileType", "不支持的文件类型: .exe")
 * ));
 * }</pre>
 *
 * @author reuben
 * @since 2026-06-14
 */
public class ValidationError {

    /** 校验失败的字段名 */
    private String field;

    /** 校验失败的描述 */
    private String message;

    public ValidationError() {}

    public ValidationError(String field, String message) {
        this.field = field;
        this.message = message;
    }

    public String getField() { return field; }
    public void setField(String field) { this.field = field; }
    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }
}
