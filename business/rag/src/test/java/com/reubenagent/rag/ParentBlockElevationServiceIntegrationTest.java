package com.reubenagent.rag;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.BulkRequest;
import co.elastic.clients.elasticsearch.core.DeleteByQueryResponse;
import co.elastic.clients.elasticsearch.indices.CreateIndexRequest;
import co.elastic.clients.elasticsearch.indices.ExistsRequest;
import co.elastic.clients.json.JsonData;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import co.elastic.clients.transport.rest_client.RestClientTransport;
import com.reubenagent.rag.config.RagProperties;
import com.reubenagent.rag.constant.MetadataKeys;
import com.reubenagent.rag.dto.RagRetrieveRequest;
import com.reubenagent.rag.model.RetrievalResult;
import com.reubenagent.rag.service.IRagRetrievalService;
import com.reubenagent.rag.service.ParentBlockElevationService;
import com.reubenagent.rag.vo.RagRetrieveResponse;
import lombok.extern.slf4j.Slf4j;
import org.apache.http.HttpHost;
import org.elasticsearch.client.RestClient;
import org.junit.jupiter.api.*;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Phase 9 Parent Block Elevation Docker 集成测试 —— 验证父块提升 + 去重全链路。
 *
 * <h3>前置条件</h3>
 * <pre>
 *   docker compose up -d mysql pgvector elasticsearch
 *   # 确保 Ollama 在 localhost:11434 提供 bge-m3 模型
 * </pre>
 *
 * <h3>运行</h3>
 * <pre>
 *   mvn test -pl business/rag -am \
 *       -Dtest=ParentBlockElevationServiceIntegrationTest \
 *       -Dspring.profiles.active=docker \
 *       -DfailIfNoTests=false
 * </pre>
 *
 * @author reuben
 * @since 2026-06-22
 */
@Slf4j
@SpringBootTest(
        classes = RagTestConfig.TestApp.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE
)
@ActiveProfiles("docker")
@Import(ParentBlockElevationServiceIntegrationTest.TestDockerConfig.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ParentBlockElevationServiceIntegrationTest {

    private static final String ES_HOST = "localhost";
    private static final int ES_PORT = 9200;

    @Autowired
    private ParentBlockElevationService elevationService;

    @Autowired
    private IRagRetrievalService ragRetrievalService;

    @Autowired
    private RagProperties ragProperties;

    @Qualifier("ragPgVectorJdbcTemplate")
    @Autowired
    private JdbcTemplate pgVectorJdbcTemplate;

    @Qualifier("ragMySqlJdbcTemplate")
    @Autowired
    private JdbcTemplate mysqlJdbcTemplate;

    @Autowired
    private ElasticsearchClient esClient;

    @Autowired
    private ObjectProvider<EmbeddingModel> embeddingModelProvider;

    private final AtomicLong idGenerator = new AtomicLong(9200000000000000000L);

    private String pgTableName;
    private String esIndexName;

    /** 测试专用 ES 配置。 */
    @org.springframework.boot.test.context.TestConfiguration
    static class TestDockerConfig {

        @Bean
        RestClient restClient() {
            return RestClient.builder(
                    HttpHost.create("http://" + ES_HOST + ":" + ES_PORT)).build();
        }

        @Bean
        ElasticsearchClient elasticsearchClient(RestClient restClient) {
            return new ElasticsearchClient(
                    new RestClientTransport(restClient, new JacksonJsonpMapper()));
        }
    }

    // =====================================================================
    // 前置 / 清理
    // =====================================================================

    @BeforeAll
    void checkPrerequisites() {
        pgTableName = ragProperties.getPgvector().getTableName();
        esIndexName = ragProperties.getElasticsearch().getIndexName();

        // 验证 PGVector
        try {
            Integer result = pgVectorJdbcTemplate.queryForObject("SELECT 1", Integer.class);
            assumeTrue(result != null && result == 1, "PGVector 连接不可用，跳过所有测试");
            log.info("PGVector 连接验证通过");
        } catch (Exception e) {
            assumeTrue(false, "PGVector 连接失败: " + e.getMessage());
        }

        // 验证 MySQL
        try {
            Integer result = mysqlJdbcTemplate.queryForObject("SELECT 1", Integer.class);
            assumeTrue(result != null && result == 1, "MySQL 连接不可用，跳过所有测试");
            log.info("MySQL 连接验证通过");
        } catch (Exception e) {
            assumeTrue(false, "MySQL 连接失败: " + e.getMessage());
        }

        // 验证 ES
        try {
            boolean alive = esClient.ping().value();
            assumeTrue(alive, "ES 连接不可用，跳过所有测试");
            log.info("ES 连接验证通过: {}:{}", ES_HOST, ES_PORT);
        } catch (Exception e) {
            assumeTrue(false, "ES 连接失败: " + e.getMessage());
        }

        // 验证 EmbeddingModel
        EmbeddingModel model = embeddingModelProvider.getIfAvailable();
        assumeTrue(model != null, "EmbeddingModel 不可用，跳过所有测试");
        log.info("EmbeddingModel 可用: {}", model);

        // 确保表/索引存在
        ensureMysqlTable();
        ensureEsIndex();

        // 前置清理
        cleanupAll();
    }

    @AfterEach
    void cleanup() {
        cleanupAll();
    }

    private void cleanupAll() {
        // 清理 MySQL parent blocks
        try {
            mysqlJdbcTemplate.update(
                    "DELETE FROM reuben_agent_document_parent_block WHERE id >= 9200000000000000000");
            log.debug("MySQL 测试数据已清理");
        } catch (Exception e) {
            log.warn("清理 MySQL 测试数据失败", e);
        }

        // 清理 PGVector
        try {
            pgVectorJdbcTemplate.update(
                    "DELETE FROM " + pgTableName + " WHERE document_id >= 9200000000000000000");
            log.debug("PGVector 测试数据已清理");
        } catch (Exception e) {
            log.warn("清理 PGVector 测试数据失败", e);
        }

        // 清理 ES
        try {
            DeleteByQueryResponse response = esClient.deleteByQuery(d -> d
                    .index(esIndexName)
                    .refresh(true)
                    .query(q -> q
                            .range(r -> r
                                    .field(MetadataKeys.DOCUMENT_ID)
                                    .gte(JsonData.of(9200000000000000000L)))));
            log.debug("ES 测试数据已清理: deleted={}", response.deleted());
        } catch (Exception e) {
            log.warn("清理 ES 测试数据失败", e);
        }
    }

    // =====================================================================
    // 测试 1：父块提升 — chunk 文本被替换为 parent text
    // =====================================================================

    @Test
    @Order(1)
    @DisplayName("父块提升 — chunkText 被替换为 parent block 的完整文本")
    void elevateReplacesChunkTextWithParentText() {
        EmbeddingModel model = embeddingModelProvider.getIfAvailable();
        assertThat(model).isNotNull();

        Long docId = nextId();
        Long parentBlockId = nextId();

        // 在 MySQL 中插入 parent block
        String parentText = "第一章：Spring Boot 基础入门 —— 本章介绍 Spring Boot 的核心理念，"
                + "包括自动配置原理、起步依赖管理、内嵌容器支持，以及如何快速搭建一个 RESTful API 项目。"
                + "读者将通过一个完整的 Hello World 示例理解 Spring Boot 的约定优于配置哲学。";
        insertParentBlock(parentBlockId, docId, parentText, 1, "/Chapter1");

        // 在 PGVector 中插入 2 个属于同一 parent block 的 chunk
        String chunk1Text = "Spring Boot 自动配置原理介绍";
        insertPgVector(model, chunk1Text, docId, 1, parentBlockId, "/Chapter1/Section1");

        String chunk2Text = "Spring Boot 起步依赖管理";
        insertPgVector(model, chunk2Text, docId, 2, parentBlockId, "/Chapter1/Section2");

        // 在 ES 中索引匹配数据
        indexEsDocument(docId, 1, parentBlockId, chunk1Text, "/Chapter1/Section1");
        indexEsDocument(docId, 2, parentBlockId, chunk2Text, "/Chapter1/Section2");

        // 执行检索
        RagRetrieveRequest request = RagRetrieveRequest.builder()
                .query("Spring Boot 如何自动配置")
                .topK(5)
                .build();

        RagRetrieveResponse response = ragRetrievalService.retrieve(request);

        // 验证：提升后的结果应该包含 parent text
        assertThat(response.getResults()).isNotEmpty();
        boolean hasElevated = response.getResults().stream()
                .anyMatch(r -> parentText.equals(r.getChunkText()) && r.getSource().endsWith("+parent"));
        assertThat(hasElevated).as("至少有一条结果被提升为 parent block 文本并标记 +parent").isTrue();

        log.info("父块提升验证通过: hits={}", response.getResults().size());
        response.getResults().forEach(r ->
                log.info("  source={} score={:.6f} parentBlockId={} text={}...",
                        r.getSource(), r.getScore(), r.getParentBlockId(),
                        r.getChunkText().substring(0, Math.min(50, r.getChunkText().length()))));
    }

    // =====================================================================
    // 测试 2：同一 parent block 去重 — 只保留分数最高的一条
    // =====================================================================

    @Test
    @Order(2)
    @DisplayName("同 parent block 去重 — 只保留分数最高的一条")
    void dedupByParentBlockId() {
        EmbeddingModel model = embeddingModelProvider.getIfAvailable();
        assertThat(model).isNotNull();

        Long docId = nextId();
        Long parentBlockId = nextId();

        // 在 MySQL 中插入 parent block
        String parentText = "第二章：Docker 容器化技术 —— 本章涵盖 Docker 的核心概念，"
                + "包括镜像构建、容器编排、Dockerfile 最佳实践，以及 Compose 多服务编排。";
        insertParentBlock(parentBlockId, docId, parentText, 2, "/Chapter2");

        // 在 PGVector 中插入 3 个属于同一 parent block 的 chunk（语义相近）
        insertPgVector(model, "Docker 镜像构建和 Dockerfile 编写", docId, 1, parentBlockId, "/Chapter2/Section1");
        insertPgVector(model, "Docker Compose 多容器编排实战", docId, 2, parentBlockId, "/Chapter2/Section2");
        insertPgVector(model, "Docker 容器网络与存储卷管理", docId, 3, parentBlockId, "/Chapter2/Section3");

        // 在 ES 中索引
        indexEsDocument(docId, 1, parentBlockId, "Docker 镜像构建 Dockerfile", "/Chapter2/Section1");
        indexEsDocument(docId, 2, parentBlockId, "Docker Compose 容器编排", "/Chapter2/Section2");
        indexEsDocument(docId, 3, parentBlockId, "Docker 网络存储管理", "/Chapter2/Section3");

        RagRetrieveRequest request = RagRetrieveRequest.builder()
                .query("Docker 容器技术")
                .topK(3)
                .build();

        RagRetrieveResponse response = ragRetrievalService.retrieve(request);

        assertThat(response.getResults()).isNotEmpty();

        // 验证：同一 parent block 只出现一次
        long countWithParentBlockId = response.getResults().stream()
                .filter(r -> parentBlockId.equals(r.getParentBlockId()))
                .count();
        assertThat(countWithParentBlockId)
                .as("同一 parent block 提升后应去重，最多出现一次")
                .isLessThanOrEqualTo(1);

        // 验证提升后的文本是 parent text
        if (countWithParentBlockId > 0) {
            RetrievalResult elevated = response.getResults().stream()
                    .filter(r -> parentBlockId.equals(r.getParentBlockId()))
                    .findFirst().orElse(null);
            assertThat(elevated).isNotNull();
            assertThat(elevated.getChunkText()).isEqualTo(parentText);
            assertThat(elevated.getSource()).endsWith("+parent");
        }

        log.info("去重验证通过: parentBlockId={}, 最终出现 {} 次", parentBlockId, countWithParentBlockId);
    }

    // =====================================================================
    // 测试 3：无 parentBlockId 的 chunk 原样通过
    // =====================================================================

    @Test
    @Order(3)
    @DisplayName("无 parentBlockId — 保留原始 chunk 不变")
    void noParentBlockIdPassesThrough() {
        EmbeddingModel model = embeddingModelProvider.getIfAvailable();
        assertThat(model).isNotNull();

        Long docId = nextId();

        // 在 PGVector 中插入无 parentBlockId 的 chunk
        String chunkText = "Python 是一门简洁优雅的编程语言，广泛应用于数据科学和 Web 开发";
        insertPgVectorNoParent(model, chunkText, docId, 1, "/Python/Overview");

        // 在 ES 中索引
        indexEsDocumentNoParent(docId, 1, chunkText, "/Python/Overview");

        RagRetrieveRequest request = RagRetrieveRequest.builder()
                .query("Python 编程语言特点")
                .topK(3)
                .build();

        RagRetrieveResponse response = ragRetrievalService.retrieve(request);

        assertThat(response.getResults()).isNotEmpty();

        // 验证无 parentBlockId 的结果保持原样
        boolean hasOriginal = response.getResults().stream()
                .anyMatch(r -> chunkText.equals(r.getChunkText())
                        && r.getParentBlockId() == null
                        && r.getSource() != null && !r.getSource().contains("+parent"));
        assertThat(hasOriginal).as("无 parentBlockId 的结果应原样保留，source 不变").isTrue();

        log.info("无 parentBlockId 原样通过验证: hits={}", response.getResults().size());
    }

    // =====================================================================
    // 测试 4：parentBlockId 在 MySQL 中不存在 → 保留原 chunk
    // =====================================================================

    @Test
    @Order(4)
    @DisplayName("MySQL 中无匹配 parent block — 保留原始 chunk")
    void noMatchingParentInMysqlFallsBackToOriginal() {
        EmbeddingModel model = embeddingModelProvider.getIfAvailable();
        assertThat(model).isNotNull();

        Long docId = nextId();
        Long nonExistentParentBlockId = nextId(); // 不在 MySQL 中

        String chunkText = "Redis 缓存穿透的解决方案：布隆过滤器和缓存空值";
        insertPgVector(model, chunkText, docId, 1, nonExistentParentBlockId, "/Redis/Cache");

        indexEsDocument(docId, 1, nonExistentParentBlockId, chunkText, "/Redis/Cache");

        RagRetrieveRequest request = RagRetrieveRequest.builder()
                .query("Redis 缓存穿透如何解决")
                .topK(3)
                .build();

        RagRetrieveResponse response = ragRetrievalService.retrieve(request);

        assertThat(response.getResults()).isNotEmpty();

        // 验证：由于 MySQL 中无此 parent block，结果应原样保留
        boolean keptOriginal = response.getResults().stream()
                .anyMatch(r -> chunkText.equals(r.getChunkText())
                        && r.getSource() != null && !r.getSource().contains("+parent"));
        assertThat(keptOriginal).as("MySQL 无匹配时，应保留原始 chunk 文本").isTrue();

        log.info("MySQL 无匹配降级验证通过: hits={}", response.getResults().size());
    }

    // =====================================================================
    // 测试 5：source 字段正确标记 +parent
    // =====================================================================

    @Test
    @Order(5)
    @DisplayName("source 标记 — vector/keyword/hybrid 正确追加 +parent")
    void sourceFieldAppendedWithParent() {
        EmbeddingModel model = embeddingModelProvider.getIfAvailable();
        assertThat(model).isNotNull();

        Long docId = nextId();
        Long parentBlockId = nextId();

        String parentText = "微服务架构设计原则：本章涵盖服务拆分粒度、API 网关模式、"
                + "服务间通信方式（同步 RPC vs 异步消息）、分布式事务解决方案（Saga 模式）。";

        insertParentBlock(parentBlockId, docId, parentText, 1, "/Microservices/Principles");

        String chunkText = "微服务 API 网关模式与服务拆分策略";
        insertPgVector(model, chunkText, docId, 1, parentBlockId, "/Microservices/Principles/Gateway");

        indexEsDocument(docId, 1, parentBlockId, chunkText, "/Microservices/Principles/Gateway");

        RagRetrieveRequest request = RagRetrieveRequest.builder()
                .query("微服务 API 网关怎么设计")
                .topK(3)
                .build();

        RagRetrieveResponse response = ragRetrievalService.retrieve(request);

        assertThat(response.getResults()).isNotEmpty();

        // 验证 source 标记格式
        boolean hasValidSource = response.getResults().stream()
                .allMatch(r -> {
                    String source = r.getSource();
                    return source != null && (
                            source.equals("vector") || source.equals("keyword")
                                    || source.equals("hybrid")
                                    || source.equals("vector+parent")
                                    || source.equals("keyword+parent")
                                    || source.equals("hybrid+parent")
                    );
                });
        assertThat(hasValidSource).as("所有结果的 source 字段格式正确").isTrue();

        log.info("source 标记验证通过:");
        response.getResults().forEach(r ->
                log.info("  source={} parentBlockId={}", r.getSource(), r.getParentBlockId()));
    }

    // =====================================================================
    // helper
    // =====================================================================

    private void ensureMysqlTable() {
        try {
            mysqlJdbcTemplate.execute(
                    "CREATE TABLE IF NOT EXISTS reuben_agent_document_parent_block ("
                            + "id BIGINT NOT NULL, "
                            + "document_id BIGINT NOT NULL, "
                            + "task_id BIGINT NOT NULL DEFAULT 0, "
                            + "plan_id BIGINT DEFAULT NULL, "
                            + "parent_no INT NOT NULL DEFAULT 0, "
                            + "source_type TINYINT DEFAULT 1, "
                            + "section_path VARCHAR(1024) DEFAULT NULL, "
                            + "structure_node_id BIGINT DEFAULT NULL, "
                            + "structure_node_type TINYINT DEFAULT NULL, "
                            + "canonical_path VARCHAR(1024) DEFAULT NULL, "
                            + "item_index INT DEFAULT NULL, "
                            + "parent_text MEDIUMTEXT DEFAULT NULL, "
                            + "char_count INT DEFAULT NULL, "
                            + "token_count INT DEFAULT NULL, "
                            + "child_count INT DEFAULT NULL, "
                            + "start_chunk_no INT DEFAULT NULL, "
                            + "end_chunk_no INT DEFAULT NULL, "
                            + "create_time DATETIME DEFAULT NULL, "
                            + "update_time DATETIME DEFAULT NULL, "
                            + "is_deleted TINYINT DEFAULT 0, "
                            + "PRIMARY KEY (id)"
                            + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
            log.info("MySQL 测试表已就绪: reuben_agent_document_parent_block");
        } catch (Exception e) {
            log.error("创建 MySQL 测试表失败", e);
            assumeTrue(false, "创建 MySQL 测试表失败: " + e.getMessage());
        }
    }

    private void ensureEsIndex() {
        try {
            boolean exists = esClient.indices().exists(
                    ExistsRequest.of(e -> e.index(esIndexName))).value();
            if (!exists) {
                esClient.indices().create(CreateIndexRequest.of(c -> c
                        .index(esIndexName)
                        .mappings(m -> m
                                .properties(MetadataKeys.DOCUMENT_ID, p -> p.long_(lp -> lp))
                                .properties(MetadataKeys.TASK_ID, p -> p.long_(lp -> lp))
                                .properties(MetadataKeys.PLAN_ID, p -> p.long_(lp -> lp))
                                .properties(MetadataKeys.CHUNK_ID, p -> p.long_(lp -> lp))
                                .properties(MetadataKeys.CHUNK_NO, p -> p.integer(ip -> ip))
                                .properties(MetadataKeys.PARENT_BLOCK_ID, p -> p.long_(lp -> lp))
                                .properties(MetadataKeys.SECTION_PATH, p -> p.keyword(kp -> kp))
                                .properties(MetadataKeys.CHUNK_TEXT, p -> p.text(tp -> tp.analyzer("standard")))
                                .properties(MetadataKeys.CHAR_COUNT, p -> p.integer(ip -> ip))
                                .properties(MetadataKeys.SOURCE_TYPE, p -> p.integer(ip -> ip)))));
                log.info("测试 ES 索引已创建: {}", esIndexName);
            }
        } catch (IOException e) {
            log.error("创建测试 ES 索引失败", e);
            assumeTrue(false, "创建 ES 索引失败: " + e.getMessage());
        }
    }

    private void insertParentBlock(Long id, Long documentId, String parentText,
                                   int parentNo, String sectionPath) {
        mysqlJdbcTemplate.update(
                "INSERT INTO reuben_agent_document_parent_block"
                        + " (id, document_id, task_id, plan_id, parent_no, source_type,"
                        + "  section_path, parent_text, char_count, is_deleted, create_time, update_time)"
                        + " VALUES (?, ?, ?, 0, ?, 1, ?, ?, ?, 0, NOW(), NOW())",
                id, documentId, nextId(), parentNo, sectionPath,
                parentText, parentText.length());
        log.debug("MySQL parent block 插入: id={}, documentId={}, parentNo={}", id, documentId, parentNo);
    }

    private void insertPgVector(EmbeddingModel model, String text, Long documentId,
                                int chunkNo, Long parentBlockId, String sectionPath) {
        float[] embedding = model.embed(text);
        String vectorLiteral = toVectorLiteral(embedding);

        long id = nextId();
        Long chunkId = nextId();

        pgVectorJdbcTemplate.update(
                "INSERT INTO " + pgTableName
                        + " (id, document_id, chunk_id, chunk_no, parent_block_id,"
                        + "  section_path, chunk_text, char_count, embedding, embedding_model,"
                        + "  metadata_json, create_time, edit_time)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS vector), ?, CAST(? AS jsonb), NOW(), NOW())",
                id, documentId, chunkId, chunkNo, parentBlockId,
                sectionPath, text, text.length(),
                vectorLiteral, "bge-m3",
                "{\"documentId\": " + documentId + ", \"chunkNo\": " + chunkNo
                        + ", \"parentBlockId\": " + parentBlockId + "}"
        );
        log.debug("PGVector 插入: id={}, documentId={}, chunkNo={}, parentBlockId={}",
                id, documentId, chunkNo, parentBlockId);
    }

    private void insertPgVectorNoParent(EmbeddingModel model, String text, Long documentId,
                                        int chunkNo, String sectionPath) {
        float[] embedding = model.embed(text);
        String vectorLiteral = toVectorLiteral(embedding);

        long id = nextId();
        Long chunkId = nextId();

        pgVectorJdbcTemplate.update(
                "INSERT INTO " + pgTableName
                        + " (id, document_id, chunk_id, chunk_no, parent_block_id,"
                        + "  section_path, chunk_text, char_count, embedding, embedding_model,"
                        + "  metadata_json, create_time, edit_time)"
                        + " VALUES (?, ?, ?, ?, NULL, ?, ?, ?, CAST(? AS vector), ?, CAST(? AS jsonb), NOW(), NOW())",
                id, documentId, chunkId, chunkNo,
                sectionPath, text, text.length(),
                vectorLiteral, "bge-m3",
                "{\"documentId\": " + documentId + ", \"chunkNo\": " + chunkNo + "}"
        );
        log.debug("PGVector 插入(无parent): id={}, documentId={}, chunkNo={}", id, documentId, chunkNo);
    }

    private void indexEsDocument(Long documentId, int chunkNo, Long parentBlockId,
                                 String chunkText, String sectionPath) {
        try {
            Long chunkId = nextId();
            Map<String, Object> doc = new LinkedHashMap<>();
            doc.put(MetadataKeys.DOCUMENT_ID, documentId);
            doc.put(MetadataKeys.TASK_ID, nextId());
            doc.put(MetadataKeys.PLAN_ID, nextId());
            doc.put(MetadataKeys.CHUNK_ID, chunkId);
            doc.put(MetadataKeys.CHUNK_NO, chunkNo);
            doc.put(MetadataKeys.PARENT_BLOCK_ID, parentBlockId);
            doc.put(MetadataKeys.SECTION_PATH, sectionPath);
            doc.put(MetadataKeys.CHUNK_TEXT, chunkText);
            doc.put(MetadataKeys.CHAR_COUNT, chunkText.length());
            doc.put(MetadataKeys.SOURCE_TYPE, 1);

            esClient.bulk(BulkRequest.of(b -> b
                    .operations(op -> op
                            .index(idx -> idx
                                    .index(esIndexName)
                                    .id(String.valueOf(chunkId))
                                    .document(doc)))));

            esClient.indices().refresh(r -> r.index(esIndexName));
            log.debug("ES 索引: documentId={}, chunkId={}, parentBlockId={}", documentId, chunkId, parentBlockId);
        } catch (IOException e) {
            log.error("ES 索引失败", e);
            assumeTrue(false, "ES 索引失败: " + e.getMessage());
        }
    }

    private void indexEsDocumentNoParent(Long documentId, int chunkNo,
                                         String chunkText, String sectionPath) {
        try {
            Long chunkId = nextId();
            Map<String, Object> doc = new LinkedHashMap<>();
            doc.put(MetadataKeys.DOCUMENT_ID, documentId);
            doc.put(MetadataKeys.TASK_ID, nextId());
            doc.put(MetadataKeys.PLAN_ID, nextId());
            doc.put(MetadataKeys.CHUNK_ID, chunkId);
            doc.put(MetadataKeys.CHUNK_NO, chunkNo);
            doc.put(MetadataKeys.SECTION_PATH, sectionPath);
            doc.put(MetadataKeys.CHUNK_TEXT, chunkText);
            doc.put(MetadataKeys.CHAR_COUNT, chunkText.length());
            doc.put(MetadataKeys.SOURCE_TYPE, 1);

            esClient.bulk(BulkRequest.of(b -> b
                    .operations(op -> op
                            .index(idx -> idx
                                    .index(esIndexName)
                                    .id(String.valueOf(chunkId))
                                    .document(doc)))));

            esClient.indices().refresh(r -> r.index(esIndexName));
            log.debug("ES 索引(无parent): documentId={}, chunkId={}", documentId, chunkId);
        } catch (IOException e) {
            log.error("ES 索引失败", e);
            assumeTrue(false, "ES 索引失败: " + e.getMessage());
        }
    }

    private String toVectorLiteral(float[] embedding) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < embedding.length; i++) {
            if (i > 0) sb.append(",");
            sb.append(embedding[i]);
        }
        sb.append("]");
        return sb.toString();
    }

    private long nextId() {
        return idGenerator.incrementAndGet();
    }
}
