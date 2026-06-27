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
public enum DocumentManageCode implements BaseEnum {

    EMPTY_FILE(20001, "文件内容为空"),
    EMPTY_ORIGINAL_FILE_NAME(20002, "原始文件名为空"),
    UNSUPPORTED_FILE_TYPE(20003, "不支持当前文件类型"),
    READ_FILE_FAIL(20004, "读取文件失败"),
    MINIO_UPLOAD_FAIL(20005, "minio 上传失败"),
    MINIO_DOWNLOAD_FAIL(20006, "minio 下载失败"),
    VECTORIZE_FAILED(20007, "向量化失败"),
    INDEX_BUILD_FAILED(20008, "索引构建失败"),
    CHUNK_EXECUTION_FAILED(20009, "切块执行失败"),
    KAFKA_SEND_FAILED(20010, "Kafka消息发送失败"),
    PLAN_NOT_FOUND(20011, "策略方案不存在"),
    PLAN_STATUS_INVALID(20012, "策略方案状态不允许操作"),
    DOCUMENT_NOT_FOUND(20013, "文档不存在"),
    DOCUMENT_HAS_ACTIVE_TASK(20014, "文档存在进行中的任务，无法删除"),
    OPERATION_NOT_ALLOWED(20015, "当前状态不允许此操作"),
    CHUNK_NOT_FOUND(20016, "切块不存在"),
    TASK_NOT_FOUND(20017, "任务不存在"),
    ;

    @Getter
    private final Integer code;
    private final String msg;

    private DocumentManageCode(Integer code, String msg) {
        this.code = code;
        this.msg = msg;
    }

    @Override
    public String getMsg() {
        return msg;
    }

    public static DocumentManageCode getFromCode(Integer code) {
        return EnumUtils.getFromCode(DocumentManageCode.class, code);
    }
}
