package com.reubenagent.document.service.keyword;

import com.reubenagent.document.dto.DocumentRetrieveRequest;
import com.reubenagent.document.entity.DocumentChunk;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

@DisplayName("NoOpKeywordSearchGateway")
class NoOpKeywordSearchGatewayTest {

    private final NoOpKeywordSearchGateway gateway = new NoOpKeywordSearchGateway();

    // ============ indexChunks ============

    @Nested
    @DisplayName("indexChunks")
    class IndexChunks {

        @Test
        @DisplayName("非空 chunk 列表不抛异常")
        void shouldNotThrowForNonEmptyChunks() {
            DocumentChunk chunk = DocumentChunk.builder()
                    .id(1L)
                    .chunkText("测试内容")
                    .build();

            assertThatCode(() -> gateway.indexChunks(List.of(chunk)))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("null 列表不抛异常")
        void shouldNotThrowForNullList() {
            assertThatCode(() -> gateway.indexChunks(null))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("空列表不抛异常")
        void shouldNotThrowForEmptyList() {
            assertThatCode(() -> gateway.indexChunks(Collections.emptyList()))
                    .doesNotThrowAnyException();
        }
    }

    // ============ search ============

    @Nested
    @DisplayName("search")
    class Search {

        @Test
        @DisplayName("返回空列表")
        void shouldReturnEmptyList() {
            DocumentRetrieveRequest request = DocumentRetrieveRequest.builder()
                    .query("测试查询")
                    .topK(10)
                    .build();

            List<org.springframework.ai.document.Document> results = gateway.search(request);
            assertThat(results).isEmpty();
        }

        @Test
        @DisplayName("null 请求返回空列表")
        void shouldReturnEmptyListForNullRequest() {
            List<org.springframework.ai.document.Document> results = gateway.search(null);
            assertThat(results).isEmpty();
        }
    }

    // ============ deleteByDocumentId ============

    @Nested
    @DisplayName("deleteByDocumentId")
    class DeleteByDocumentId {

        @Test
        @DisplayName("有效 documentId 不抛异常")
        void shouldNotThrowForValidId() {
            assertThatCode(() -> gateway.deleteByDocumentId(1L))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("null documentId 不抛异常")
        void shouldNotThrowForNullId() {
            assertThatCode(() -> gateway.deleteByDocumentId(null))
                    .doesNotThrowAnyException();
        }
    }
}
