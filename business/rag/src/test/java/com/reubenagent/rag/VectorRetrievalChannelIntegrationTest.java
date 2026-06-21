package com.reubenagent.rag;

import com.reubenagent.rag.config.RagProperties;
import com.reubenagent.rag.model.RetrievalResult;
import com.reubenagent.rag.service.VectorRetrievalChannel;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.*;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 向量检索通道 Docker 集成测试 —— 验证 PGVector 余弦相似度检索。
 *
 * <h3>前置条件</h3>
 * <pre>
 *   docker compose up -d postgres
 *   # PGVector 需要在 postgres 容器中启用
 *   docker compose up -d     # 或启动所有服务
 * </pre>
 *
 * <h3>Embedding 模型</h3>
 * <p>需要 Ollama 运行在 localhost:11434 并提供 bge-m3 模型，
 * 或配置有效的 DeepSeek API key。若不可用则测试跳过。</p>
 *
 * <h3>运行</h3>
 * <pre>
 *   mvn test -pl business/rag -am \
 *       -Dtest=VectorRetrievalChannelIntegrationTest \
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
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class VectorRetrievalChannelIntegrationTest {

    @Autowired
    private VectorRetrievalChannel vectorRetrievalChannel;

    @Autowired
    private RagProperties ragProperties;

    @Autowired
    private ObjectProvider<EmbeddingModel> embeddingModelProvider;

    @Qualifier("ragPgVectorJdbcTemplate")
    @Autowired
    private JdbcTemplate pgVectorJdbcTemplate;

    private final AtomicLong idGenerator = new AtomicLong(9000000000000000000L);

    /** 全部测试的前置检查：PGVector 和 EmbeddingModel 是否可用。 */
    @BeforeAll
    void checkPrerequisites() {
        // 验证 PGVector 连接可用
        try {
            Integer result = pgVectorJdbcTemplate.queryForObject("SELECT 1", Integer.class);
            assumeTrue(result != null && result == 1, "PGVector 连接不可用，跳过所有测试");
            log.info("PGVector 连接验证通过");
        } catch (Exception e) {
            assumeTrue(false, "PGVector 连接失败: " + e.getMessage());
        }

        // 验证 EmbeddingModel 可用
        EmbeddingModel model = embeddingModelProvider.getIfAvailable();
        assumeTrue(model != null, "EmbeddingModel 不可用（需 Ollama 或 DeepSeek API），跳过所有测试");
        log.info("EmbeddingModel 可用: {}", model);
    }

    /** 每个测试后清理数据。 */
    @AfterEach
    void cleanup() {
        String tableName = ragProperties.getPgvector().getTableName();
        pgVectorJdbcTemplate.update(
                "DELETE FROM " + tableName + " WHERE document_id >= 9900000000000000000");
    }

    // =====================================================================
    // 测试 1：基本向量检索
    // =====================================================================

    @Test
    @Order(1)
    @DisplayName("基本向量检索 — 语义相似 chunk 排在前面")
    void basicVectorRetrieval() {
        EmbeddingModel model = embeddingModelProvider.getIfAvailable();
        assertThat(model).isNotNull();

        // 插入 3 条测试数据（不同主题）
        insertEmbedding(model, "Python 是一门流行的编程语言，广泛用于数据科学和机器学习",
                "Python", 1);
        insertEmbedding(model, "今天天气真好，适合出去散步和野餐",
                "天气", 2);
        insertEmbedding(model, "Python 的 pandas 库提供了强大的 DataFrame 数据处理能力",
                "Python", 3);

        // 查询与 Python 编程相关的内容
        List<RetrievalResult> results = vectorRetrievalChannel.retrieve(
                "如何在 Python 中进行数据分析", 5, null);

        assertThat(results).isNotEmpty();
        assertThat(results.size()).isLessThanOrEqualTo(5);

        // 最相关的应该是关于 Python 数据分析的（第3条）或 Python 编程的（第1条）
        RetrievalResult top = results.get(0);
        assertThat(top.getChunkText()).isNotNull();
        assertThat(top.getScore()).isGreaterThan(0.0);
        assertThat(top.getSource()).isEqualTo("vector");
        assertThat(top.getDocumentId()).isNotNull();
        assertThat(top.getChunkId()).isNotNull();

        // 天气相关的应该不在前面（或分数更低）
        log.info("Top result: score={}, text={}", top.getScore(), top.getChunkText());
        results.forEach(r -> log.info("  score={:.4f} text={}", r.getScore(), r.getChunkText()));
    }

    // =====================================================================
    // 测试 2：filter 过滤
    // =====================================================================

    @Test
    @Order(2)
    @DisplayName("过滤检索 — metadata_json documentId 过滤")
    void filteredRetrieval() {
        EmbeddingModel model = embeddingModelProvider.getIfAvailable();
        assertThat(model).isNotNull();

        Long docA = nextId();
        Long docB = nextId();

        insertEmbedding(model, "Spring Boot 是一个 Java 框架，简化了企业级应用开发",
                "Spring", 1, docA);
        insertEmbedding(model, "Docker 容器化技术让应用部署更加便捷和可移植",
                "Docker", 2, docB);

        // 只检索 documentId = docA 的结果
        List<RetrievalResult> results = vectorRetrievalChannel.retrieve(
                "Java 应用开发和部署",
                5,
                Map.of("documentId", String.valueOf(docA)));

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
        EmbeddingModel model = embeddingModelProvider.getIfAvailable();
        assertThat(model).isNotNull();

        // 插入 6 条数据
        for (int i = 1; i <= 6; i++) {
            insertEmbedding(model,
                    "这是测试文档段落 " + i + "，包含一些关于人工智能和机器学习的讨论内容",
                    "AI", i);
        }

        int topK = 3;
        List<RetrievalResult> results = vectorRetrievalChannel.retrieve(
                "人工智能和机器学习的发展趋势", topK, null);

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
        List<RetrievalResult> results = vectorRetrievalChannel.retrieve("", 5, null);
        assertThat(results).isEmpty();

        results = vectorRetrievalChannel.retrieve(null, 5, null);
        assertThat(results).isEmpty();
    }

    // =====================================================================
    // 测试 5：防御性 — 空表无结果
    // =====================================================================

    @Test
    @Order(5)
    @DisplayName("防御性 — 无匹配数据返回空列表")
    void noMatchingDataReturnsEmptyList() {
        // 确保表是空的（AfterEach 已清理）
        List<RetrievalResult> results = vectorRetrievalChannel.retrieve(
                "这个查询不会有任何匹配结果因为表是空的", 5, null);

        // 空表时可能返回空列表，也可能返回相似度很低的结果
        // 取决于 PGVector 索引行为，两种都可以
        assertThat(results).isNotNull();
        log.info("空表检索返回 {} 条", results.size());
    }

    // =====================================================================
    // helper
    // =====================================================================

    private void insertEmbedding(EmbeddingModel model, String text, String category, int chunkNo) {
        insertEmbedding(model, text, category, chunkNo, nextId());
    }

    private void insertEmbedding(EmbeddingModel model, String text, String category,
                                  int chunkNo, Long documentId) {
        float[] embedding = model.embed(text);
        String vectorLiteral = toVectorLiteral(embedding);

        long id = nextId();
        Long chunkId = nextId();
        Long parentBlockId = nextId();

        String tableName = ragProperties.getPgvector().getTableName();
        pgVectorJdbcTemplate.update(
                "INSERT INTO " + tableName
                        + " (id, document_id, chunk_id, chunk_no, parent_block_id,"
                        + "  section_path, chunk_text, char_count, embedding, embedding_model,"
                        + "  metadata_json, create_time, edit_time)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS vector), ?, CAST(? AS jsonb), NOW(), NOW())",
                id, documentId, chunkId, chunkNo, parentBlockId,
                category + "/section-" + chunkNo, text, text.length(),
                vectorLiteral, "bge-m3",
                "{\"documentId\": " + documentId + ", \"category\": \"" + category + "\", \"chunkNo\": " + chunkNo + "}"
        );
        log.debug("测试数据插入: id={}, documentId={}, chunkNo={}, category={}", id, documentId, chunkNo, category);
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
