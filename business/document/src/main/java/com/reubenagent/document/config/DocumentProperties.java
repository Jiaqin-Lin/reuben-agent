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

    private StructureParsing structureParsing = new StructureParsing();

    private Strategy strategy = new Strategy();

    private Pgvector pgvector = new Pgvector();

    private Embedding embedding = new Embedding();

    private Elasticsearch elasticsearch = new Elasticsearch();

    // ============ 内部类 — 按中间件/功能拆分 ============

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

    /**
     * 文档结构解析配置，绑定 {@code reuben.document.structure-parsing}。
     *
     * <p>控制 LLM 歧义消解（Stage 2）的行为参数。</p>
     */
    @Data
    public static class StructureParsing {
        /** LLM 歧义消解总开关，关闭时跳过 Stage 2 */
        private Boolean llmDisambiguationEnabled = true;
        /** 单次 LLM 调用最多处理的模糊信号数 */
        private Integer maxAmbiguousSignalsPerCall = 8;
        /** 每个模糊行前后各取多少行作为 LLM 上下文 */
        private Integer contextWindowLines = 4;
        /** 模糊信号置信度下限（低于此值不送 LLM，直接保留规则引擎分类） */
        private Double ambiguityConfidenceFloor = 0.45;
        /** 模糊信号置信度上限（高于此值不送 LLM，直接保留规则引擎分类） */
        private Double ambiguityConfidenceCeil = 0.80;
    }

    /**
     * 策略推荐配置，绑定 {@code reuben.document.strategy}。
     */
    @Data
    public static class Strategy {
        /** 触发递归切分的字符数阈值（总字符数或最长段落字符数超过此值时推荐） */
        private Integer recursiveMaxChars = 3000;
        /** 触发语义切分的最小字符数 */
        private Integer semanticMinChars = 1000;
        /** 低质量文档是否推荐 LLM 切分 */
        private Boolean recommendLlmWhenLowQuality = false;
        /** 递归切分重叠字符数 */
        private Integer recursiveOverlapChars = 200;
        /** 语义切分相似度阈值（0~1，越低越容易切分） */
        private Double semanticSimilarityThreshold = 0.7;
        /** LLM 切分单次最大输入字符数 */
        private Integer llmMaxInputChars = 8000;
    }

    /**
     * PGVector 配置，绑定 {@code reuben.document.pgvector}。
     */
    @Data
    public static class Pgvector {
        /** 主机地址 */
        private String host = "127.0.0.1";
        /** 端口 */
        private int port = 5432;
        /** 数据库名 */
        private String database = "reuben_agent_pgvector";
        /** 用户名 */
        private String username = "reuben";
        /** 密码 */
        private String password = "reuben123";
        /** 连接池名称 */
        private String poolName = "DocumentPgVectorPool";
        /** 连接池最大连接数 */
        private int maximumPoolSize = 5;
        /** 连接池最小空闲连接数 */
        private int minimumIdle = 1;
        /** 嵌入表名 */
        private String tableName = "public.reuben_agent_document_embedding";
        /** 向量化批量大小 */
        private Integer batchSize = 10;
        /** embedding 模型名称 */
        private String embeddingModel = "bge-m3";
    }

    /**
     * Embedding 通用配置，绑定 {@code reuben.document.embedding}。
     */
    @Data
    public static class Embedding {
        /** 向量维度 (bge-m3 = 1024) */
        private Integer dimension = 1024;
    }

    /**
     * Elasticsearch 关键字搜索配置，绑定 {@code reuben.document.elasticsearch}。
     */
    @Data
    public static class Elasticsearch {
        /** 索引名称 */
        private String indexName = "reuben_document_chunk";
    }
}
