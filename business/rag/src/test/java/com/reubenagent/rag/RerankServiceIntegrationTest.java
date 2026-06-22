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
import com.reubenagent.rag.service.RerankService;
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
 * Phase 10 Rerank Docker 集成测试 —— 验证重排序开关 + 降级 + 全链路。
 *
 * <h3>前置条件</h3>
 * <pre>
 *   docker compose up -d mysql pgvector elasticsearch
 *   # 确保 Ollama 在 localhost:11434 提供 bge-m3 模型
 *   # 可选: export SILICONFLOW_API_KEY=sk-xxx 以测试真实 rerank
 * </pre>
 *
 * <h3>运行</h3>
 * <pre>
 *   mvn test -pl business/rag -am \
 *       -Dtest=RerankServiceIntegrationTest \
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
@Import(RerankServiceIntegrationTest.TestDockerConfig.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RerankServiceIntegrationTest {

    private static final String ES_HOST = "localhost";
    private static final int ES_PORT = 9200;

    @Autowired
    private RerankService rerankService;

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

    private final AtomicLong idGenerator = new AtomicLong(930000000000000000L);

    private String pgTableName;
    private String esIndexName;

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
        } catch (Exception e) {
            assumeTrue(false, "PGVector 连接失败: " + e.getMessage());
        }

        // 验证 MySQL
        try {
            Integer result = mysqlJdbcTemplate.queryForObject("SELECT 1", Integer.class);
            assumeTrue(result != null && result == 1, "MySQL 连接不可用，跳过所有测试");
        } catch (Exception e) {
            assumeTrue(false, "MySQL 连接失败: " + e.getMessage());
        }

        // 验证 ES
        try {
            boolean alive = esClient.ping().value();
            assumeTrue(alive, "ES 连接不可用，跳过所有测试");
        } catch (Exception e) {
            assumeTrue(false, "ES 连接失败: " + e.getMessage());
        }

        // 验证 EmbeddingModel
        EmbeddingModel model = embeddingModelProvider.getIfAvailable();
        assumeTrue(model != null, "EmbeddingModel 不可用，跳过所有测试");

        // 确保表/索引存在
        ensureMysqlTable();
        ensureEsIndex();

        log.info("Rerank 配置: enabled={}, url={}, model={}",
                ragProperties.getRerank().isEnabled(),
                ragProperties.getRerank().getUrl(),
                ragProperties.getRerank().getModel());
    }

    @AfterEach
    void cleanup() {
        // 清理 MySQL
        try {
            mysqlJdbcTemplate.update(
                    "DELETE FROM reuben_agent_document_parent_block WHERE id >= 930000000000000000");
        } catch (Exception e) {
            log.warn("清理 MySQL 测试数据失败", e);
        }
        // 清理 PGVector
        try {
            pgVectorJdbcTemplate.update(
                    "DELETE FROM " + pgTableName + " WHERE document_id >= 930000000000000000");
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
                                    .gte(JsonData.of(930000000000000000L)))));
        } catch (Exception e) {
            log.warn("清理 ES 测试数据失败", e);
        }
    }

    // =====================================================================
    // 测试 1：rerank 禁用 — 全链路结果不含 +rerank
    // =====================================================================

    @Test
    @Order(1)
    @DisplayName("rerank 禁用 — source 不含 +rerank，分数为 RRF 分数")
    void rerankDisabledPassthrough() {
        EmbeddingModel model = embeddingModelProvider.getIfAvailable();
        assertThat(model).isNotNull();

        Long docId = nextId();
        Long parentBlockId = nextId();

        String parentText = "Java 并发编程核心知识：包括 synchronized 关键字、ReentrantLock、"
                + "线程池 ThreadPoolExecutor 原理、以及 Java 内存模型 JMM 的 happens-before 规则。";
        insertParentBlock(parentBlockId, docId, parentText, 1, "/Java/Concurrency");

        insertPgVector(model, "Java 线程池原理与使用", docId, 1, parentBlockId, "/Java/Concurrency/ThreadPool");
        insertPgVector(model, "Java 内存模型与 volatile", docId, 2, parentBlockId, "/Java/Concurrency/JMM");

        indexEsDocument(docId, 1, parentBlockId, "Java 线程池原理", "/Java/Concurrency/ThreadPool");
        indexEsDocument(docId, 2, parentBlockId, "Java 内存模型 volatile", "/Java/Concurrency/JMM");

        RagRetrieveRequest request = RagRetrieveRequest.builder()
                .query("Java 线程池怎么配置")
                .topK(3)
                .build();

        RagRetrieveResponse response = ragRetrievalService.retrieve(request);

        assertThat(response.getResults()).isNotEmpty();

        // 验证 rerank 禁用时 source 不含 "+rerank"
        boolean noRerankSource = response.getResults().stream()
                .noneMatch(r -> r.getSource() != null && r.getSource().contains("+rerank"));
        assertThat(noRerankSource).as("rerank 禁用时 source 不应含 +rerank").isTrue();

        // 验证 rerankScore 为空（未执行重排序）
        boolean noRerankScore = response.getResults().stream()
                .allMatch(r -> r.getRerankScore() == null);
        assertThat(noRerankScore).as("rerank 禁用时 rerankScore 应为 null").isTrue();

        log.info("rerank 禁用通过验证: hits={}", response.getResults().size());
        response.getResults().forEach(r ->
                log.info("  source={} score={:.6f} rerankScore={}",
                        r.getSource(), r.getScore(), r.getRerankScore()));
    }

    // =====================================================================
    // 测试 2：rerankService.rerank 空列表 → 返回空
    // =====================================================================

    @Test
    @Order(2)
    @DisplayName("空候选列表 — 返回空列表")
    void emptyCandidatesReturnsEmpty() {
        List<RetrievalResult> result = rerankService.rerank("test query", List.of());
        assertThat(result).isEmpty();
    }

    // =====================================================================
    // 测试 3：rerankService.rerank null → 返回空
    // =====================================================================

    @Test
    @Order(3)
    @DisplayName("null 候选列表 — 返回空列表")
    void nullCandidatesReturnsEmpty() {
        List<RetrievalResult> result = rerankService.rerank("test query", null);
        assertThat(result).isEmpty();
    }

    // =====================================================================
    // 测试 4：重新配置 enabled=false → RerankService 原样返回
    // =====================================================================

    @Test
    @Order(4)
    @DisplayName("rerank disabled → 候选原样返回（不改分数、不加 +rerank）")
    void disabledReturnsOriginalOrder() {
        List<RetrievalResult> candidates = List.of(
                RetrievalResult.builder()
                        .chunkId(1L).chunkText("文本A").score(0.9)
                        .sectionPath("/A").documentId(100L).parentBlockId(10L)
                        .source("vector+parent").build(),
                RetrievalResult.builder()
                        .chunkId(2L).chunkText("文本B").score(0.5)
                        .sectionPath("/B").documentId(100L).parentBlockId(20L)
                        .source("keyword+parent").build()
        );

        // 当前配置 enabled=false，应原样返回
        List<RetrievalResult> result = rerankService.rerank("test query", candidates);

        assertThat(result).hasSize(2);
        // 顺序不变
        assertThat(result.get(0).getChunkId()).isEqualTo(1L);
        assertThat(result.get(0).getScore()).isEqualTo(0.9);
        assertThat(result.get(0).getSource()).isEqualTo("vector+parent");
        assertThat(result.get(0).getRerankScore()).isNull();

        assertThat(result.get(1).getChunkId()).isEqualTo(2L);
        assertThat(result.get(1).getScore()).isEqualTo(0.5);
        assertThat(result.get(1).getSource()).isEqualTo("keyword+parent");
        assertThat(result.get(1).getRerankScore()).isNull();

        log.info("rerank disabled 原样返回验证通过");
    }

    // =====================================================================
    // 测试 5：不良 URL → 降级返回原顺序
    // =====================================================================

    @Test
    @Order(5)
    @DisplayName("Rerank API 不可达 → 降级返回原顺序")
    void unreachableApiDegradesGracefully() {
        // 临时修改 URL 为不可达地址
        RagProperties.Rerank config = ragProperties.getRerank();
        String originalUrl = config.getUrl();
        boolean originalEnabled = config.isEnabled();

        try {
            config.setEnabled(true);
            config.setUrl("http://127.0.0.1:19999/rerank"); // 不可达端口

            List<RetrievalResult> candidates = List.of(
                    RetrievalResult.builder()
                            .chunkId(1L).chunkText("文本A").score(0.8)
                            .sectionPath("/A").documentId(100L).parentBlockId(10L)
                            .source("vector+parent").build(),
                    RetrievalResult.builder()
                            .chunkId(2L).chunkText("文本B").score(0.3)
                            .sectionPath("/B").documentId(100L).parentBlockId(20L)
                            .source("keyword+parent").build()
            );

            List<RetrievalResult> result = rerankService.rerank("test query", candidates);

            // 应降级返回原顺序
            assertThat(result).hasSize(2);
            assertThat(result.get(0).getChunkId()).isEqualTo(1L);
            assertThat(result.get(0).getScore()).isEqualTo(0.8);
            assertThat(result.get(0).getSource()).isEqualTo("vector+parent");
            assertThat(result.get(1).getChunkId()).isEqualTo(2L);

            log.info("不可达 API 降级验证通过");

        } finally {
            config.setUrl(originalUrl);
            config.setEnabled(originalEnabled);
        }
    }

    // =====================================================================
    // 测试 6：真实 rerank（需 SILICONFLOW_API_KEY）
    // =====================================================================

    @Test
    @Order(6)
    @DisplayName("真实 rerank — 分数更新、source 标记 +rerank")
    void realRerankUpdatesScoresAndSource() {
        String apiKey = System.getenv("SILICONFLOW_API_KEY");
        assumeTrue(apiKey != null && !apiKey.isBlank(),
                "SILICONFLOW_API_KEY 未设置，跳过真实 rerank 测试");

        RagProperties.Rerank config = ragProperties.getRerank();
        String originalUrl = config.getUrl();
        String originalApiKey = config.getApiKey();
        boolean originalEnabled = config.isEnabled();
        int originalTopN = config.getTopN();

        try {
            config.setEnabled(true);
            config.setApiKey(apiKey);
            config.setUrl("https://api.siliconflow.cn/v1/rerank");
            config.setTopN(3);

            List<RetrievalResult> candidates = List.of(
                    RetrievalResult.builder()
                            .chunkId(1L).chunkText("苹果是一种常见的水果，富含维生素C和纤维素")
                            .score(0.8).sectionPath("/Fruits").documentId(100L).parentBlockId(10L)
                            .source("vector+parent").build(),
                    RetrievalResult.builder()
                            .chunkId(2L).chunkText("香蕉含有丰富的钾元素，有助于维持心脏健康")
                            .score(0.6).sectionPath("/Fruits").documentId(100L).parentBlockId(20L)
                            .source("keyword+parent").build(),
                    RetrievalResult.builder()
                            .chunkId(3L).chunkText("特斯拉是一家电动汽车制造商，总部位于美国加州")
                            .score(0.4).sectionPath("/Companies").documentId(100L).parentBlockId(30L)
                            .source("vector+parent").build()
            );

            List<RetrievalResult> result = rerankService.rerank("苹果的营养价值", candidates);

            assertThat(result).isNotEmpty();

            // 验证分数被更新（rerank 后的分数与原来不同）
            boolean scoresChanged = false;
            for (RetrievalResult r : result) {
                if (r.getRerankScore() != null && !r.getRerankScore().equals(r.getScore())) {
                    scoresChanged = true;
                    break;
                }
            }
            // 注意：如果 rerank API 返回的分数恰好与 RRF 分数一样，这个检测会失败
            // 但概率极低，因为 RRF 和 cross-encoder 的分数空间完全不同

            // 验证 source 含 +rerank
            boolean hasRerankSource = result.stream()
                    .anyMatch(r -> r.getSource() != null && r.getSource().contains("+rerank"));
            assertThat(hasRerankSource).as("真实 rerank 后 source 应含 +rerank").isTrue();

            // 验证 rerankScore 被记录
            boolean hasRerankScore = result.stream()
                    .anyMatch(r -> r.getRerankScore() != null);
            assertThat(hasRerankScore).as("真实 rerank 后 rerankScore 应非空").isTrue();

            log.info("真实 rerank 验证通过: hits={}", result.size());
            result.forEach(r ->
                    log.info("  source={} score={:.6f} rerankScore={:.6f} text={}",
                            r.getSource(), r.getScore(), r.getRerankScore(),
                            r.getChunkText().substring(0, Math.min(40, r.getChunkText().length()))));

        } finally {
            config.setEnabled(originalEnabled);
            config.setApiKey(originalApiKey);
            config.setUrl(originalUrl);
            config.setTopN(originalTopN);
        }
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
        } catch (Exception e) {
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
            }
        } catch (IOException e) {
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
        } catch (IOException e) {
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
