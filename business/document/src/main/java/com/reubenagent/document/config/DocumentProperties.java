package com.reubenagent.document.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 文档模块统一配置属性，绑定 {@code reuben.document} 前缀。
 *
 * <p>当前仅包含 MinIO 对象存储配置，Kafka / Elasticsearch / PgVector 等为后续扩展预留。</p>
 *
 * @author reuben
 * @since 2026-06-14
 */
@Data
@ConfigurationProperties(prefix = "reuben.document")
public class DocumentProperties {

    private Minio minio = new Minio();

    // ============ 内部类 — 按中间件拆分 ============

    @Data
    public static class Minio {
        /** MinIO 服务地址 */
        private String endpoint = "http://127.0.0.1:9000";
        /** 访问密钥 */
        private String accessKey = "minioadmin";
        /** 秘密密钥 */
        private String secretKey = "minioadmin";
        /** 存储桶名称 */
        private String bucketName = "reuben-agent-document";
        /** 对象前缀路径 */
        private String objectPrefix = "rag/document";
        /** 解析后纯文本的存储路径（TODO: 尚未被代码引用，待接入异步解析管线） */
        private String parseSuccessTextPath = "rag/parsed-text";
    }
}
