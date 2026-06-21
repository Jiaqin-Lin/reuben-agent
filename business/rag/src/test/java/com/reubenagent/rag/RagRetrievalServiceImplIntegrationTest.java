package com.reubenagent.rag;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.BulkRequest;
import co.elastic.clients.elasticsearch.core.DeleteByQueryResponse;
import co.elastic.clients.elasticsearch.indices.CreateIndexRequest;
import co.elastic.clients.elasticsearch.indices.ExistsRequest;
import co.elastic.clients.json.JsonData;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import co.elastic.clients.transport.rest_client.RestClientTransport;
import com.reubenagent.common.exception.BusinessException;
import com.reubenagent.common.exception.ValidationException;
import com.reubenagent.rag.config.RagProperties;
import com.reubenagent.rag.constant.MetadataKeys;
import com.reubenagent.rag.dto.RagRetrieveRequest;
import com.reubenagent.rag.enums.RagErrorCode;
import com.reubenagent.rag.model.RetrievalResult;
import com.reubenagent.rag.service.IRagRetrievalService;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * RAG 混合检索引擎 Docker 集成测试 —— 验证完整双通道 + RRF 融合链路。
 *
 * <h3>前置条件</h3>
 * <pre>
 *   docker compose up -d postgres elasticsearch
 *   # 确保 Ollama 在 localhost:11434 提供 bge-m3 模型，或配置 DeepSeek API key
 * </pre>
 *
 * <h3>运行</h3>
 * <pre>
 *   mvn test -pl business/rag -am \
 *       -Dtest=RagRetrievalServiceImplIntegrationTest \
 *       -Dspring.profiles.active=docker \
 *       -DfailIfNoTests=false
 * </pre>
 *
 * @author reuben
 * @since 2026-06-21
 */
@Slf4j
@SpringBootTest(
        classes = RagTestConfig.TestApp.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE
)
@ActiveProfiles("docker")
@Import(RagRetrievalServiceImplIntegrationTest.TestDockerConfig.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RagRetrievalServiceImplIntegrationTest {

    private static final String ES_HOST = "localhost";
    private static final int ES_PORT = 9200;

    @Autowired
    private IRagRetrievalService ragRetrievalService;

    @Autowired
    private RagProperties ragProperties;

    @Qualifier("ragPgVectorJdbcTemplate")
    @Autowired
    private JdbcTemplate pgVectorJdbcTemplate;

    @Autowired
    private ElasticsearchClient esClient;

    @Autowired
    private ObjectProvider<EmbeddingModel> embeddingModelProvider;

    private final AtomicLong idGenerator = new AtomicLong(9100000000000000000L);

    private String pgTableName;
    private String esIndexName;

    /**
     * 测试专用 ES 配置 —— 手动创建 ElasticsearchClient 连接 Docker ES。
     */
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

        // 验证 ES
        try {
            boolean alive = esClient.ping().value();
            assumeTrue(alive, "ES 连接不可用（需 docker compose up -d elasticsearch），跳过所有测试");
            log.info("ES 连接验证通过: {}:{}", ES_HOST, ES_PORT);
        } catch (Exception e) {
            assumeTrue(false, "ES 连接失败: " + e.getMessage());
        }

        // 前置清理：删除上一次运行可能残留的测试数据
        try {
            pgVectorJdbcTemplate.update(
                    "DELETE FROM " + pgTableName + " WHERE document_id >= 9100000000000000000");
            log.info("PGVector 前置清理完成");
        } catch (Exception e) {
            log.warn("PGVector 前置清理失败", e);
        }
        try {
            esClient.deleteByQuery(d -> d
                    .index(esIndexName)
                    .refresh(true)
                    .query(q -> q
                            .range(r -> r
                                    .field(MetadataKeys.DOCUMENT_ID)
                                    .gte(JsonData.of(9100000000000000000L)))));
            log.info("ES 前置清理完成");
        } catch (Exception e) {
            log.warn("ES 前置清理失败", e);
        }

        // 验证 EmbeddingModel
        EmbeddingModel model = embeddingModelProvider.getIfAvailable();
        assumeTrue(model != null, "EmbeddingModel 不可用（需 Ollama 或 DeepSeek API），跳过所有测试");
        log.info("EmbeddingModel 可用: {}", model);

        // 确保 ES 索引存在
        ensureEsIndex();
    }

    @AfterEach
    void cleanup() {
        // 清理 PGVector
        try {
            pgVectorJdbcTemplate.update(
                    "DELETE FROM " + pgTableName + " WHERE document_id >= 9100000000000000000");
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
                                    .gte(JsonData.of(9100000000000000000L)))));
            log.debug("ES 测试数据已清理: deleted={}", response.deleted());
        } catch (Exception e) {
            log.warn("清理 ES 测试数据失败", e);
        }
    }

    // =====================================================================
    // 测试 1：完整混合检索链路
    // =====================================================================

    @Test
    @Order(1)
    @DisplayName("完整混合检索 — 双通道并行 + RRF 融合返回结果")
    void fullHybridRetrieval() {
        EmbeddingModel model = embeddingModelProvider.getIfAvailable();
        assertThat(model).isNotNull();

        Long docId = nextId();

        // 在 PGVector 中插入数据
        insertPgVector(model, "Spring Boot 简化了 Java 企业级应用开发，提供了自动配置和起步依赖",
                docId, 1, "Spring Boot/Introduction");
        insertPgVector(model, "Python pandas 库提供了 DataFrame 数据结构，适合数据分析",
                docId, 2, "Python/pandas");
        insertPgVector(model, "Docker 容器化技术让应用部署更加便捷，支持 CI/CD 流水线",
                docId, 3, "DevOps/Docker");

        // 在 ES 中插入相同文档的数据（chunkId 匹配 PGVector 的）
        indexEsDocument(docId, 1, "Spring 框架与 Java 开发入门指南", "Spring Boot/Introduction");
        indexEsDocument(docId, 2, "Python pandas 数据处理与分析", "Python/pandas");
        indexEsDocument(docId, 3, "Docker 容器化部署最佳实践", "DevOps/Docker");

        // 执行检索
        RagRetrieveRequest request = RagRetrieveRequest.builder()
                .query("如何在 Java 项目中使用 Spring 框架进行开发")
                .topK(5)
                .build();

        RagRetrieveResponse response = ragRetrievalService.retrieve(request);

        // 验证响应结构
        assertThat(response).isNotNull();
        assertThat(response.getTotalCostMs()).isPositive();
        assertThat(response.getResults()).isNotEmpty();
        assertThat(response.getResults().size()).isLessThanOrEqualTo(5);

        // 验证结果字段完整性
        for (RetrievalResult r : response.getResults()) {
            assertThat(r.getChunkId()).isNotNull();
            assertThat(r.getChunkText()).isNotNull();
            assertThat(r.getScore()).isNotNull().isGreaterThan(0.0);
            assertThat(r.getSource()).isIn("vector", "keyword", "hybrid");
            assertThat(r.getDocumentId()).isNotNull();
        }

        log.info("混合检索完成: costMs={}, hits={}", response.getTotalCostMs(), response.getResults().size());
        response.getResults().forEach(r ->
                log.info("  source={} score={:.6f} chunkId={} text={}",
                        r.getSource(), r.getScore(), r.getChunkId(), r.getChunkText()));
    }

    // =====================================================================
    // 测试 2：空 query 校验
    // =====================================================================

    @Test
    @Order(2)
    @DisplayName("空 query — 抛出 ValidationException")
    void emptyQueryThrowsValidationException() {
        RagRetrieveRequest request = RagRetrieveRequest.builder()
                .query("")
                .build();

        assertThatThrownBy(() -> ragRetrievalService.retrieve(request))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("查询文本不能为空");
    }

    // =====================================================================
    // 测试 3：null query 校验
    // =====================================================================

    @Test
    @Order(3)
    @DisplayName("null query — 抛出 ValidationException")
    void nullQueryThrowsValidationException() {
        RagRetrieveRequest request = RagRetrieveRequest.builder()
                .query(null)
                .build();

        assertThatThrownBy(() -> ragRetrievalService.retrieve(request))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("查询文本不能为空");
    }

    // =====================================================================
    // 测试 4：仅空白 query
    // =====================================================================

    @Test
    @Order(4)
    @DisplayName("仅空白 query — 抛出 ValidationException")
    void blankQueryThrowsValidationException() {
        RagRetrieveRequest request = RagRetrieveRequest.builder()
                .query("   ")
                .build();

        assertThatThrownBy(() -> ragRetrievalService.retrieve(request))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("查询文本不能为空");
    }

    // =====================================================================
    // 测试 5：topK 覆盖
    // =====================================================================

    @Test
    @Order(5)
    @DisplayName("topK 覆盖 — request.topK 优先于配置文件")
    void topKOverride() {
        EmbeddingModel model = embeddingModelProvider.getIfAvailable();
        assertThat(model).isNotNull();

        Long docId = nextId();

        // 插入多条数据
        for (int i = 1; i <= 4; i++) {
            insertPgVector(model,
                    "这是一个关于机器学习和人工智能的测试段落 " + i,
                    docId, i, "AI/ML/test-" + i);
            indexEsDocument(docId, i,
                    "机器学习与人工智能测试段落 " + i, "AI/ML/test-" + i);
        }

        // 请求 topK=2
        RagRetrieveRequest request = RagRetrieveRequest.builder()
                .query("机器学习 人工智能")
                .topK(2)
                .build();

        RagRetrieveResponse response = ragRetrievalService.retrieve(request);

        assertThat(response.getResults()).isNotEmpty();
        assertThat(response.getResults().size()).isLessThanOrEqualTo(2);
        log.info("topK=2 请求返回 {} 条", response.getResults().size());
    }

    // =====================================================================
    // 测试 6：过滤检索
    // =====================================================================

    @Test
    @Order(6)
    @DisplayName("过滤检索 — filterFields 传递到双通道并生效")
    void filteredRetrieval() {
        EmbeddingModel model = embeddingModelProvider.getIfAvailable();
        assertThat(model).isNotNull();

        Long docA = nextId();
        Long docB = nextId();

        // 不同 documentId 的数据
        insertPgVector(model, "Elasticsearch 是一个分布式全文搜索引擎", docA, 1, "/Search/ES");
        insertPgVector(model, "Redis 是一个高性能内存数据库和缓存系统", docB, 1, "/Database/Redis");

        indexEsDocument(docA, 1, "Elasticsearch 分布式全文搜索引擎", "/Search/ES");
        indexEsDocument(docB, 1, "Redis 高性能内存数据库缓存", "/Database/Redis");

        // 只检索 documentId=docA
        RagRetrieveRequest request = RagRetrieveRequest.builder()
                .query("搜索引擎和数据库缓存")
                .topK(5)
                .filterFields(Map.of(MetadataKeys.DOCUMENT_ID, String.valueOf(docA)))
                .build();

        RagRetrieveResponse response = ragRetrievalService.retrieve(request);

        assertThat(response.getResults()).isNotEmpty();
        // 所有结果都应属于 docA
        assertThat(response.getResults())
                .allMatch(r -> docA.equals(r.getDocumentId()));
        log.info("过滤检索命中 {} 条，全部属于 documentId={}", response.getResults().size(), docA);
    }

    // =====================================================================
    // 测试 7：无匹配数据 — 返回空列表不崩溃
    // =====================================================================

    @Test
    @Order(7)
    @DisplayName("无匹配数据 — 返回空列表，不抛异常")
    void noMatchingDataReturnsEmptyList() {
        // 数据已被 AfterEach 清理，使用一个不可能匹配的查询
        RagRetrieveRequest request = RagRetrieveRequest.builder()
                .query("这是一个绝不可能匹配到任何数据的查询文本xyzabc123456")
                .topK(5)
                .build();

        RagRetrieveResponse response = ragRetrievalService.retrieve(request);

        assertThat(response).isNotNull();
        assertThat(response.getResults()).isNotNull();
        assertThat(response.getTotalCostMs()).isPositive();
        // 空表/无匹配时返回空列表
        log.info("无匹配检索返回 {} 条，耗时 {}ms", response.getResults().size(), response.getTotalCostMs());
    }

    // =====================================================================
    // helper
    // =====================================================================

    /** 确保 ES 测试索引存在。 */
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
            } else {
                log.info("测试 ES 索引已存在: {}", esIndexName);
            }
        } catch (IOException e) {
            log.error("创建测试 ES 索引失败: {}", esIndexName, e);
            assumeTrue(false, "创建 ES 索引失败: " + e.getMessage());
        }
    }

    /** 在 PGVector 中插入一条 embedding。 */
    private void insertPgVector(EmbeddingModel model, String text, Long documentId,
                                int chunkNo, String sectionPath) {
        float[] embedding = model.embed(text);
        String vectorLiteral = toVectorLiteral(embedding);

        long id = nextId();
        Long chunkId = nextId();
        Long parentBlockId = nextId();

        pgVectorJdbcTemplate.update(
                "INSERT INTO " + pgTableName
                        + " (id, document_id, chunk_id, chunk_no, parent_block_id,"
                        + "  section_path, chunk_text, char_count, embedding, embedding_model,"
                        + "  metadata_json, create_time, edit_time)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS vector), ?, CAST(? AS jsonb), NOW(), NOW())",
                id, documentId, chunkId, chunkNo, parentBlockId,
                sectionPath, text, text.length(),
                vectorLiteral, "bge-m3",
                "{\"documentId\": " + documentId + ", \"chunkNo\": " + chunkNo + "}"
        );
        log.debug("PGVector 插入: id={}, documentId={}, chunkNo={}", id, documentId, chunkNo);
    }

    /** 在 ES 中索引一条文档。 */
    private void indexEsDocument(Long documentId, int chunkNo, String chunkText, String sectionPath) {
        try {
            Long chunkId = nextId();
            Map<String, Object> doc = new LinkedHashMap<>();
            doc.put(MetadataKeys.DOCUMENT_ID, documentId);
            doc.put(MetadataKeys.TASK_ID, nextId());
            doc.put(MetadataKeys.PLAN_ID, nextId());
            doc.put(MetadataKeys.CHUNK_ID, chunkId);
            doc.put(MetadataKeys.CHUNK_NO, chunkNo);
            doc.put(MetadataKeys.PARENT_BLOCK_ID, nextId());
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
            log.debug("ES 索引: documentId={}, chunkId={}, text={}", documentId, chunkId, chunkText);
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
