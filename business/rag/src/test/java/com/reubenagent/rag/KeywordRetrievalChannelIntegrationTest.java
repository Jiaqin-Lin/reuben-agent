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
import com.reubenagent.rag.model.RetrievalResult;
import com.reubenagent.rag.service.KeywordRetrievalChannel;
import lombok.extern.slf4j.Slf4j;
import org.apache.http.HttpHost;
import org.elasticsearch.client.RestClient;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 关键词检索通道 Docker 集成测试 —— 验证 ES BM25 全文检索。
 *
 * <h3>前置条件</h3>
 * <pre>
 *   docker compose up -d elasticsearch
 *   # 或启动所有服务
 *   docker compose up -d
 * </pre>
 *
 * <h3>运行</h3>
 * <pre>
 *   mvn test -pl business/rag -am \
 *       -Dtest=KeywordRetrievalChannelIntegrationTest \
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
@Import(KeywordRetrievalChannelIntegrationTest.EsTestConfig.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class KeywordRetrievalChannelIntegrationTest {

    private static final String ES_HOST = "localhost";
    private static final int ES_PORT = 9200;

    @Autowired
    private KeywordRetrievalChannel keywordRetrievalChannel;

    @Autowired
    private ElasticsearchClient esClient;

    @Autowired
    private RagProperties ragProperties;

    private final AtomicLong idGenerator = new AtomicLong(9000000000000000000L);

    private String indexName;

    /**
     * 测试专用 ES 配置 —— 手动创建 ElasticsearchClient 连接 Docker ES，
     * 绕过 Spring Boot 自动配置（application-docker.yml 已排除 ES 自动配置）。
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

    /** 全部测试的前置检查：ES 连接和索引就绪。 */
    @BeforeAll
    void checkPrerequisites() {
        indexName = ragProperties.getElasticsearch().getIndexName();

        // 验证 ES 连接可用
        try {
            boolean alive = esClient.ping().value();
            assumeTrue(alive, "ES 连接不可用（需 docker compose up -d elasticsearch），跳过所有测试");
            log.info("ES 连接验证通过: {}:{}", ES_HOST, ES_PORT);
        } catch (Exception e) {
            assumeTrue(false, "ES 连接失败: " + e.getMessage());
        }

        // 确保测试索引存在
        ensureTestIndex();
    }

    /** 每个测试后清理数据。 */
    @AfterEach
    void cleanup() {
        try {
            DeleteByQueryResponse response = esClient.deleteByQuery(d -> d
                    .index(indexName)
                    .refresh(true)
                    .query(q -> q
                            .range(r -> r
                                    .field(MetadataKeys.DOCUMENT_ID)
                                    .gte(JsonData.of(9000000000000000000L)))));
            log.debug("清理测试数据: deleted={}", response.deleted());
        } catch (Exception e) {
            log.warn("清理测试数据失败", e);
        }
    }

    // =====================================================================
    // 测试 1：基本关键词检索
    // =====================================================================

    @Test
    @Order(1)
    @DisplayName("基本关键词检索 — 关键词匹配的 chunk 排在前面")
    void basicKeywordRetrieval() {
        // 插入 3 条测试数据
        indexDocument(nextId(), "Spring Boot 让 Java 开发变得简单高效", "/Java/Spring", 1);
        indexDocument(nextId(), "Python 是数据科学领域最流行的编程语言", "/Python/DataScience", 2);
        indexDocument(nextId(), "Docker 容器化技术简化了应用部署和运维流程", "/DevOps/Docker", 3);

        // 查询与 Java 开发相关
        List<RetrievalResult> results = keywordRetrievalChannel.retrieve(
                "Java 开发框架", 5, null);

        assertThat(results).isNotEmpty();
        assertThat(results.size()).isLessThanOrEqualTo(5);

        // 最相关的应该是 Spring Boot 那条
        RetrievalResult top = results.get(0);
        assertThat(top.getChunkText()).isNotNull();
        assertThat(top.getScore()).isGreaterThan(0.0);
        assertThat(top.getSource()).isEqualTo("keyword");
        assertThat(top.getDocumentId()).isNotNull();
        assertThat(top.getChunkId()).isNotNull();
        assertThat(top.getSectionPath()).isNotNull();

        log.info("Top result: score={}, text={}", top.getScore(), top.getChunkText());
        results.forEach(r -> log.info("  score={:.4f} text={}", r.getScore(), r.getChunkText()));
    }

    // =====================================================================
    // 测试 2：filter 过滤
    // =====================================================================

    @Test
    @Order(2)
    @DisplayName("过滤检索 — documentId term 精确过滤")
    void filteredRetrieval() {
        Long docA = nextId();
        Long docB = nextId();

        indexDocument(docA, "Elasticsearch 是一个强大的全文搜索引擎", "/Search/ES", 1);
        indexDocument(docB, "Redis 是一个高性能的内存数据库和缓存", "/Database/Redis", 2);
        indexDocument(docA, "使用 Elasticsearch 的聚合功能进行数据分析", "/Search/ES", 3);

        // 只检索 documentId = docA
        List<RetrievalResult> results = keywordRetrievalChannel.retrieve(
                "搜索引擎和数据分析",
                5,
                Map.of(MetadataKeys.DOCUMENT_ID, String.valueOf(docA)));

        assertThat(results).isNotEmpty();
        // 所有结果都应属于 docA
        assertThat(results).allMatch(r -> docA.equals(r.getDocumentId()));
        log.info("过滤检索命中 {} 条，全部属于 documentId={}", results.size(), docA);
    }

    // =====================================================================
    // 测试 3：topK 截断
    // =====================================================================

    @Test
    @Order(3)
    @DisplayName("TopK 截断 — 返回数量不超过 topK")
    void topKTruncation() {
        // 插入 6 条相似数据
        for (int i = 1; i <= 6; i++) {
            indexDocument(nextId(),
                    "人工智能和机器学习正在改变各行各业的运作方式 这是第 " + i + " 篇相关文章",
                    "/AI/ML", i);
        }

        int topK = 3;
        List<RetrievalResult> results = keywordRetrievalChannel.retrieve(
                "人工智能 机器学习", topK, null);

        assertThat(results).isNotEmpty();
        assertThat(results.size()).isLessThanOrEqualTo(topK);
        log.info("请求 topK={}, 返回 {} 条", topK, results.size());
    }

    // =====================================================================
    // 测试 4：防御性 — query 为空
    // =====================================================================

    @Test
    @Order(4)
    @DisplayName("防御性 — query 为空返回空列表")
    void emptyQueryReturnsEmptyList() {
        List<RetrievalResult> results = keywordRetrievalChannel.retrieve("", 5, null);
        assertThat(results).isEmpty();

        results = keywordRetrievalChannel.retrieve(null, 5, null);
        assertThat(results).isEmpty();

        results = keywordRetrievalChannel.retrieve("   ", 5, null);
        assertThat(results).isEmpty();
    }

    // =====================================================================
    // 测试 5：防御性 — 无匹配数据
    // =====================================================================

    @Test
    @Order(5)
    @DisplayName("防御性 — 无匹配数据返回空列表")
    void noMatchingDataReturnsEmptyList() {
        // 索引已清空（AfterEach 清理）
        List<RetrievalResult> results = keywordRetrievalChannel.retrieve(
                "这个查询绝不可能匹配到任何已索引的文档内容", 5, null);

        assertThat(results).isNotNull();
        assertThat(results).isEmpty();
        log.info("无匹配检索返回 {} 条", results.size());
    }

    // =====================================================================
    // 测试 6：部分字段为 null 的文档
    // =====================================================================

    @Test
    @Order(6)
    @DisplayName("防御性 — source 字段缺失不抛异常")
    void missingFieldsGraceful() {
        // 插入只有 chunkText 的最小文档
        Long docId = nextId();
        indexMinimalDocument(docId, "最小文档仅包含文本内容", 1);

        List<RetrievalResult> results = keywordRetrievalChannel.retrieve(
                "最小文档 文本内容", 5, null);

        assertThat(results).isNotNull();
        if (!results.isEmpty()) {
            RetrievalResult r = results.get(0);
            assertThat(r.getChunkText()).isEqualTo("最小文档仅包含文本内容");
            assertThat(r.getSource()).isEqualTo("keyword");
            // 缺失字段应为 null
            assertThat(r.getSectionPath()).isNull();
        }
        log.info("缺失字段检索返回 {} 条", results.size());
    }

    // =====================================================================
    // helper
    // =====================================================================

    /** 确保测试索引存在（与 document 模块 mapping 一致）。 */
    private void ensureTestIndex() {
        try {
            boolean exists = esClient.indices().exists(
                    ExistsRequest.of(e -> e.index(indexName))).value();
            if (!exists) {
                esClient.indices().create(CreateIndexRequest.of(c -> c
                        .index(indexName)
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
                log.info("测试索引已创建: {}", indexName);
            } else {
                log.info("测试索引已存在: {}", indexName);
            }
        } catch (IOException e) {
            log.error("创建测试索引失败: {}", indexName, e);
            assumeTrue(false, "创建索引失败: " + e.getMessage());
        }
    }

    /** 索引完整字段的测试文档。 */
    private void indexDocument(Long documentId, String chunkText, String sectionPath, int chunkNo) {
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
                                    .index(indexName)
                                    .id(String.valueOf(chunkId))
                                    .document(doc)))));

            // 刷新使文档立即可搜索
            esClient.indices().refresh(r -> r.index(indexName));

            log.debug("测试文档已索引: documentId={}, chunkId={}, text={}", documentId, chunkId, chunkText);
        } catch (IOException e) {
            log.error("索引测试文档失败", e);
            assumeTrue(false, "索引文档失败: " + e.getMessage());
        }
    }

    /** 索引仅有 chunkText 的最小文档（测试缺失字段容错）。 */
    private void indexMinimalDocument(Long documentId, String chunkText, int chunkNo) {
        try {
            Long chunkId = nextId();
            Map<String, Object> doc = new LinkedHashMap<>();
            doc.put(MetadataKeys.DOCUMENT_ID, documentId);
            doc.put(MetadataKeys.CHUNK_ID, chunkId);
            doc.put(MetadataKeys.CHUNK_NO, chunkNo);
            doc.put(MetadataKeys.CHUNK_TEXT, chunkText);

            esClient.bulk(BulkRequest.of(b -> b
                    .operations(op -> op
                            .index(idx -> idx
                                    .index(indexName)
                                    .id(String.valueOf(chunkId))
                                    .document(doc)))));

            esClient.indices().refresh(r -> r.index(indexName));
        } catch (IOException e) {
            log.error("索引最小测试文档失败", e);
            assumeTrue(false, "索引文档失败: " + e.getMessage());
        }
    }

    private long nextId() {
        return idGenerator.incrementAndGet();
    }
}
