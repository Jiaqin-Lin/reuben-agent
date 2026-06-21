package com.reubenagent.document.service.keyword;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.BulkRequest;
import co.elastic.clients.elasticsearch.core.BulkResponse;
import co.elastic.clients.elasticsearch.core.DeleteByQueryResponse;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import co.elastic.clients.elasticsearch.indices.CreateIndexRequest;
import co.elastic.clients.elasticsearch.indices.ExistsRequest;
import com.reubenagent.document.config.DocumentProperties;
import com.reubenagent.document.dto.DocumentRetrieveRequest;
import com.reubenagent.document.entity.DocumentChunk;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Elasticsearch 关键字搜索网关 —— 基于 ES 8.x 的 BM25 全文检索。
 *
 * <p>当 {@link ElasticsearchClient} bean 存在时自动注册，替代默认的
 * {@link NoOpKeywordSearchGateway}。索引在首次 {@link #indexChunks} 调用时懒创建。</p>
 *
 * <h3>功能</h3>
 * <ul>
 *   <li>{@code indexChunks} — 批量索引到 ES（_bulk API）</li>
 *   <li>{@code search} — BM25 全文检索，返回 Spring AI {@code Document} 列表</li>
 *   <li>{@code deleteByDocumentId} — 按文档 ID 删除全部索引（delete_by_query）</li>
 * </ul>
 *
 * @author reuben
 * @since 2026-06-21
 */
@Slf4j
@Component
@Primary
@ConditionalOnBean(ElasticsearchClient.class)
public class EsDocumentKeywordSearchGateway implements IDocumentKeywordSearchGateway {

    private static final String FIELD_DOCUMENT_ID = "documentId";
    private static final String FIELD_CHUNK_TEXT = "chunkText";

    private final ElasticsearchClient esClient;
    private final String indexName;
    private volatile boolean indexReady;

    public EsDocumentKeywordSearchGateway(ElasticsearchClient esClient, DocumentProperties properties) {
        this.esClient = esClient;
        this.indexName = properties.getElasticsearch().getIndexName();
    }

    @Override
    public void indexChunks(List<DocumentChunk> chunks) {
        if (chunks == null || chunks.isEmpty()) {
            log.debug("chunk 列表为空，跳过 ES 索引");
            return;
        }

        ensureIndex();

        // 过滤有效 chunk
        List<DocumentChunk> validChunks = chunks.stream()
                .filter(c -> c.getChunkText() != null && !c.getChunkText().isBlank())
                .toList();
        if (validChunks.isEmpty()) {
            log.debug("所有 chunk 文本均为空，跳过 ES 索引");
            return;
        }

        BulkRequest.Builder bulkBuilder = new BulkRequest.Builder();
        for (DocumentChunk chunk : validChunks) {
            bulkBuilder.operations(op -> op
                    .index(idx -> idx
                            .index(indexName)
                            .id(String.valueOf(chunk.getId()))
                            .document(toEsDocument(chunk))));
        }

        try {
            BulkResponse response = esClient.bulk(bulkBuilder.build());
            long failedCount = response.items().stream()
                    .filter(item -> item.error() != null)
                    .count();
            if (failedCount > 0) {
                log.warn("ES 批量索引部分失败: total={} failed={}", response.items().size(), failedCount);
            } else {
                log.info("ES 批量索引完成: chunkCount={}", response.items().size());
            }
        } catch (IOException e) {
            log.error("ES 批量索引异常: chunkCount={}", chunks.size(), e);
        } catch (RuntimeException e) {
            log.error("ES 批量索引异常: chunkCount={}", chunks.size(), e);
        }
    }

    @Override
    public List<org.springframework.ai.document.Document> search(DocumentRetrieveRequest request) {
        if (request == null || request.getQuery() == null || request.getQuery().isBlank()) {
            return Collections.emptyList();
        }

        ensureIndex();

        try {
            SearchResponse<Map> response = esClient.search(s -> {
                s.index(indexName)
                 .query(q -> q
                         .bool(b -> {
                             b.must(m -> m
                                     .match(mq -> mq
                                             .field(FIELD_CHUNK_TEXT)
                                             .query(request.getQuery())));
                             // 可选过滤字段
                             if (request.getFilterFields() != null) {
                                 request.getFilterFields().forEach((field, value) ->
                                         b.filter(f -> f
                                                 .term(tq -> tq
                                                         .field(field)
                                                         .value(value))));
                             }
                             return b;
                         }))
                 .size(request.getTopK() != null ? request.getTopK() : 10);

                if (request.getScoreThreshold() != null && request.getScoreThreshold() > 0) {
                    s.minScore(request.getScoreThreshold());
                }

                return s;
            }, Map.class);

            List<org.springframework.ai.document.Document> results = new ArrayList<>();
            for (Hit<Map> hit : response.hits().hits()) {
                Map<String, Object> source = hit.source();
                String chunkText = source != null
                        ? (String) source.getOrDefault(FIELD_CHUNK_TEXT, "")
                        : "";
                Map<String, Object> metadata = source != null
                        ? new LinkedHashMap<>(source)
                        : new LinkedHashMap<>();
                metadata.put("_score", hit.score());
                metadata.put("_index", hit.index());

                org.springframework.ai.document.Document doc =
                        new org.springframework.ai.document.Document(chunkText, metadata);
                results.add(doc);
            }

            log.debug("ES 全文检索完成: query={} topK={} hits={}",
                    request.getQuery(), request.getTopK(), results.size());
            return results;

        } catch (IOException e) {
            log.error("ES 全文检索异常: query={}", request.getQuery(), e);
            return Collections.emptyList();
        } catch (RuntimeException e) {
            log.error("ES 全文检索异常: query={}", request.getQuery(), e);
            return Collections.emptyList();
        }
    }

    @Override
    public void deleteByDocumentId(Long documentId) {
        if (documentId == null) {
            return;
        }

        ensureIndex();

        try {
            DeleteByQueryResponse response = esClient.deleteByQuery(d -> d
                    .index(indexName)
                    .refresh(true)
                    .query(q -> q
                            .term(tq -> tq
                                    .field(FIELD_DOCUMENT_ID)
                                    .value(documentId))));

            log.info("ES 已删除文档索引: documentId={} deleted={}", documentId, response.deleted());
        } catch (IOException e) {
            log.error("ES 删除文档索引异常: documentId={}", documentId, e);
        } catch (RuntimeException e) {
            log.error("ES 删除文档索引异常: documentId={}", documentId, e);
        }
    }

    // ============ 私有方法 ============

    /** 懒初始化索引——若索引不存在则创建。 */
    private void ensureIndex() {
        if (indexReady) {
            return;
        }
        synchronized (this) {
            if (indexReady) {
                return;
            }
            try {
                boolean exists = esClient.indices().exists(
                        ExistsRequest.of(e -> e.index(indexName))).value();
                if (!exists) {
                    esClient.indices().create(CreateIndexRequest.of(c -> c
                            .index(indexName)
                            .mappings(m -> m
                                    .properties(FIELD_DOCUMENT_ID, p -> p.long_(lp -> lp))
                                    .properties("taskId", p -> p.long_(lp -> lp))
                                    .properties("planId", p -> p.long_(lp -> lp))
                                    .properties("chunkId", p -> p.long_(lp -> lp))
                                    .properties("chunkNo", p -> p.integer(ip -> ip))
                                    .properties("parentBlockId", p -> p.long_(lp -> lp))
                                    .properties("sectionPath", p -> p.keyword(kp -> kp))
                                    .properties(FIELD_CHUNK_TEXT, p -> p.text(tp -> tp.analyzer("standard")))
                                    .properties("charCount", p -> p.integer(ip -> ip))
                                    .properties("sourceType", p -> p.integer(ip -> ip)))));
                    log.info("ES 索引已创建: indexName={}", indexName);
                }
                indexReady = true;
            } catch (IOException e) {
                log.error("ES 索引初始化失败: indexName={}", indexName, e);
            }
        }
    }

    /** 将 chunk 实体转为 ES 文档 Map。 */
    private Map<String, Object> toEsDocument(DocumentChunk chunk) {
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put(FIELD_DOCUMENT_ID, chunk.getDocumentId());
        doc.put("taskId", chunk.getTaskId());
        doc.put("planId", chunk.getPlanId());
        doc.put("chunkId", chunk.getId());
        doc.put("chunkNo", chunk.getChunkNo());
        doc.put("parentBlockId", chunk.getParentBlockId());
        doc.put("sectionPath", chunk.getSectionPath());
        doc.put(FIELD_CHUNK_TEXT, chunk.getChunkText());
        doc.put("charCount", chunk.getCharCount());
        doc.put("sourceType", chunk.getSourceType());
        return doc;
    }
}
