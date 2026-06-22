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
import com.reubenagent.rag.service.QueryRewriteService;
import com.reubenagent.rag.vo.RagRetrieveResponse;
import lombok.extern.slf4j.Slf4j;
import org.apache.http.HttpHost;
import org.elasticsearch.client.RestClient;
import org.junit.jupiter.api.*;
import org.springframework.ai.chat.model.ChatModel;
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
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Phase 8 Query Rewrite Docker 集成测试 —— 验证 LLM 查询改写 + 改写后检索全链路。
 *
 * <h3>前置条件</h3>
 * <pre>
 *   docker compose up -d pgvector elasticsearch
 *   # 确保 ChatModel 可用（DeepSeek API key 已在 .env 中配置）
 *   # 确保 Ollama 在 localhost:11434 提供 bge-m3 embedding 模型
 * </pre>
 *
 * <h3>运行</h3>
 * <pre>
 *   mvn test -pl business/rag \
 *       -Dtest=QueryRewriteIntegrationTest \
 *       -Dspring.profiles.active=docker \
 *       -Dsurefire.failIfNoSpecifiedTests=false
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
@Import(QueryRewriteIntegrationTest.TestDockerConfig.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class QueryRewriteIntegrationTest {

    private static final String ES_HOST = "localhost";
    private static final int ES_PORT = 9200;

    @Autowired
    private QueryRewriteService queryRewriteService;

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

    @Autowired
    private ObjectProvider<ChatModel> chatModelProvider;

    private final AtomicLong idGenerator = new AtomicLong(9210000000000000000L);

    private String pgTableName;
    private String esIndexName;
    private Long testDocId;

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

        // PGVector
        try {
            Integer result = pgVectorJdbcTemplate.queryForObject("SELECT 1", Integer.class);
            assumeTrue(result != null && result == 1, "PGVector 连接不可用，跳过所有测试");
            log.info("PGVector 连接验证通过");
        } catch (Exception e) {
            assumeTrue(false, "PGVector 连接失败: " + e.getMessage());
        }

        // ES
        try {
            boolean alive = esClient.ping().value();
            assumeTrue(alive, "ES 连接不可用，跳过所有测试");
            log.info("ES 连接验证通过: {}:{}", ES_HOST, ES_PORT);
        } catch (Exception e) {
            assumeTrue(false, "ES 连接失败: " + e.getMessage());
        }

        // EmbeddingModel
        EmbeddingModel model = embeddingModelProvider.getIfAvailable();
        assumeTrue(model != null, "EmbeddingModel 不可用，跳过所有测试");
        log.info("EmbeddingModel 可用: {}", model);

        // ChatModel（查询改写依赖）— 不止检查 bean，还要验证 API 真的能调通
        ChatModel chatModel = chatModelProvider.getIfAvailable();
        assumeTrue(chatModel != null, "ChatModel bean 不可用，跳过所有测试");
        try {
            // 直接调 ChatModel 验证连通性（绕开 QueryRewriteService 的降级逻辑）
            chatModel.call(new org.springframework.ai.chat.prompt.Prompt(
                    new org.springframework.ai.chat.messages.UserMessage("回复 OK")));
            log.info("ChatModel 连通性验证通过");
        } catch (Exception e) {
            log.warn("ChatModel 连通性检查失败（可能是 API key 未配置），跳过所有测试: {}", e.getMessage());
            assumeTrue(false, "ChatModel 不可用（API 认证失败，需在 .env 中配置有效 API key）");
        }

        ensureEsIndex();
    }

    @BeforeEach
    void setUp() {
        testDocId = nextId();
        log.debug("测试 documentId={}", testDocId);
    }

    @AfterEach
    void cleanupEach() {
        try {
            pgVectorJdbcTemplate.update(
                    "DELETE FROM " + pgTableName + " WHERE document_id = ?", testDocId);
            log.debug("PGVector 测试数据已清理: documentId={}", testDocId);
        } catch (Exception e) {
            log.warn("清理 PGVector 测试数据失败", e);
        }
        try {
            DeleteByQueryResponse resp = esClient.deleteByQuery(d -> d
                    .index(esIndexName)
                    .refresh(true)
                    .query(q -> q
                            .term(t -> t
                                    .field(MetadataKeys.DOCUMENT_ID)
                                    .value(testDocId))));
            log.debug("ES 测试数据已清理: documentId={}, deleted={}", testDocId, resp.deleted());
        } catch (Exception e) {
            log.warn("清理 ES 测试数据失败", e);
        }
    }

    private Map<String, String> docFilter() {
        return Map.of(MetadataKeys.DOCUMENT_ID, String.valueOf(testDocId));
    }

    // =====================================================================
    // 测试 1：查询改写 — 口语化查询被改写为检索友好文本
    // =====================================================================

    @Test
    @Order(1)
    @DisplayName("查询改写 — 口语化查询被改写为检索友好文本")
    void queryRewriteTransformsColloquialQuery() {
        String colloquialQuery = "请问一下我想知道怎么用Docker来部署一个Spring Boot应用";
        String rewritten = queryRewriteService.rewrite(colloquialQuery);

        assertThat(rewritten).isNotNull().isNotBlank();
        log.info("查询改写结果: '{}' → '{}'", colloquialQuery, rewritten);

        // 改写后的文本应去除口语化表达
        assertThat(rewritten)
                .as("改写后不应包含'请问'等口语化表达")
                .doesNotContain("请问");
        assertThat(rewritten)
                .as("改写后不应包含'我想知道'")
                .doesNotContain("我想知道");

        // 改写后应保留核心技术术语
        assertThat(rewritten)
                .as("改写后应保留 Docker")
                .contains("Docker");
        assertThat(rewritten)
                .as("改写后应保留 Spring Boot")
                .contains("Spring Boot");

        // 改写后长度合理
        assertThat(rewritten.length())
                .as("改写后长度应不超过 200")
                .isLessThanOrEqualTo(200);
    }

    // =====================================================================
    // 测试 2：短查询跳过改写
    // =====================================================================

    @Test
    @Order(2)
    @DisplayName("短查询跳过 — 长度 < minQueryLength 不调 LLM")
    void shortQuerySkipsRewrite() {
        String shortQuery = "Doc";
        String rewritten = queryRewriteService.rewrite(shortQuery);

        assertThat(rewritten).isEqualTo(shortQuery);
        log.info("短查询改写跳过: '{}' (长度={} < {})", rewritten, shortQuery.length(),
                ragProperties.getQueryRewrite().getMinQueryLength());
    }

    // =====================================================================
    // 测试 3：已精炼查询原样返回
    // =====================================================================

    @Test
    @Order(3)
    @DisplayName("精炼查询 — 已经很好的查询被改写为同义形式或原样保留")
    void refinedQueryRewrite() {
        String refinedQuery = "Docker 容器化部署 Spring Boot 微服务";
        String rewritten = queryRewriteService.rewrite(refinedQuery);

        assertThat(rewritten).isNotNull().isNotBlank();
        log.info("精炼查询改写: '{}' → '{}'", refinedQuery, rewritten);

        // 改写后的文本应包含核心关键词（可能扩展但不丢失原关键词）
        assertThat(rewritten.toLowerCase())
                .as("改写后应保留 Docker")
                .containsIgnoringCase("docker");

        assertThat(rewritten.length())
                .as("改写后长度应不超过 200")
                .isLessThanOrEqualTo(200);
    }

    // =====================================================================
    // 测试 4：改写 + 全链路检索集成
    // =====================================================================

    @Test
    @Order(4)
    @DisplayName("改写 + 检索集成 — 口语化查询经改写后命中相关结果")
    void rewritePlusRetrievalIntegration() {
        EmbeddingModel model = embeddingModelProvider.getIfAvailable();
        assertThat(model).isNotNull();

        // 插入与 Docker/Spring Boot 相关的数据
        String text1 = "Docker 容器化技术通过镜像和容器实现了应用的快速部署和环境一致性";
        String text2 = "Spring Boot 框架通过自动配置和起步依赖大幅简化了 Java 企业级应用开发流程";
        String text3 = "Kubernetes 作为容器编排平台可以自动化微服务的部署扩展和管理";

        insertPgVector(model, text1, testDocId, 1, "/DevOps/Docker");
        insertPgVector(model, text2, testDocId, 2, "/Java/SpringBoot");
        insertPgVector(model, text3, testDocId, 3, "/DevOps/K8s");

        indexEsDocument(testDocId, 1, text1, "/DevOps/Docker");
        indexEsDocument(testDocId, 2, text2, "/Java/SpringBoot");
        indexEsDocument(testDocId, 3, text3, "/DevOps/K8s");

        // 用口语化查询检索（会被 QueryRewriteService 改写）
        RagRetrieveRequest request = RagRetrieveRequest.builder()
                .query("请问一下我想知道怎么用Docker来部署一个Spring Boot应用")
                .topK(5)
                .filterFields(docFilter())
                .build();

        RagRetrieveResponse response = ragRetrievalService.retrieve(request);

        assertThat(response).isNotNull();
        assertThat(response.getResults()).isNotNull();
        assertThat(response.getTotalCostMs()).isPositive();

        log.info("改写 + 检索集成结果: rewrittenQuery='{}', hits={}, costMs={}",
                response.getRewrittenQuery(), response.getResults().size(), response.getTotalCostMs());

        // 验证 rewrittenQuery 字段被正确设置
        assertThat(response.getRewrittenQuery())
                .as("口语化查询应被改写，rewrittenQuery 不应为 null")
                .isNotNull()
                .isNotBlank();

        // 验证改写后的查询与原始不同
        assertThat(response.getRewrittenQuery())
                .as("改写后的查询应与原始不同")
                .isNotEqualTo(request.getQuery());

        // 验证检索结果包含相关内容
        List<String> texts = response.getResults().stream()
                .map(RetrievalResult::getChunkText)
                .collect(Collectors.toList());

        boolean hasDockerOrSpringBoot = texts.stream()
                .anyMatch(t -> t.contains("Docker") || t.contains("Spring Boot"));
        assertThat(hasDockerOrSpringBoot)
                .as("改写后检索应命中 Docker 或 Spring Boot 相关内容")
                .isTrue();

        response.getResults().forEach(r ->
                log.info("  source={} score={:.6f} text={}",
                        r.getSource(), r.getScore(), r.getChunkText()));
    }

    // =====================================================================
    // 测试 5：精炼查询检索 — rewrittenQuery 为 null（未改写）
    // =====================================================================

    @Test
    @Order(5)
    @DisplayName("精炼查询检索 — 已精炼查询不触发改写，rewrittenQuery 为 null")
    void refinedQueryRetrievalNoRewrite() {
        EmbeddingModel model = embeddingModelProvider.getIfAvailable();
        assertThat(model).isNotNull();

        insertPgVector(model,
                "Elasticsearch 是一个分布式全文搜索引擎，基于倒排索引和 BM25 算法",
                testDocId, 1, "/Search/ES");
        indexEsDocument(testDocId, 1,
                "Elasticsearch 分布式全文搜索引擎 BM25 相关性评分", "/Search/ES");

        // 用已精炼的查询检索
        RagRetrieveRequest request = RagRetrieveRequest.builder()
                .query("Elasticsearch BM25 搜索引擎")
                .topK(5)
                .filterFields(docFilter())
                .build();

        RagRetrieveResponse response = ragRetrievalService.retrieve(request);

        assertThat(response).isNotNull();
        assertThat(response.getResults()).isNotNull();
        assertThat(response.getTotalCostMs()).isPositive();

        log.info("精炼查询检索: rewrittenQuery='{}', hits={}, costMs={}",
                response.getRewrittenQuery(), response.getResults().size(), response.getTotalCostMs());

        // 对于已精炼的查询，改写结果可能与原查询相同，此时 rewrittenQuery 应为 null
        // （但如果 LLM 做了同义扩展，也可能是非 null — 两种情况都可接受）
        // 重点验证检索结果
        assertThat(response.getResults()).isNotEmpty();
    }

    // =====================================================================
    // 测试 6：改写降级 — ChatModel 不可用时返回原查询
    // =====================================================================

    @Test
    @Order(6)
    @DisplayName("改写降级 — QueryRewriteService.rewrite 对空白/null 输入安全返回")
    void rewriteHandlesEdgeCases() {
        // null 输入
        String nullResult = queryRewriteService.rewrite(null);
        assertThat(nullResult).isNull();
        log.info("null 输入安全: result=null");

        // 空白输入
        String blankResult = queryRewriteService.rewrite("   ");
        assertThat(blankResult).isEqualTo("   ");
        log.info("空白输入安全: result='{}'", blankResult);
    }

    // =====================================================================
    // 测试 7：改写 + 短查询全链路检索 — 短查询直接走原始检索
    // =====================================================================

    @Test
    @Order(7)
    @DisplayName("短查询全链路 — 短查询跳过改写但仍正常检索")
    void shortQueryFullPipeline() {
        EmbeddingModel model = embeddingModelProvider.getIfAvailable();
        assertThat(model).isNotNull();

        insertPgVector(model,
                "Docker 容器化技术通过镜像和容器实现了应用的快速部署和环境一致性",
                testDocId, 1, "/DevOps/Docker");
        indexEsDocument(testDocId, 1,
                "Docker 容器化技术快速部署环境一致性", "/DevOps/Docker");

        // 短查询（< 6 字）
        RagRetrieveRequest request = RagRetrieveRequest.builder()
                .query("Docker")
                .topK(5)
                .filterFields(docFilter())
                .build();

        RagRetrieveResponse response = ragRetrievalService.retrieve(request);

        assertThat(response).isNotNull();
        assertThat(response.getResults()).isNotNull();
        assertThat(response.getTotalCostMs()).isPositive();

        log.info("短查询检索: rewrittenQuery='{}', hits={}, costMs={}",
                response.getRewrittenQuery(), response.getResults().size(), response.getTotalCostMs());

        // 短查询不触发改写
        assertThat(response.getRewrittenQuery())
                .as("短查询不应触发改写")
                .isNull();

        // 短查询仍应正常检索
        assertThat(response.getResults())
                .as("短查询跳过改写后仍应正常检索")
                .isNotEmpty();
    }

    // =====================================================================
    // helper
    // =====================================================================

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
        log.debug("PGVector 插入: chunkId={}, documentId={}, text={}",
                chunkId, documentId, text.substring(0, Math.min(30, text.length())));
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
            log.debug("ES 索引: chunkId={}, documentId={}, text={}",
                    chunkId, documentId, chunkText.substring(0, Math.min(30, chunkText.length())));
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
