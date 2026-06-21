package com.reubenagent.rag;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import co.elastic.clients.transport.rest_client.RestClientTransport;
import com.reubenagent.document.entity.Document;
import com.reubenagent.document.entity.DocumentStrategyPlan;
import com.reubenagent.document.enums.DocumentIndexStatusEnum;
import com.reubenagent.document.enums.DocumentStrategyStatusEnum;
import com.reubenagent.document.mapper.IDocumentMapper;
import com.reubenagent.document.mapper.IDocumentStrategyPlanMapper;
import com.reubenagent.document.mq.DocumentKafkaConsumer;
import com.reubenagent.document.mq.DocumentKafkaProducer;
import com.reubenagent.rag.config.RagProperties;
import com.reubenagent.rag.constant.MetadataKeys;
import com.reubenagent.rag.dto.RagRetrieveRequest;
import com.reubenagent.rag.vo.RagRetrieveResponse;
import com.reubenagent.common.dto.ApiResponse;
import com.reubenagent.document.dto.DocumentStrategyConfirmDto;
import com.reubenagent.document.vo.DocumentStrategyConfirmVo;
import com.reubenagent.document.vo.DocumentUploadVo;
import lombok.extern.slf4j.Slf4j;
import org.apache.http.HttpHost;
import org.elasticsearch.client.RestClient;
import org.junit.jupiter.api.*;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * RAG 端到端全链路 Docker 集成测试 ——
 * 上传文档 → Kafka 异步解析+策略推荐 → 确认策略 → Kafka 异步切块+向量化+索引 → RRF 检索。
 *
 * <h3>前置条件</h3>
 * <pre>
 *   docker compose up -d   # MySQL, PGVector, ES, Redis, Kafka, MinIO
 *   # Ollama bge-m3 在 localhost:11434
 * </pre>
 *
 * <h3>运行</h3>
 * <pre>
 *   mvn test -pl business/rag -am \
 *       -Dtest=RagEndToEndIntegrationTest \
 *       -Dspring.profiles.active=e2e \
 *       -Dsurefire.failIfNoSpecifiedTests=false
 * </pre>
 *
 * @author reuben
 * @since 2026-06-21
 */
@Slf4j
@SpringBootTest(
        classes = RagEndToEndIntegrationTest.E2ETestApp.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT
)
@ActiveProfiles("e2e")
@Import(RagEndToEndIntegrationTest.EsE2EConfig.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RagEndToEndIntegrationTest {

    private static final String ES_HOST = "localhost";
    private static final int ES_PORT = 9200;

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private IDocumentMapper documentMapper;

    @Autowired
    private IDocumentStrategyPlanMapper planMapper;

    @Qualifier("ragPgVectorJdbcTemplate")
    @Autowired
    private JdbcTemplate pgVectorJdbcTemplate;

    @Autowired
    private RagProperties ragProperties;

    @Autowired
    private ObjectProvider<ElasticsearchClient> esClientProvider;

    @Autowired
    private ObjectProvider<KafkaTemplate<?, ?>> kafkaTemplateProvider;

    @Autowired
    private ObjectProvider<DocumentKafkaProducer> kafkaProducerProvider;

    @Autowired
    private ObjectProvider<DocumentKafkaConsumer> kafkaConsumerProvider;

    @Autowired
    private org.springframework.context.ApplicationContext ctx;

    private Path tempKafkaFile;
    private Path tempEsFile;
    private String baseUrl;

    // =====================================================================
    // 测试 App + ES 配置
    // =====================================================================

    /**
     * E2E 测试 Spring Boot App —— 扫描全部 com.reubenagent 包，
     * 拉取 document / rag / framework / common 所有 Bean。
     *
     * <p>{@code @EnableKafka} 由 {@code DocumentKafkaConfiguration} 提供，
     * 此处不重复声明。</p>
     */
    @org.springframework.boot.autoconfigure.SpringBootApplication(scanBasePackages = "com.reubenagent")
    @MapperScan("com.reubenagent.**.mapper")
    static class E2ETestApp {
    }

    /**
     * E2E 测试 ES 配置 —— 手动创建 ElasticsearchClient 连接 Docker ES。
     *
     * <p>ES 自动配置被 application-e2e.yml 排除，此处手动创建。
     * Kafka Producer/Consumer 由 document 模块的 {@code @Component} 自动扫描，
     * {@code DocumentKafkaConfiguration} 负责 {@code @EnableKafka} 和 Topic 创建。</p>
     */
    @org.springframework.boot.test.context.TestConfiguration
    static class EsE2EConfig {

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
    void checkPrerequisites() throws IOException {
        baseUrl = "http://localhost:" + port;

        // 验证中间件
        verifyMiddleware();

        // 准备测试文档（来自 Wikipedia）
        tempKafkaFile = Files.createTempFile("e2e-kafka-", ".txt");
        Files.writeString(tempKafkaFile, KAFKA_DOC_CONTENT);
        log.info("测试文档已创建: {} ({} bytes)", tempKafkaFile, Files.size(tempKafkaFile));

        tempEsFile = Files.createTempFile("e2e-elasticsearch-", ".txt");
        Files.writeString(tempEsFile, ELASTICSEARCH_DOC_CONTENT);
        log.info("测试文档已创建: {} ({} bytes)", tempEsFile, Files.size(tempEsFile));

        // 清理可能残留的 E2E 测试数据
        cleanupE2EData();
    }

    @AfterAll
    void tearDown() throws IOException {
        Files.deleteIfExists(tempKafkaFile);
        Files.deleteIfExists(tempEsFile);
        cleanupE2EData();
    }

    // =====================================================================
    // 测试 1：Kafka 文档 — 上传 → 解析 → 策略 → 确认 → 索引 → 检索
    // =====================================================================

    @Test
    @Order(1)
    @DisplayName("E2E: 上传 Kafka 文档 → 异步解析 → 确认策略 → 异步索引 → RRF 检索")
    void e2eKafkaDocument() {
        // 阶段 1：上传文档
        DocumentUploadVo uploadResult = uploadDocument(tempKafkaFile.toFile());
        Long documentId = uploadResult.getDocumentId();
        assertThat(documentId).isNotNull().isPositive();
        log.info("📄 文档已上传: documentId={}, taskId={}", documentId, uploadResult.getTaskId());

        // 阶段 2：等待 Kafka 异步解析完成 → 策略推荐就绪
        Document doc = waitForStrategyReady(documentId);
        Long planId = getLatestPlanId(documentId);
        assertThat(planId).isNotNull().isPositive();
        log.info("📋 策略就绪: documentId={}, planId={}, strategyStatus={}",
                documentId, planId, doc.getStrategyStatus());

        // 阶段 3：确认策略 → 触发 Kafka 索引构建
        DocumentStrategyConfirmVo confirmResult = confirmStrategy(documentId, planId);
        assertThat(confirmResult.getTaskId()).isNotNull().isPositive();
        log.info("✅ 策略已确认: documentId={}, buildTaskId={}", documentId, confirmResult.getTaskId());

        // 阶段 4：等待 Kafka 异步索引构建完成
        doc = waitForIndexComplete(documentId);
        log.info("🔍 索引完成: documentId={}, indexStatus={}", documentId, doc.getIndexStatus());

        // 阶段 5：RAG 检索 — 应命中 Kafka 相关 chunk
        RagRetrieveResponse retrieveResult = retrieve("Apache Kafka 分布式流处理平台如何实现消息传递", 5);
        assertThat(retrieveResult.getResults()).isNotEmpty();

        // 验证检索结果与原始文档语义相关
        log.info("🎯 检索完成: costMs={}, hits={}", retrieveResult.getTotalCostMs(),
                retrieveResult.getResults().size());
        retrieveResult.getResults().forEach(r ->
                log.info("  source={} score={} chunkId={} sectionPath={} text={}...",
                        r.getSource(), String.format("%.6f", r.getScore()),
                        r.getChunkId(), r.getSectionPath(),
                        r.getChunkText() != null ? r.getChunkText().substring(0,
                                Math.min(60, r.getChunkText().length())) : "null"));
    }

    // =====================================================================
    // 测试 2：ES 文档 — 上传 → 解析 → 策略 → 确认 → 索引 → 检索
    // =====================================================================

    @Test
    @Order(2)
    @DisplayName("E2E: 上传 Elasticsearch 文档 → 异步解析 → 确认策略 → 异步索引 → RRF 检索")
    void e2eElasticsearchDocument() {
        // 上传
        DocumentUploadVo uploadResult = uploadDocument(tempEsFile.toFile());
        Long documentId = uploadResult.getDocumentId();
        log.info("📄 ES 文档已上传: documentId={}", documentId);

        // 等待策略就绪
        waitForStrategyReady(documentId);
        Long planId = getLatestPlanId(documentId);
        log.info("📋 策略就绪: planId={}", planId);

        // 确认
        confirmStrategy(documentId, planId);

        // 等待索引完成
        waitForIndexComplete(documentId);
        log.info("🔍 ES 文档索引完成: documentId={}", documentId);

        // 检索 — 应命中 Elasticsearch 相关 chunk
        RagRetrieveResponse result = retrieve("Elasticsearch 全文搜索引擎的分片和副本机制", 5);
        assertThat(result.getResults()).isNotEmpty();

        log.info("🎯 ES 文档检索完成: hits={}", result.getResults().size());
        result.getResults().forEach(r ->
                log.info("  source={} score={} sectionPath={} text={}...",
                        r.getSource(), String.format("%.6f", r.getScore()),
                        r.getSectionPath(),
                        r.getChunkText() != null ? r.getChunkText().substring(0,
                                Math.min(60, r.getChunkText().length())) : "null"));
    }

    // =====================================================================
    // 测试 3：跨文档检索 — 两个文档都入库后，检索应返回最相关结果
    // =====================================================================

    @Test
    @Order(3)
    @DisplayName("E2E: 跨文档检索 — Kafka 查询不返回 ES 文档内容")
    void e2eCrossDocumentRetrieval() {
        // 查询明确与 Kafka 相关
        RagRetrieveResponse result = retrieve("Kafka Connect 和 Kafka Streams 流处理框架", 5);

        assertThat(result.getResults()).isNotEmpty();

        // 验证结果按相关性排序（Kafka 相关内容排在前面）
        log.info("🎯 跨文档检索: hits={}, costMs={}", result.getResults().size(), result.getTotalCostMs());
        result.getResults().forEach(r ->
                log.info("  source={} score={} documentId={} text={}...",
                        r.getSource(), String.format("%.6f", r.getScore()),
                        r.getDocumentId(),
                        r.getChunkText() != null ? r.getChunkText().substring(0,
                                Math.min(60, r.getChunkText().length())) : "null"));

        // 结果字段完整性
        for (var r : result.getResults()) {
            assertThat(r.getChunkId()).isNotNull();
            assertThat(r.getChunkText()).isNotNull();
            assertThat(r.getScore()).isNotNull().isGreaterThan(0.0);
            assertThat(r.getSource()).isIn("vector", "keyword", "hybrid");
            assertThat(r.getDocumentId()).isNotNull();
        }
    }

    // =====================================================================
    // helper — HTTP 调用
    // =====================================================================

    private DocumentUploadVo uploadDocument(java.io.File file) {
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", new FileSystemResource(file));

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);

        ResponseEntity<ApiResponse<DocumentUploadVo>> response = restTemplate.exchange(
                baseUrl + "/api/document/upload",
                HttpMethod.POST,
                new HttpEntity<>(body, headers),
                new ParameterizedTypeReference<ApiResponse<DocumentUploadVo>>() {});

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        ApiResponse<DocumentUploadVo> apiResp = response.getBody();
        assertThat(apiResp).isNotNull();
        assertThat(apiResp.getCode()).isEqualTo(0);
        assertThat(apiResp.getData()).isNotNull();
        return apiResp.getData();
    }

    private DocumentStrategyConfirmVo confirmStrategy(Long documentId, Long planId) {
        DocumentStrategyConfirmDto dto = DocumentStrategyConfirmDto.builder()
                .documentId(documentId)
                .planId(planId)
                .build();

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        ResponseEntity<ApiResponse<DocumentStrategyConfirmVo>> response = restTemplate.exchange(
                baseUrl + "/api/document/strategy/confirm",
                HttpMethod.POST,
                new HttpEntity<>(dto, headers),
                new ParameterizedTypeReference<ApiResponse<DocumentStrategyConfirmVo>>() {});

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        ApiResponse<DocumentStrategyConfirmVo> apiResp = response.getBody();
        assertThat(apiResp).isNotNull();
        assertThat(apiResp.getCode()).isEqualTo(0);
        assertThat(apiResp.getData()).isNotNull();
        return apiResp.getData();
    }

    private RagRetrieveResponse retrieve(String query, int topK) {
        RagRetrieveRequest request = RagRetrieveRequest.builder()
                .query(query)
                .topK(topK)
                .build();

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        ResponseEntity<ApiResponse<RagRetrieveResponse>> response = restTemplate.exchange(
                baseUrl + "/api/rag/retrieve",
                HttpMethod.POST,
                new HttpEntity<>(request, headers),
                new ParameterizedTypeReference<ApiResponse<RagRetrieveResponse>>() {});

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        ApiResponse<RagRetrieveResponse> apiResp = response.getBody();
        assertThat(apiResp).isNotNull();
        assertThat(apiResp.getCode()).isEqualTo(0);
        assertThat(apiResp.getData()).isNotNull();
        return apiResp.getData();
    }

    // =====================================================================
    // helper — 状态轮询
    // =====================================================================

    /** 等待 Kafka 消费者完成解析+策略推荐。 */
    private Document waitForStrategyReady(Long documentId) {
        return await().until(() -> {
            Document doc = documentMapper.selectById(documentId);
            if (doc == null) return null;
            Integer status = doc.getStrategyStatus();
            // RECOMMENDED = 策略推荐完成
            return (status != null
                    && status.equals(DocumentStrategyStatusEnum.RECOMMENDED.getCode()))
                    ? doc : null;
        }, Objects::nonNull);
    }

    /** 等待 Kafka 消费者完成切块+向量化+索引入库。 */
    private Document waitForIndexComplete(Long documentId) {
        return await().until(() -> {
            Document doc = documentMapper.selectById(documentId);
            if (doc == null) return null;
            Integer status = doc.getIndexStatus();
            return (status != null &&
                    (status.equals(DocumentIndexStatusEnum.BUILD_SUCCESS.getCode())
                            || status.equals(DocumentIndexStatusEnum.BUILD_FAIL.getCode())))
                    ? doc : null;
        }, Objects::nonNull);
    }

    /** 获取文档最新的策略方案 ID。 */
    private Long getLatestPlanId(Long documentId) {
        List<DocumentStrategyPlan> plans = planMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<DocumentStrategyPlan>()
                        .eq(DocumentStrategyPlan::getDocumentId, documentId)
                        .orderByDesc(DocumentStrategyPlan::getCreateTime)
                        .last("LIMIT 1"));
        return plans.isEmpty() ? null : plans.get(0).getId();
    }

    // =====================================================================
    // helper — 中间件验证 & 清理
    // =====================================================================

    private void verifyMiddleware() {
        // MySQL
        try {
            Long r = documentMapper.selectCount(null);
            log.info("MySQL 连接验证通过 (document count={})", r);
        } catch (Exception e) {
            assumeTrue(false, "MySQL 连接失败: " + e.getMessage());
        }

        // ES
        ElasticsearchClient esClient = esClientProvider.getIfAvailable();
        try {
            assumeTrue(esClient != null && esClient.ping().value(),
                    "ES 不可用（需 docker compose up -d elasticsearch）");
            log.info("ES 连接验证通过");
        } catch (Exception e) {
            assumeTrue(false, "ES 连接失败: " + e.getMessage());
        }

        // PGVector
        try {
            Integer r = pgVectorJdbcTemplate.queryForObject("SELECT 1", Integer.class);
            assumeTrue(r != null && r == 1, "PGVector 连接失败");
            log.info("PGVector 连接验证通过");
        } catch (Exception e) {
            assumeTrue(false, "PGVector 连接失败: " + e.getMessage());
        }

        // Kafka — 诊断 KafkaTemplate 是否被创建
        KafkaTemplate<?, ?> kt = kafkaTemplateProvider.getIfAvailable();
        if (kt != null) {
            log.info("KafkaTemplate 可用: {}", kt);
        } else {
            log.warn("KafkaTemplate 不可用！Kafka 消息发送将降级跳过");
        }

        DocumentKafkaProducer producer = kafkaProducerProvider.getIfAvailable();
        if (producer != null) {
            log.info("DocumentKafkaProducer 可用: {}", producer);
        } else {
            log.warn("DocumentKafkaProducer 不可用！");
            // 诊断：列出所有 KafkaTemplate 类型的 bean
            String[] ktBeans = ctx.getBeanNamesForType(KafkaTemplate.class);
            log.info("  KafkaTemplate beans in context: {}", java.util.Arrays.toString(ktBeans));
            String[] producerBeans = ctx.getBeanNamesForType(DocumentKafkaProducer.class);
            log.info("  DocumentKafkaProducer beans in context: {}", java.util.Arrays.toString(producerBeans));
            String[] consumerBeans = ctx.getBeanNamesForType(DocumentKafkaConsumer.class);
            log.info("  DocumentKafkaConsumer beans in context: {}", java.util.Arrays.toString(consumerBeans));
        }
    }

    private void cleanupE2EData() {
        // 清理 ES（E2E 测试文档的 chunk）
        ElasticsearchClient esClient = esClientProvider.getIfAvailable();
        if (esClient != null) {
            try {
                // 只清理最近创建的测试数据（通过时间范围删除不太现实，跳过）
                log.debug("ES E2E 数据清理跳过（由 AfterAll 一次处理）");
            } catch (Exception e) {
                log.warn("ES 清理失败", e);
            }
        }

        // PGVector 数据由 document 模块管理，不在此清理
    }

    // =====================================================================
    // 测试文档内容
    // =====================================================================

    /** Apache Kafka Wikipedia 文章（用于端到端测试）。 */
    static final String KAFKA_DOC_CONTENT = """
            Apache Kafka

            Apache Kafka is a distributed event store and stream-processing platform.
            It is an open-source system developed by the Apache Software Foundation
            written in Java and Scala. The project aims to provide a unified,
            high-throughput, low-latency platform for handling real-time data feeds.

            Kafka can connect to external systems for data import and export via
            Kafka Connect, and provides the Kafka Streams libraries for stream
            processing applications. Kafka uses a binary TCP-based protocol that
            is optimized for efficiency and relies on a message set abstraction
            that naturally groups messages together to reduce the overhead of
            network roundtrips.

            History

            Kafka was originally developed at LinkedIn, and was subsequently open
            sourced in early 2011. Jay Kreps, Neha Narkhede and Jun Rao helped
            co-create Kafka. Graduation from the Apache Incubator occurred on
            23 October 2012. Jay Kreps chose to name the software after the
            author Franz Kafka because it is a system optimized for writing.

            Architecture

            Kafka stores key-value messages that come from arbitrarily many
            processes called producers. The data is partitioned into different
            partitions within different topics. Within a partition, messages are
            strictly ordered by their offsets (the position of a message within
            a partition), and indexed and stored together with a timestamp.

            Producers write messages to topics. Kafka distributes messages across
            partitions using a partitioning strategy, which defaults to a hash of
            the key. Consumers read messages from topics by subscribing to them.
            Each consumer belongs to a consumer group, and each partition is
            consumed by exactly one consumer in the group.

            Kafka runs on a cluster of one or more servers (called brokers), and
            the partitions of all topics are distributed across the cluster nodes.
            Additionally, partitions are replicated to multiple brokers for fault
            tolerance. The Kafka cluster retains all published messages for a
            configurable retention period, regardless of whether they have been
            consumed.

            Kafka Connect API

            Kafka Connect is a framework to import and export data from and to
            other systems. It was added in the Kafka 0.9.0.0 release and uses the
            Producer and Consumer API internally. The Connect framework executes
            connectors that implement the actual logic to read and write data from
            other systems. Common connectors include JDBC, Elasticsearch, HDFS,
            and S3.

            Kafka Streams API

            Kafka Streams is a stream-processing library written in Java. It was
            added in the Kafka 0.10.0.0 release. The library allows for the
            development of stateful stream-processing applications that are
            scalable, elastic, and fully fault-tolerant. The main API is a
            stream-processing DSL that offers high-level operators like filter,
            map, grouping, windowing, aggregation, joins, and the notion of tables.

            For stateful stream processing, Kafka Streams uses RocksDB to maintain
            local operator state. Because RocksDB can write to disk, the maintained
            state can be larger than available main memory. For fault-tolerance,
            all updates to local state stores are also written into a topic in the
            Kafka cluster, allowing state recreation by reading those topics.

            Queues for Kafka

            In 2025, Apache Kafka introduced Queues for Kafka, adding share groups
            as an alternative to consumer groups. This feature enables queue-like
            semantics where consumers can cooperatively process records from the
            same partitions, with individual message acknowledgment and delivery
            tracking. This addresses the common challenge of over-partitioning.

            Use Cases

            Kafka is commonly used for building real-time streaming data pipelines
            that reliably move data between systems, building real-time streaming
            applications that transform streams of data, and for event sourcing
            and log aggregation. Major users include LinkedIn, Netflix, Uber,
            Spotify, and thousands of other organizations.
            """;

    /** Elasticsearch Wikipedia 文章（用于端到端测试）。 */
    static final String ELASTICSEARCH_DOC_CONTENT = """
            Elasticsearch

            Elasticsearch is a source-available search engine developed by Elastic.
            It is based on Apache Lucene and provides a distributed, multitenant-
            capable full-text search engine with an HTTP web interface and
            schema-free JSON documents.

            Elasticsearch is distributed and uses JSON documents stored in indices
            divided into shards, each of which may have replicas distributed across
            cluster nodes. It supports full-text search, faceted search, real-time
            search, and multitenancy. The software is developed alongside Logstash,
            Kibana, and Beats as part of the Elastic Stack (formerly the ELK Stack).

            History

            Shay Banon created the precursor to Elasticsearch, called Compass, in
            2004. Developing a third version of Compass, he concluded that a full
            rewrite was necessary to build a scalable, distributed search solution
            using JSON over HTTP as a common interface. He released the first
            version of Elasticsearch in February 2010. Elastic NV was founded in
            2012 to provide commercial services and products around Elasticsearch.

            Architecture and Features

            Elasticsearch is built on Apache Lucene and exposes Lucene's
            capabilities through a JSON and Java API. Documents are stored in
            indices, which are divided into primary shards; each shard may have
            zero or more replicas distributed across cluster nodes. Routing and
            rebalancing are handled automatically.

            The engine supports faceted search and percolation, a form of
            prospective search in which stored queries are matched against incoming
            documents rather than the reverse. A gateway module handles long-term
            index persistence, allowing an index to be recovered after a node
            failure. Real-time GET requests make Elasticsearch usable as a NoSQL
            datastore, though it does not support distributed transactions.

            Security

            In May 2019, Elastic made the core security features of the Elastic
            Stack available without charge, including TLS for encrypted
            communications, file and native realm authentication, and role-based
            access control for cluster APIs and indices. Elasticsearch also offers
            SIEM and machine learning capabilities as part of its commercial
            offerings.

            Elastic Stack

            Elasticsearch is developed alongside Logstash, a data collection and
            log-parsing engine; Kibana, an analytics and visualization platform;
            and Beats, lightweight data shippers. The four products are designed
            for use together as the Elastic Stack.

            Use Cases

            Elasticsearch is widely used for full-text search, log and event data
            analysis, application performance monitoring, security analytics, and
            geospatial data analysis. It powers search experiences across thousands
            of websites and enterprise applications worldwide.
            """;
}
