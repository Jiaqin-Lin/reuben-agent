package com.reubenagent.common.enums;

import lombok.Getter;

/**
 * 文档管理模块错误码 —— 区间 20001 ~ 20099。
 *
 * <p>配合 {@link com.reubenagent.common.exception.DocumentException} 使用，
 * 让异常同时携带 code + 枚举类型，方便日志、监控和上游分类处理。</p>
 *
 * @author reuben
 * @since 2026-06-14
 */
public enum DocumentManageCode {

    EMPTY_FILE(20001, "文件内容为空"),
    EMPTY_ORIGINAL_FILE_NAME(20002, "原始文件名为空"),
    UNSUPPORTED_FILE_TYPE(20003, "不支持当前文件类型"),
    READ_FILE_FAIL(20004, "读取文件失败"),
    MINIO_UPLOAD_FAIL(20005, "minio 上传失败")
    ;

    @Getter
    private final Integer code;
    private final String msg;

    private DocumentManageCode(Integer code, String msg) {
        this.code = code;
        this.msg = msg;
    }

    public String getMsg() {
        return msg;
    }
}
