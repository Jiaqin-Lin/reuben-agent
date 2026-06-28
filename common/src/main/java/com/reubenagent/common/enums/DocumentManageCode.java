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

    // ========== 知识域错误码（50001–50099）==========
    SCOPE_NOT_FOUND(50001, "知识范围不存在"),
    SCOPE_CODE_DUPLICATE(50002, "知识范围编码重复"),
    TOPIC_NOT_FOUND(50003, "知识主题不存在"),
    TOPIC_CODE_DUPLICATE(50004, "知识主题编码重复"),
    RELATION_ALREADY_EXISTS(50005, "主题-文档关联已存在"),
    RELATION_NOT_FOUND(50006, "主题-文档关联不存在"),
    ROUTE_FAILED(50007, "知识路由失败"),
    ROUTE_LOW_CONFIDENCE(50008, "知识路由置信度过低"),
    ES_INDEX_FAILED(50009, "ES 索引操作失败"),
    ES_INDEX_REFRESH_FAILED(50010, "ES 索引刷新失败"),
    PROFILE_GENERATE_FAILED(50011, "文档画像生成失败"),
    NEO4J_QUERY_FAILED(50012, "Neo4j 图查询失败"),
    NEO4J_PROJECTION_FAILED(50013, "Neo4j 图投影失败"),
    NEO4J_CONNECTION_FAILED(50014, "Neo4j 连接失败"),
    GRAPH_NOT_AVAILABLE(50015, "图数据不可用"),
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
