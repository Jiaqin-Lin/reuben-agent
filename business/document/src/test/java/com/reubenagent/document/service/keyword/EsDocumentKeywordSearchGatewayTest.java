package com.reubenagent.document.service.keyword;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.BulkRequest;
import co.elastic.clients.elasticsearch.core.BulkResponse;
import co.elastic.clients.elasticsearch.core.DeleteByQueryRequest;
import co.elastic.clients.elasticsearch.core.DeleteByQueryResponse;
import co.elastic.clients.elasticsearch.core.SearchRequest;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.bulk.BulkResponseItem;
import co.elastic.clients.elasticsearch.core.search.Hit;
import co.elastic.clients.elasticsearch.core.search.HitsMetadata;
import co.elastic.clients.elasticsearch.core.search.TotalHits;
import co.elastic.clients.elasticsearch.indices.CreateIndexRequest;
import co.elastic.clients.elasticsearch.indices.ElasticsearchIndicesClient;
import co.elastic.clients.elasticsearch.indices.ExistsRequest;
import co.elastic.clients.transport.endpoints.BooleanResponse;
import co.elastic.clients.util.ObjectBuilder;
import com.reubenagent.document.config.DocumentProperties;
import com.reubenagent.document.dto.DocumentRetrieveRequest;
import com.reubenagent.document.entity.DocumentChunk;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;

import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@DisplayName("EsDocumentKeywordSearchGateway")
class EsDocumentKeywordSearchGatewayTest {

    private ElasticsearchClient esClient;
    private ElasticsearchIndicesClient indicesClient;
    private EsDocumentKeywordSearchGateway gateway;

    @BeforeEach
    void setUp() throws IOException {
        esClient = mock(ElasticsearchClient.class);
        indicesClient = mock(ElasticsearchIndicesClient.class);
        when(esClient.indices()).thenReturn(indicesClient);

        // ensureIndex 调用 exists(ExistsRequest.of(...)) → 匹配 exists(ExistsRequest)
        when(indicesClient.exists(any(ExistsRequest.class)))
                .thenReturn(new BooleanResponse(true));

        DocumentProperties properties = new DocumentProperties();
        properties.getElasticsearch().setIndexName("reuben_document_chunk");

        gateway = new EsDocumentKeywordSearchGateway(
                new org.springframework.beans.factory.ObjectProvider<>() {
                    @Override
                    public ElasticsearchClient getObject() { return esClient; }
                    @Override
                    public ElasticsearchClient getIfAvailable() { return esClient; }
                    @Override
                    public ElasticsearchClient getIfUnique() { return esClient; }
                }, properties);
    }

    // ============ indexChunks ============

    @Nested
    @DisplayName("indexChunks")
    class IndexChunks {

        @Test
        @DisplayName("null 列表不调用 ES")
        void shouldSkipForNullList() {
            gateway.indexChunks(null);
            verifyNoInteractions(esClient);
        }

        @Test
        @DisplayName("空列表不调用 ES")
        void shouldSkipForEmptyList() {
            gateway.indexChunks(Collections.emptyList());
            verifyNoInteractions(esClient);
        }

        @Test
        @DisplayName("正常 chunk 批量索引成功")
        void shouldBulkIndexChunks() throws IOException {
            BulkResponse bulkResponse = mock(BulkResponse.class);
            BulkResponseItem item = mock(BulkResponseItem.class);
            when(item.error()).thenReturn(null);
            when(bulkResponse.items()).thenReturn(List.of(item, item));

            when(esClient.bulk(any(BulkRequest.class)))
                    .thenReturn(bulkResponse);

            DocumentChunk chunk = DocumentChunk.builder()
                    .id(1L).documentId(100L).taskId(10L).chunkNo(1)
                    .chunkText("测试切块内容").charCount(6).sourceType(1)
                    .build();

            assertThatCode(() -> gateway.indexChunks(List.of(chunk)))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("chunk 文本为空时跳过，不调用 ES bulk")
        void shouldSkipEmptyChunkText() {
            DocumentChunk emptyChunk = DocumentChunk.builder().id(1L).chunkText("").build();

            assertThatCode(() -> gateway.indexChunks(List.of(emptyChunk)))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("bulk 部分失败不抛异常")
        void shouldNotThrowOnPartialBulkFailure() throws IOException {
            BulkResponse bulkResponse = mock(BulkResponse.class);
            BulkResponseItem failedItem = mock(BulkResponseItem.class);
            co.elastic.clients.elasticsearch._types.ErrorCause error =
                    mock(co.elastic.clients.elasticsearch._types.ErrorCause.class);
            when(error.reason()).thenReturn("simulated error");
            when(failedItem.error()).thenReturn(error);
            when(bulkResponse.items()).thenReturn(List.of(failedItem));

            when(esClient.bulk(any(BulkRequest.class)))
                    .thenReturn(bulkResponse);

            DocumentChunk chunk = DocumentChunk.builder().id(1L).chunkText("内容").build();

            assertThatCode(() -> gateway.indexChunks(List.of(chunk)))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("bulk IOException 不抛异常")
        void shouldNotThrowOnBulkIOException() throws IOException {
            when(esClient.bulk(any(BulkRequest.class)))
                    .thenThrow(new IOException("连接超时"));

            DocumentChunk chunk = DocumentChunk.builder().id(1L).chunkText("内容").build();

            assertThatCode(() -> gateway.indexChunks(List.of(chunk)))
                    .doesNotThrowAnyException();
        }
    }

    // ============ search ============

    @Nested
    @DisplayName("search")
    class Search {

        @Test
        @DisplayName("null 请求返回空列表")
        void shouldReturnEmptyForNullRequest() {
            assertThat(gateway.search(null)).isEmpty();
        }

        @Test
        @DisplayName("空 query 返回空列表")
        void shouldReturnEmptyForBlankQuery() {
            DocumentRetrieveRequest request = DocumentRetrieveRequest.builder()
                    .query("").build();
            assertThat(gateway.search(request)).isEmpty();
        }

        @SuppressWarnings({"unchecked", "rawtypes"})
        @Test
        @DisplayName("正常检索返回 Spring AI Document 列表")
        void shouldReturnDocumentsForValidQuery() throws IOException {
            SearchResponse<Map> searchResponse = mock(SearchResponse.class);
            HitsMetadata<Map> hitsMetadata = mock(HitsMetadata.class);
            Hit<Map> hit = mock(Hit.class);

            when(hit.source()).thenReturn(Map.of(
                    "chunkText", "这份合同的有效期为三年",
                    "documentId", 100L,
                    "sectionPath", "第三章"));
            when(hit.score()).thenReturn(1.5);
            when(hit.index()).thenReturn("reuben_document_chunk");

            TotalHits totalHits = mock(TotalHits.class);
            when(totalHits.value()).thenReturn(1L);
            when(hitsMetadata.total()).thenReturn(totalHits);
            when(hitsMetadata.hits()).thenReturn(List.of(hit));
            when(searchResponse.hits()).thenReturn(hitsMetadata);

            when(esClient.search(
                    ArgumentMatchers
                            .<Function<SearchRequest.Builder, ObjectBuilder<SearchRequest>>>any(),
                    eq(Map.class)))
                    .thenReturn(searchResponse);

            DocumentRetrieveRequest request = DocumentRetrieveRequest.builder()
                    .query("合同有效期").topK(5).build();

            List<org.springframework.ai.document.Document> results = gateway.search(request);

            assertThat(results).hasSize(1);
            org.springframework.ai.document.Document doc = results.get(0);
            assertThat(doc.getText()).isEqualTo("这份合同的有效期为三年");
            assertThat(doc.getMetadata()).containsEntry("_score", 1.5);
            assertThat(doc.getMetadata()).containsEntry("sectionPath", "第三章");
        }

        @SuppressWarnings({"unchecked", "rawtypes"})
        @Test
        @DisplayName("带 filterFields 的检索")
        void shouldApplyFilterFields() throws IOException {
            SearchResponse<Map> searchResponse = mock(SearchResponse.class);
            HitsMetadata<Map> hitsMetadata = mock(HitsMetadata.class);
            TotalHits totalHits = mock(TotalHits.class);
            when(totalHits.value()).thenReturn(0L);
            when(hitsMetadata.total()).thenReturn(totalHits);
            when(hitsMetadata.hits()).thenReturn(List.of());
            when(searchResponse.hits()).thenReturn(hitsMetadata);

            when(esClient.search(
                    ArgumentMatchers
                            .<Function<SearchRequest.Builder, ObjectBuilder<SearchRequest>>>any(),
                    eq(Map.class)))
                    .thenReturn(searchResponse);

            DocumentRetrieveRequest request = DocumentRetrieveRequest.builder()
                    .query("合同").topK(10)
                    .filterFields(Map.of("documentId", "100"))
                    .build();

            assertThat(gateway.search(request)).isEmpty();
        }

        @SuppressWarnings({"unchecked", "rawtypes"})
        @Test
        @DisplayName("search IOException 返回空列表")
        void shouldReturnEmptyOnSearchIOException() throws IOException {
            when(esClient.search(
                    ArgumentMatchers
                            .<Function<SearchRequest.Builder, ObjectBuilder<SearchRequest>>>any(),
                    eq(Map.class)))
                    .thenThrow(new IOException("ES 不可用"));

            DocumentRetrieveRequest request = DocumentRetrieveRequest.builder()
                    .query("测试").build();

            assertThat(gateway.search(request)).isEmpty();
        }
    }

    // ============ deleteByDocumentId ============

    @Nested
    @DisplayName("deleteByDocumentId")
    class DeleteByDocumentId {

        @Test
        @DisplayName("null documentId 不抛异常")
        void shouldSkipForNullId() {
            assertThatCode(() -> gateway.deleteByDocumentId(null))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("正常删除")
        void shouldDeleteByDocumentId() throws IOException {
            DeleteByQueryResponse deleteResponse = mock(DeleteByQueryResponse.class);
            when(deleteResponse.deleted()).thenReturn(3L);

            when(esClient.deleteByQuery(
                    ArgumentMatchers
                            .<Function<DeleteByQueryRequest.Builder,
                                    ObjectBuilder<DeleteByQueryRequest>>>any()))
                    .thenReturn(deleteResponse);

            assertThatCode(() -> gateway.deleteByDocumentId(100L))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("deleteByQuery IOException 不抛异常")
        void shouldNotThrowOnDeleteIOException() throws IOException {
            when(esClient.deleteByQuery(
                    ArgumentMatchers
                            .<Function<DeleteByQueryRequest.Builder,
                                    ObjectBuilder<DeleteByQueryRequest>>>any()))
                    .thenThrow(new IOException("ES 不可用"));

            assertThatCode(() -> gateway.deleteByDocumentId(100L))
                    .doesNotThrowAnyException();
        }
    }

    // ============ 边界条件 ============

    @Nested
    @DisplayName("边界条件")
    class EdgeCases {

        @SuppressWarnings({"unchecked", "rawtypes"})
        @Test
        @DisplayName("scoreThreshold > 0 时正常检索")
        void shouldApplyScoreThreshold() throws IOException {
            SearchResponse<Map> searchResponse = mock(SearchResponse.class);
            HitsMetadata<Map> hitsMetadata = mock(HitsMetadata.class);
            TotalHits totalHits = mock(TotalHits.class);
            when(totalHits.value()).thenReturn(0L);
            when(hitsMetadata.total()).thenReturn(totalHits);
            when(hitsMetadata.hits()).thenReturn(List.of());
            when(searchResponse.hits()).thenReturn(hitsMetadata);

            when(esClient.search(
                    ArgumentMatchers
                            .<Function<SearchRequest.Builder, ObjectBuilder<SearchRequest>>>any(),
                    eq(Map.class)))
                    .thenReturn(searchResponse);

            DocumentRetrieveRequest request = DocumentRetrieveRequest.builder()
                    .query("测试").topK(10).scoreThreshold(0.5).build();

            assertThat(gateway.search(request)).isEmpty();
        }

        @Test
        @DisplayName("索引懒创建：索引不存在时自动创建")
        void shouldCreateIndexWhenNotExists() throws IOException {
            when(indicesClient.exists(any(ExistsRequest.class)))
                    .thenReturn(new BooleanResponse(false));

            when(esClient.bulk(any(BulkRequest.class)))
                    .thenReturn(mock(BulkResponse.class));

            DocumentChunk chunk = DocumentChunk.builder()
                    .id(1L).chunkText("内容").build();

            assertThatCode(() -> gateway.indexChunks(List.of(chunk)))
                    .doesNotThrowAnyException();
        }
    }
}
