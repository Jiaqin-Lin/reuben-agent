package com.reubenagent.document.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 文档模块统一配置属性，绑定 {@code reuben.document} 前缀。
 *
 * <p>聚合文档模块所需全部中间件配置，采用内部类组织：</p>
 * <ul>
 *   <li><b>MinIO</b> — 对象存储，存放原始文档和解析文本</li>
 *   <li><b>Kafka</b> — 消息队列（后续扩展）</li>
 *   <li><b>Elasticsearch</b> — 全文检索（后续扩展）</li>
 *   <li><b>PgVector</b> — 向量存储（后续扩展）</li>
 * </ul>
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
        /** 解析后纯文本的存储路径 */
        private String parseSuccessTextPath = "rag/parsed-text";
    }
}
