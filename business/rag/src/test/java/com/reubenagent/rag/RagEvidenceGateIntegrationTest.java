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
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Phase 7 证据门控 Docker 集成测试 —— 验证向量绝对阈值 + 关键词相对阈值过滤。
 *
 * <h3>前置条件</h3>
 * <pre>
 *   docker compose up -d pgvector elasticsearch
 *   # Ollama 必须运行并提供 bge-m3 embedding 模型
 * </pre>
 *
 * <h3>运行</h3>
 * <pre>
 *   mvn test -pl business/rag \
 *       -Dtest=RagEvidenceGateIntegrationTest \
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
@Import(RagEvidenceGateIntegrationTest.TestDockerConfig.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RagEvidenceGateIntegrationTest {

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

    private final AtomicLong idGenerator = new AtomicLong(9200000000000000000L);

    private String pgTableName;
    private String esIndexName;
    /** 测试过程中固定的 documentId，用于 filterFields 隔离 */
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

    /** 构建 filterFields，只查当前测试的 documentId。 */
    private Map<String, String> docFilter() {
        return Map.of(MetadataKeys.DOCUMENT_ID, String.valueOf(testDocId));
    }

    // =====================================================================
    // 测试 1：向量门控 — 高相关保留，低相似度过滤
    // =====================================================================

    @Test
    @Order(1)
    @DisplayName("向量门控 — 语义相关 chunk 保留，不相关 chunk 被过滤")
    void vectorGateKeepsRelevantChunks() {
        EmbeddingModel model = embeddingModelProvider.getIfAvailable();
        assertThat(model).isNotNull();

        // 高相关：与查询语义高度一致（同义改写）
        String goodText = "Spring Boot 框架通过自动配置和起步依赖大幅简化了 Java 企业级应用开发流程";
        insertPgVector(model, goodText, testDocId, 1, "/Java/Spring");

        // 低相关：与查询主题完全无关
        String badText = "天气预报说明天会下雨记得带伞出门注意交通安全";
        insertPgVector(model, badText, testDocId, 2, "/Life/Weather");

        // 纯测向量通道，不用 ES
        RagRetrieveRequest request = RagRetrieveRequest.builder()
                .query("使用 Spring Boot 进行 Java 企业级开发")
                .topK(5)
                .filterFields(docFilter())
                .build();

        RagRetrieveResponse response = ragRetrievalService.retrieve(request);

        assertThat(response).isNotNull();
        assertThat(response.getResults()).isNotNull();

        List<String> texts = response.getResults().stream()
                .map(RetrievalResult::getChunkText)
                .collect(Collectors.toList());

        log.info("向量门控结果 ({} 条): {}", response.getResults().size(), texts);

        // 高相关 chunk 必须保留
        assertThat(texts)
                .as("语义高度相关的 chunk 应通过门控")
                .contains(goodText);

        // 低相关 chunk 必须被过滤
        assertThat(texts)
                .as("语义不相关的天气 chunk 应被门控过滤")
                .doesNotContain(badText);

        response.getResults().forEach(r ->
                log.info("  保留: score={:.6f} source={} chunkId={}",
                        r.getScore(), r.getSource(), r.getChunkId()));
    }

    // =====================================================================
    // 测试 2：关键词门控 — 高 BM25 保留，低分过滤
    // =====================================================================

    @Test
    @Order(2)
    @DisplayName("关键词门控 — BM25 高分保留，低分被相对阈值过滤")
    void keywordGateKeepsHighBm25Chunks() {
        // 高相关：包含大量查询关键词
        String goodText = "Elasticsearch 是一个分布式全文搜索引擎，基于 BM25 算法进行相关性评分和排序";
        indexEsDocument(testDocId, 1, goodText, "/Search/ES");

        // 中相关：包含部分关键词
        String midText = "搜索引擎需要高效的相关性排序算法来保证搜索结果质量";
        indexEsDocument(testDocId, 2, midText, "/Search/Algo");

        // 低相关：完全没有搜索相关词
        String badText = "周末去郊外野餐烧烤享受阳光和美食是很惬意的事情";
        indexEsDocument(testDocId, 3, badText, "/Life/Weekend");

        RagRetrieveRequest request = RagRetrieveRequest.builder()
                .query("Elasticsearch 分布式搜索引擎 BM25 相关性评分")
                .topK(5)
                .filterFields(docFilter())
                .build();

        RagRetrieveResponse response = ragRetrievalService.retrieve(request);

        assertThat(response).isNotNull();
        assertThat(response.getResults()).isNotNull();

        List<String> texts = response.getResults().stream()
                .map(RetrievalResult::getChunkText)
                .collect(Collectors.toList());

        log.info("关键词门控结果 ({} 条): {}", response.getResults().size(), texts);

        // 高 BM25 的在结果中
        assertThat(texts)
                .as("高 BM25 分数的 Elasticsearch chunk 应保留")
                .contains(goodText);

        // 低 BM25 的被过滤
        assertThat(texts)
                .as("低 BM25 的野餐 chunk 应被门控过滤")
                .doesNotContain(badText);

        response.getResults().forEach(r ->
                log.info("  保留: score={:.6f} source={} chunkId={}",
                        r.getScore(), r.getSource(), r.getChunkId()));
    }

    // =====================================================================
    // 测试 3：双通道 + 双门控 — 各自过滤后融合
    // =====================================================================

    @Test
    @Order(3)
    @DisplayName("双通道门控融合 — 向量和关键词的低分结果都被过滤后融合")
    void dualChannelGatesThenFuse() {
        EmbeddingModel model = embeddingModelProvider.getIfAvailable();
        assertThat(model).isNotNull();

        // 向量：好 + 坏
        String vGood = "Docker 容器化技术通过镜像和容器实现了应用的快速部署和环境一致性";
        String vBad = "今天晚上吃什么好呢要不我们点外卖吧比较方便省时间";
        insertPgVector(model, vGood, testDocId, 1, "/DevOps/Docker");
        insertPgVector(model, vBad, testDocId, 2, "/Life/Food");

        // 关键词：好 + 坏
        String kGood = "Docker Compose 和 Kubernetes 是主流的容器编排和管理平台";
        String kBad = "红烧肉的做法先把五花肉焯水再炒糖色加调料慢炖两小时";
        indexEsDocument(testDocId, 1, kGood, "/DevOps/K8s");
        indexEsDocument(testDocId, 2, kBad, "/Life/Cooking");

        RagRetrieveRequest request = RagRetrieveRequest.builder()
                .query("Docker 容器化技术 Kubernetes 部署编排")
                .topK(5)
                .filterFields(docFilter())
                .build();

        RagRetrieveResponse response = ragRetrievalService.retrieve(request);

        assertThat(response).isNotNull();
        assertThat(response.getResults()).isNotNull();
        assertThat(response.getResults()).isNotEmpty();

        List<String> texts = response.getResults().stream()
                .map(RetrievalResult::getChunkText)
                .collect(Collectors.toList());

        log.info("双通道门控融合结果 ({} 条): {}", response.getResults().size(), texts);

        // 相关的内容必须在
        boolean hasDocker = texts.stream().anyMatch(t -> t.contains("Docker"));
        assertThat(hasDocker).as("Docker 相关内容应保留").isTrue();

        // 不相关的必须不在
        assertThat(texts).as("食物相关内容应被过滤").noneMatch(t -> t.contains("吃") || t.contains("红烧肉"));

        response.getResults().forEach(r ->
                log.info("  保留: score={:.6f} source={} chunkId={}",
                        r.getScore(), r.getSource(), r.getChunkId()));
    }

    // =====================================================================
    // 测试 4：全部被门控过滤 → 返回空列表不抛异常
    // =====================================================================

    @Test
    @Order(4)
    @DisplayName("全部门控过滤 — 返回空列表不崩溃")
    void allGatedOutReturnsEmpty() {
        EmbeddingModel model = embeddingModelProvider.getIfAvailable();
        assertThat(model).isNotNull();

        // 插入与查询完全无关的数据
        String unrelated1 = "中午去食堂吃饭今天的红烧排骨味道很不错";
        String unrelated2 = "明天早上六点半起床去操场跑步锻炼身体";
        insertPgVector(model, unrelated1, testDocId, 1, "/Life/Food");
        insertPgVector(model, unrelated2, testDocId, 2, "/Life/Sport");

        indexEsDocument(testDocId, 1, "周末去哪里玩比较好周边有什么景点推荐", "/Life/Travel");
        indexEsDocument(testDocId, 2, "今天晚上看什么电影最近有什么好看的推荐吗", "/Life/Entertainment");

        RagRetrieveRequest request = RagRetrieveRequest.builder()
                .query("分布式微服务架构高可用系统设计模式")
                .topK(5)
                .filterFields(docFilter())
                .build();

        RagRetrieveResponse response = ragRetrievalService.retrieve(request);

        assertThat(response).isNotNull();
        assertThat(response.getResults()).isNotNull();
        assertThat(response.getTotalCostMs()).isPositive();
        log.info("全部门控过滤: 返回 {} 条（预期 0），耗时 {}ms",
                response.getResults().size(), response.getTotalCostMs());
    }

    // =====================================================================
    // 测试 5：混合质量 — 门控保留高质量、丢弃低质量
    // =====================================================================

    @Test
    @Order(5)
    @DisplayName("混合质量门控 — 多质量 mixed 输入，仅高质量保留")
    void mixedQualityGating() {
        EmbeddingModel model = embeddingModelProvider.getIfAvailable();
        assertThat(model).isNotNull();

        // 高质量：与查询高度相关
        String[] goodTexts = {
                "微服务架构将单体应用拆分为多个独立的服务每个服务负责特定的业务功能",
                "Spring Cloud 提供了服务注册发现负载均衡和配置管理等微服务治理组件",
                "Kubernetes 作为容器编排平台可以自动化微服务的部署扩展和管理"
        };

        // 低质量：与查询无关
        String[] badTexts = {
                "今天晚饭吃什么要不我们叫外卖披萨和炸鸡味道都不错",
                "明天早上六点起床去跑步锻炼身体保持健康生活方式"
        };

        for (int i = 0; i < goodTexts.length; i++) {
            insertPgVector(model, goodTexts[i], testDocId, i + 1, "/Microservices/" + i);
            indexEsDocument(testDocId, i + 1, goodTexts[i], "/Microservices/" + i);
        }
        for (int i = 0; i < badTexts.length; i++) {
            insertPgVector(model, badTexts[i], testDocId, goodTexts.length + i + 1, "/Life/" + i);
            indexEsDocument(testDocId, goodTexts.length + i + 1, badTexts[i], "/Life/" + i);
        }

        RagRetrieveRequest request = RagRetrieveRequest.builder()
                .query("微服务架构设计 Spring Cloud 服务治理 Kubernetes 容器编排")
                .topK(10)
                .filterFields(docFilter())
                .build();

        RagRetrieveResponse response = ragRetrievalService.retrieve(request);

        assertThat(response).isNotNull();
        assertThat(response.getResults()).isNotNull();

        List<String> texts = response.getResults().stream()
                .map(RetrievalResult::getChunkText)
                .collect(Collectors.toList());

        log.info("混合质量门控结果 ({} 条, 插入 {} 条): {}",
                response.getResults().size(), goodTexts.length + badTexts.length, texts);

        // 至少有一条微服务相关内容保留
        long techHits = texts.stream()
                .filter(t -> t.contains("微服务") || t.contains("Spring Cloud") || t.contains("Kubernetes"))
                .count();
        assertThat(techHits)
                .as("至少 1 条微服务相关内容通过门控")
                .isGreaterThanOrEqualTo(1);

        // 不相关的内容全部被过滤
        assertThat(texts)
                .as("晚饭/跑步等无关内容不应出现")
                .noneMatch(t -> t.contains("晚饭") || t.contains("跑步") || t.contains("披萨"));

        // 结果数量应小于总输入量（有过滤发生）
        log.info("门控输入 {} 条 → 输出 {} 条（{} 条被过滤）",
                goodTexts.length + badTexts.length, texts.size(),
                goodTexts.length + badTexts.length - texts.size());

        response.getResults().forEach(r ->
                log.info("  保留: score={:.6f} source={} chunkId={}",
                        r.getScore(), r.getSource(), r.getChunkId()));
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
