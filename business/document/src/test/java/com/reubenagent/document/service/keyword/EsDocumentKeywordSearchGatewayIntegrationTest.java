package com.reubenagent.document.service.keyword;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import co.elastic.clients.transport.rest_client.RestClientTransport;
import com.reubenagent.document.config.DocumentProperties;
import com.reubenagent.document.dto.DocumentRetrieveRequest;
import com.reubenagent.document.entity.DocumentChunk;
import com.reubenagent.document.enums.DocumentChunkSourceTypeEnum;
import org.apache.http.HttpHost;
import org.elasticsearch.client.RestClient;
import org.springframework.beans.factory.ObjectProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 真实 Elasticsearch 集成测试 —— 需要 Docker ES 运行在 localhost:9200。
 *
 * <pre>docker compose up -d elasticsearch</pre>
 *
 * <p>使用英文文本测试，避免 ES standard 分析器对中文的逐字分词问题。</p>
 */
@DisplayName("EsDocumentKeywordSearchGateway 集成测试 (真实 ES)")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class EsDocumentKeywordSearchGatewayIntegrationTest {

    private static final String TEST_INDEX = "reuben_test_chunk_integration";

    private ElasticsearchClient esClient;
    private EsDocumentKeywordSearchGateway gateway;

    @BeforeAll
    void createClient() {
        RestClient restClient = RestClient.builder(HttpHost.create("http://localhost:9200")).build();
        RestClientTransport transport = new RestClientTransport(restClient, new JacksonJsonpMapper());
        esClient = new ElasticsearchClient(transport);

        DocumentProperties properties = new DocumentProperties();
        properties.getElasticsearch().setIndexName(TEST_INDEX);
        gateway = new EsDocumentKeywordSearchGateway(
                new ObjectProvider<>() {
                    @Override
                    public ElasticsearchClient getObject() { return esClient; }
                    @Override
                    public ElasticsearchClient getIfAvailable() { return esClient; }
                    @Override
                    public ElasticsearchClient getIfUnique() { return esClient; }
                }, properties);
    }

    @BeforeEach
    void deleteTestIndex() {
        try {
            esClient.indices().delete(d -> d.index(TEST_INDEX).ignoreUnavailable(true));
        } catch (Exception ignored) {
        }
    }

    @AfterEach
    void cleanup() {
        try {
            esClient.indices().delete(d -> d.index(TEST_INDEX).ignoreUnavailable(true));
        } catch (Exception ignored) {
        }
    }

    // ============ 索引 + 检索 + 删除全链路 ============

    @Test
    @Order(1)
    @DisplayName("索引两条 chunk → 检索命中 → 删除 → 检索为空")
    void shouldCompleteFullCycle() throws IOException {
        // === 1. 索引 chunk ===
        DocumentChunk chunk1 = DocumentChunk.builder()
                .id(1001L)
                .documentId(100L)
                .taskId(10L)
                .planId(1L)
                .parentBlockId(500L)
                .chunkNo(1)
                .sourceType(DocumentChunkSourceTypeEnum.ORIGINAL.getCode())
                .sectionPath("Chapter 1 / Section 1")
                .chunkText("The vendor shall deliver the software within 30 days after contract signing")
                .charCount(75)
                .build();

        DocumentChunk chunk2 = DocumentChunk.builder()
                .id(1002L)
                .documentId(100L)
                .taskId(10L)
                .planId(1L)
                .parentBlockId(500L)
                .chunkNo(2)
                .sourceType(DocumentChunkSourceTypeEnum.ORIGINAL.getCode())
                .sectionPath("Chapter 2")
                .chunkText("Late payment shall incur a penalty of 0.05 percent per day")
                .charCount(58)
                .build();

        gateway.indexChunks(List.of(chunk1, chunk2));

        // 等待 ES refresh
        esClient.indices().refresh(r -> r.index(TEST_INDEX));

        // === 2. 检索命中 ===
        DocumentRetrieveRequest request = DocumentRetrieveRequest.builder()
                .query("software delivery contract")
                .topK(5)
                .build();

        List<org.springframework.ai.document.Document> results = gateway.search(request);
        assertThat(results).hasSize(1);
        assertThat(results.get(0).getText())
                .isEqualTo("The vendor shall deliver the software within 30 days after contract signing");
        assertThat(results.get(0).getMetadata()).containsEntry("sectionPath", "Chapter 1 / Section 1");

        // === 3. 检索不相关 → 空结果 ===
        DocumentRetrieveRequest emptyRequest = DocumentRetrieveRequest.builder()
                .query("zebra galaxy quantum physics")
                .topK(5)
                .build();
        List<org.springframework.ai.document.Document> emptyResults = gateway.search(emptyRequest);
        assertThat(emptyResults).isEmpty();

        // === 4. 删除文档 ===
        gateway.deleteByDocumentId(100L);

        // 删除已包含 refresh=true，但再刷一次保证
        esClient.indices().refresh(r -> r.index(TEST_INDEX));

        // === 5. 删除后检索为空 ===
        List<org.springframework.ai.document.Document> afterDelete = gateway.search(request);
        assertThat(afterDelete).isEmpty();
    }

    @Test
    @Order(2)
    @DisplayName("带 filterFields 过滤检索")
    void shouldApplyFilterFields() throws IOException {
        DocumentChunk chunk1 = DocumentChunk.builder()
                .id(2001L).documentId(200L).taskId(10L).planId(1L).chunkNo(1)
                .chunkText("The tenant shall pay rent before the 5th day of each month").build();
        DocumentChunk chunk2 = DocumentChunk.builder()
                .id(2002L).documentId(300L).taskId(10L).planId(1L).chunkNo(1)
                .chunkText("The tenant shall be liable for late payment of rent").build();

        gateway.indexChunks(List.of(chunk1, chunk2));
        esClient.indices().refresh(r -> r.index(TEST_INDEX));

        // 仅查 documentId=200
        DocumentRetrieveRequest request = DocumentRetrieveRequest.builder()
                .query("rent payment")
                .topK(10)
                .filterFields(Map.of("documentId", "200"))
                .build();

        List<org.springframework.ai.document.Document> results = gateway.search(request);
        assertThat(results).hasSize(1);
        assertThat(results.get(0).getText())
                .isEqualTo("The tenant shall pay rent before the 5th day of each month");
    }

    @Test
    @Order(3)
    @DisplayName("大量 chunk 批量索引 + 检索")
    void shouldHandleLargerBatch() throws IOException {
        List<DocumentChunk> chunks = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            chunks.add(DocumentChunk.builder()
                    .id(3000L + i).documentId(400L).taskId(10L).planId(1L)
                    .chunkNo(i + 1)
                    .chunkText("This is test content number " + (i + 1) + " with keyword marker_" + i)
                    .build());
        }
        // 插入一条特殊标记
        chunks.add(DocumentChunk.builder()
                .id(3100L).documentId(400L).taskId(10L).planId(1L)
                .chunkNo(999)
                .chunkText("Occupational Safety and Health Administration compliance report")
                .build());

        gateway.indexChunks(chunks);
        esClient.indices().refresh(r -> r.index(TEST_INDEX));

        DocumentRetrieveRequest request = DocumentRetrieveRequest.builder()
                .query("Occupational Safety compliance")
                .topK(3)
                .build();

        List<org.springframework.ai.document.Document> results = gateway.search(request);
        assertThat(results).isNotEmpty();
        assertThat(results.get(0).getText())
                .isEqualTo("Occupational Safety and Health Administration compliance report");
    }
}
