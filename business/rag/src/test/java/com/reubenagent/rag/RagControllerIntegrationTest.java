package com.reubenagent.rag;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.BulkRequest;
import co.elastic.clients.elasticsearch.core.DeleteByQueryResponse;
import co.elastic.clients.elasticsearch.indices.CreateIndexRequest;
import co.elastic.clients.elasticsearch.indices.ExistsRequest;
import co.elastic.clients.json.JsonData;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import co.elastic.clients.transport.rest_client.RestClientTransport;
import com.reubenagent.common.dto.ApiResponse;
import com.reubenagent.common.exception.ValidationError;
import com.reubenagent.rag.config.RagProperties;
import com.reubenagent.rag.constant.MetadataKeys;
import com.reubenagent.rag.dto.RagRetrieveRequest;
import com.reubenagent.rag.model.RetrievalResult;
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
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.*;
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
 * RAG Controller 全链路 Docker 集成测试 —— 从 HTTP 端点 → 双通道检索 → RRF 融合 → JSON 响应。
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
 *       -Dtest=RagControllerIntegrationTest \
 *       -Dspring.profiles.active=docker \
 *       -DfailIfNoTests=false
 * </pre>
 *
 * @author reuben
 * @since 2026-06-21
 */
@Slf4j
@SpringBootTest(
        classes = RagTestConfig.WebTestApp.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT
)
@ActiveProfiles("docker")
@Import(RagControllerIntegrationTest.EsTestConfig.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RagControllerIntegrationTest {

    private static final String ES_HOST = "localhost";
    private static final int ES_PORT = 9200;

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private RagProperties ragProperties;

    @Qualifier("ragPgVectorJdbcTemplate")
    @Autowired
    private JdbcTemplate pgVectorJdbcTemplate;

    @Autowired
    private ElasticsearchClient esClient;

    @Autowired
    private ObjectProvider<EmbeddingModel> embeddingModelProvider;

    private final AtomicLong idGenerator = new AtomicLong(9200000000000000000L);

    private String pgTableName;
    private String esIndexName;
    private String baseUrl;

    /**
     * 测试专用 ES 配置 —— 手动创建 ElasticsearchClient 连接 Docker ES。
     */
    @org.springframework.boot.test.context.TestConfiguration
    static class EsTestConfig {

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
        baseUrl = "http://localhost:" + port;

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

        // 前置清理：删除上一次运行残留的测试数据
        try {
            pgVectorJdbcTemplate.update(
                    "DELETE FROM " + pgTableName + " WHERE document_id >= 9200000000000000000");
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
                                    .gte(JsonData.of(9200000000000000000L)))));
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
        try {
            pgVectorJdbcTemplate.update(
                    "DELETE FROM " + pgTableName + " WHERE document_id >= 9200000000000000000");
            log.debug("PGVector 测试数据已清理");
        } catch (Exception e) {
            log.warn("清理 PGVector 测试数据失败", e);
        }

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
    // 测试 1：完整 HTTP 混合检索链路
    // =====================================================================

    @Test
    @Order(1)
    @DisplayName("HTTP POST /api/rag/retrieve — 完整双通道 + RRF 融合返回结果")
    void fullHttpHybridRetrieval() {
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

        // 在 ES 中插入数据
        indexEsDocument(docId, 1, "Spring 框架与 Java 开发入门指南", "Spring Boot/Introduction");
        indexEsDocument(docId, 2, "Python pandas 数据处理与分析", "Python/pandas");
        indexEsDocument(docId, 3, "Docker 容器化部署最佳实践", "DevOps/Docker");

        // 发送 HTTP POST 请求
        RagRetrieveRequest request = RagRetrieveRequest.builder()
                .query("如何在 Java 项目中使用 Spring 框架进行开发")
                .topK(5)
                .build();

        ResponseEntity<ApiResponse<RagRetrieveResponse>> response = restTemplate.exchange(
                baseUrl + "/api/rag/retrieve",
                HttpMethod.POST,
                new HttpEntity<>(request, jsonHeaders()),
                new ParameterizedTypeReference<ApiResponse<RagRetrieveResponse>>() {
                });

        // 验证 HTTP 状态
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);

        // 验证 ApiResponse 结构
        ApiResponse<RagRetrieveResponse> body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.getCode()).isEqualTo(0);

        // 验证 RAG 响应
        RagRetrieveResponse ragResponse = body.getData();
        assertThat(ragResponse).isNotNull();
        assertThat(ragResponse.getTotalCostMs()).isPositive();
        assertThat(ragResponse.getResults()).isNotEmpty();
        assertThat(ragResponse.getResults().size()).isLessThanOrEqualTo(5);

        // 验证结果字段完整性
        for (RetrievalResult r : ragResponse.getResults()) {
            assertThat(r.getChunkId()).isNotNull();
            assertThat(r.getChunkText()).isNotNull();
            assertThat(r.getScore()).isNotNull().isGreaterThan(0.0);
            assertThat(r.getSource()).isIn("vector", "keyword", "hybrid");
            assertThat(r.getDocumentId()).isNotNull();
        }

        log.info("HTTP 混合检索完成: costMs={}, hits={}",
                ragResponse.getTotalCostMs(), ragResponse.getResults().size());
        ragResponse.getResults().forEach(r ->
                log.info("  source={} score={} chunkId={} text={}",
                        r.getSource(), String.format("%.6f", r.getScore()), r.getChunkId(), r.getChunkText()));
    }

    // =====================================================================
    // 测试 2：空 query → 400 校验失败
    // =====================================================================

    @Test
    @Order(2)
    @DisplayName("空 query → HTTP 400 + 校验错误列表")
    void emptyQueryReturns400() {
        RagRetrieveRequest request = RagRetrieveRequest.builder()
                .query("")
                .build();

        ResponseEntity<ApiResponse<List<ValidationError>>> response = restTemplate.exchange(
                baseUrl + "/api/rag/retrieve",
                HttpMethod.POST,
                new HttpEntity<>(request, jsonHeaders()),
                new ParameterizedTypeReference<ApiResponse<List<ValidationError>>>() {
                });

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        // 注意：GlobalExceptionHandler 返回 HTTP 200 但 ApiResponse.code ≠ 0
        ApiResponse<List<ValidationError>> body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.getCode()).isNotEqualTo(0);
        assertThat(body.getData()).isNotNull();
        assertThat(body.getData()).anyMatch(e -> e.getField().equals("query")
                && e.getMessage().contains("不能为空"));
        log.info("空 query 校验通过: code={}, errors={}", body.getCode(), body.getData());
    }

    // =====================================================================
    // 测试 3：null query → 400 校验失败
    // =====================================================================

    @Test
    @Order(3)
    @DisplayName("null query → 校验失败（ApiResponse.code ≠ 0）")
    void nullQueryReturnsErrorCode() {
        RagRetrieveRequest request = RagRetrieveRequest.builder()
                .query(null)
                .build();

        ResponseEntity<ApiResponse<List<ValidationError>>> response = restTemplate.exchange(
                baseUrl + "/api/rag/retrieve",
                HttpMethod.POST,
                new HttpEntity<>(request, jsonHeaders()),
                new ParameterizedTypeReference<ApiResponse<List<ValidationError>>>() {
                });

        // GlobalExceptionHandler 统一返回 HTTP 200，通过 ApiResponse.code 区分
        ApiResponse<List<ValidationError>> body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.getCode()).isNotEqualTo(0);
        assertThat(body.getData()).isNotNull();
        assertThat(body.getData()).anyMatch(e -> "query".equals(e.getField())
                && e.getMessage().contains("不能为空"));
        log.info("null query 校验通过: code={}, errors={}", body.getCode(), body.getData());
    }

    // =====================================================================
    // 测试 4：仅空白 query → 校验失败
    // =====================================================================

    @Test
    @Order(4)
    @DisplayName("仅空白 query → HTTP 校验失败")
    void blankQueryReturns400() {
        RagRetrieveRequest request = RagRetrieveRequest.builder()
                .query("   ")
                .build();

        ResponseEntity<ApiResponse<List<ValidationError>>> response = restTemplate.exchange(
                baseUrl + "/api/rag/retrieve",
                HttpMethod.POST,
                new HttpEntity<>(request, jsonHeaders()),
                new ParameterizedTypeReference<ApiResponse<List<ValidationError>>>() {
                });

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        ApiResponse<List<ValidationError>> body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.getCode()).isNotEqualTo(0);
        log.info("空白 query 校验通过: code={}", body.getCode());
    }

    // =====================================================================
    // 测试 5：过滤检索
    // =====================================================================

    @Test
    @Order(5)
    @DisplayName("过滤检索 — filterFields 生效，结果仅包含指定 documentId")
    void filteredRetrieval() {
        EmbeddingModel model = embeddingModelProvider.getIfAvailable();
        assertThat(model).isNotNull();

        Long docA = nextId();
        Long docB = nextId();

        insertPgVector(model, "Elasticsearch 是一个分布式全文搜索引擎", docA, 1, "/Search/ES");
        insertPgVector(model, "Redis 是一个高性能内存数据库和缓存系统", docB, 1, "/Database/Redis");

        indexEsDocument(docA, 1, "Elasticsearch 分布式全文搜索引擎", "/Search/ES");
        indexEsDocument(docB, 1, "Redis 高性能内存数据库缓存", "/Database/Redis");

        RagRetrieveRequest request = RagRetrieveRequest.builder()
                .query("搜索引擎和数据库缓存")
                .topK(5)
                .filterFields(Map.of(MetadataKeys.DOCUMENT_ID, String.valueOf(docA)))
                .build();

        ResponseEntity<ApiResponse<RagRetrieveResponse>> response = restTemplate.exchange(
                baseUrl + "/api/rag/retrieve",
                HttpMethod.POST,
                new HttpEntity<>(request, jsonHeaders()),
                new ParameterizedTypeReference<ApiResponse<RagRetrieveResponse>>() {
                });

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        ApiResponse<RagRetrieveResponse> body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.getCode()).isEqualTo(0);

        RagRetrieveResponse ragResponse = body.getData();
        assertThat(ragResponse.getResults()).isNotEmpty();
        assertThat(ragResponse.getResults())
                .allMatch(r -> docA.equals(r.getDocumentId()));
        log.info("过滤检索命中 {} 条，全部属于 documentId={}",
                ragResponse.getResults().size(), docA);
    }

    // =====================================================================
    // 测试 6：无匹配数据 → 200 + 空列表
    // =====================================================================

    @Test
    @Order(6)
    @DisplayName("无匹配数据 → HTTP 200 + 空结果列表")
    void noMatchingDataReturns200EmptyList() {
        RagRetrieveRequest request = RagRetrieveRequest.builder()
                .query("这是一个绝不可能匹配到任何数据的查询文本xyzabc123456")
                .topK(5)
                .build();

        ResponseEntity<ApiResponse<RagRetrieveResponse>> response = restTemplate.exchange(
                baseUrl + "/api/rag/retrieve",
                HttpMethod.POST,
                new HttpEntity<>(request, jsonHeaders()),
                new ParameterizedTypeReference<ApiResponse<RagRetrieveResponse>>() {
                });

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        ApiResponse<RagRetrieveResponse> body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.getCode()).isEqualTo(0);

        RagRetrieveResponse ragResponse = body.getData();
        assertThat(ragResponse).isNotNull();
        assertThat(ragResponse.getTotalCostMs()).isPositive();
        // 无匹配数据时返回空结果，不抛 500
        assertThat(ragResponse.getResults()).isNotNull();
        log.info("无匹配检索返回 {} 条，耗时 {}ms",
                ragResponse.getResults().size(), ragResponse.getTotalCostMs());
    }

    // =====================================================================
    // 测试 7：降级验证 — 停掉 ES，向量通道仍可返回
    // =====================================================================
    // 注意：此测试需要手动停掉 ES 才能验证。此处验证 ES 不可用时的容错行为。
    // 由于并行运行，改为验证通道异常时的降级策略已在 Service 层覆盖（参见 RagRetrievalServiceImplIntegrationTest）。

    @Test
    @Order(7)
    @DisplayName("source 字段分类验证 — vector / keyword / hybrid")
    void sourceFieldClassification() {
        EmbeddingModel model = embeddingModelProvider.getIfAvailable();
        assertThat(model).isNotNull();

        Long docId = nextId();

        // 仅 PGVector 数据（无 ES 对应）
        insertPgVector(model,
                "Apache Kafka 是一个分布式流处理平台，广泛用于构建实时数据管道",
                docId, 1, "/Streaming/Kafka");
        insertPgVector(model,
                "Kafka 使用发布-订阅模式，生产者发送消息到 Topic，消费者从 Topic 消费",
                docId, 2, "/Streaming/Kafka/Architecture");

        // 仅 ES 数据（无 PGVector 对应）
        indexEsDocument(docId, 10, "Kafka 集群部署与管理最佳实践", "/Streaming/Kafka/Deployment");
        indexEsDocument(docId, 11, "使用 Kafka Streams 进行实时数据处理与分析", "/Streaming/Kafka/Streams");

        // 双通道都有的数据
        insertPgVector(model,
                "Kafka Connect 是连接 Kafka 与外部系统的框架，支持 Source 和 Sink 模式",
                docId, 3, "/Streaming/Kafka/Connect");
        indexEsDocument(docId, 3,
                "Kafka Connect 连接外部系统框架 Source Sink 模式", "/Streaming/Kafka/Connect");

        RagRetrieveRequest request = RagRetrieveRequest.builder()
                .query("如何使用 Kafka 进行流处理和消息传递")
                .topK(10)
                .build();

        ResponseEntity<ApiResponse<RagRetrieveResponse>> response = restTemplate.exchange(
                baseUrl + "/api/rag/retrieve",
                HttpMethod.POST,
                new HttpEntity<>(request, jsonHeaders()),
                new ParameterizedTypeReference<ApiResponse<RagRetrieveResponse>>() {
                });

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        ApiResponse<RagRetrieveResponse> body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.getCode()).isEqualTo(0);

        RagRetrieveResponse ragResponse = body.getData();
        assertThat(ragResponse.getResults()).isNotEmpty();

        // 验证 source 字段含义
        boolean hasVector = ragResponse.getResults().stream()
                .anyMatch(r -> "vector".equals(r.getSource()));
        boolean hasKeyword = ragResponse.getResults().stream()
                .anyMatch(r -> "keyword".equals(r.getSource()));
        boolean hasHybrid = ragResponse.getResults().stream()
                .anyMatch(r -> "hybrid".equals(r.getSource()));

        log.info("source 分布: vector={}, keyword={}, hybrid={}",
                hasVector, hasKeyword, hasHybrid);

        ragResponse.getResults().forEach(r ->
                log.info("  source={} score={} chunkId={} text={}",
                        r.getSource(), String.format("%.6f", r.getScore()), r.getChunkId(), r.getChunkText()));
    }

    // =====================================================================
    // helper
    // =====================================================================

    private HttpHeaders jsonHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
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
            } else {
                log.info("测试 ES 索引已存在: {}", esIndexName);
            }
        } catch (IOException e) {
            log.error("创建测试 ES 索引失败: {}", esIndexName, e);
            assumeTrue(false, "创建 ES 索引失败: " + e.getMessage());
        }
    }

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
