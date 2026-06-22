package com.reubenagent.rag.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * RAG 检索模块统一配置属性，绑定 {@code reuben.rag} 前缀。
 *
 * <p>包含检索参数、PGVector 只读连接、ES 索引、Embedding 维度等配置。</p>
 *
 * @author reuben
 * @since 2026-06-21
 */
@Data
@ConfigurationProperties(prefix = "reuben.rag")
public class RagProperties {

    private Retrieval retrieval = new Retrieval();

    private Pgvector pgvector = new Pgvector();

    private Elasticsearch elasticsearch = new Elasticsearch();

    private Embedding embedding = new Embedding();

    // ============ 内部类 — 按功能拆分 ============

    /**
     * 检索参数配置，绑定 {@code reuben.rag.retrieval}。
     */
    @Data
    public static class Retrieval {
        /** 向量通道召回数 */
        private int vectorTopK = 8;
        /** 关键词通道召回数 */
        private int keywordTopK = 8;
        /** 融合后最终返回数 */
        private int finalTopK = 5;
        /** RRF 公式 K 值 */
        private int rrfK = 60;
        /** 单通道超时时间（ms） */
        private long channelTimeoutMs = 5000;
        /** 向量通道最低余弦相似度阈值（低于此值的结果被丢弃） */
        private double minVectorSimilarity = 0.45;
        /** 关键词通道相对分数下限（相对于该通道最高分的比例，0~1） */
        private double keywordRelativeScoreFloor = 0.35;
    }

    /**
     * PGVector 只读连接配置，绑定 {@code reuben.rag.pgvector}。
     *
     * <p>独立于 document 模块的 DataSource，使用独立的连接池名。</p>
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
        /** 嵌入表名 */
        private String tableName = "public.reuben_agent_document_embedding";
        /** 连接池名称（与 document 模块的 DocumentPgVectorPool 隔离） */
        private String poolName = "RagPgVectorPool";
    }

    /**
     * Elasticsearch 关键字搜索配置，绑定 {@code reuben.rag.elasticsearch}。
     */
    @Data
    public static class Elasticsearch {
        /** 索引名称（与 document 模块写入同一索引） */
        private String indexName = "reuben_document_chunk";
    }

    /**
     * Embedding 配置，绑定 {@code reuben.rag.embedding}。
     */
    @Data
    public static class Embedding {
        /** 模型名称 */
        private String model = "bge-m3";
        /** 向量维度 */
        private int dimension = 1024;
    }
}
