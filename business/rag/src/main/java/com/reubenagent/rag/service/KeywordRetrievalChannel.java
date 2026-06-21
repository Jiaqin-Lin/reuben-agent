package com.reubenagent.rag.service;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import com.reubenagent.rag.config.RagProperties;
import com.reubenagent.rag.constant.MetadataKeys;
import com.reubenagent.rag.model.RetrievalResult;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 关键词检索通道 —— 基于 Elasticsearch BM25 全文检索 chunk 文本。
 *
 * <p>防御性设计：ES Client 不可用或查询异常时返回空列表，不抛异常，
 * 允许上层编排降级到单通道（向量）继续服务。</p>
 *
 * @author reuben
 * @since 2026-06-21
 */
@Slf4j
@Component
@AllArgsConstructor
public class KeywordRetrievalChannel {

    private final ObjectProvider<ElasticsearchClient> esClientProvider;

    private final RagProperties ragProperties;

    /** 关键词检索入口。 */
    public List<RetrievalResult> retrieve(String query, int topK, Map<String, String> filters) {
        // 防御：query 为空 → 空列表
        if (query == null || query.isBlank()) {
            log.warn("关键词检索 query 为空，返回空列表");
            return List.of();
        }

        // 防御：ES Client 不可用 → 空列表
        ElasticsearchClient esClient = esClientProvider.getIfAvailable();
        if (esClient == null) {
            log.debug("ElasticsearchClient 不可用，关键词通道降级返回空列表");
            return List.of();
        }

        try {
            String indexName = ragProperties.getElasticsearch().getIndexName();
            List<RetrievalResult> results = executeSearch(esClient, indexName, query, topK, filters);

            log.debug("关键词检索完成: query={}, topK={}, hitCount={}", query, topK, results.size());
            return results;

        } catch (Exception e) {
            log.error("关键词检索异常，降级返回空列表: query={}", query, e);
            return List.of();
        }
    }

    /** 阶段 1：构建并执行 ES 搜索。 */
    private List<RetrievalResult> executeSearch(ElasticsearchClient esClient, String indexName,
                                                 String query, int topK, Map<String, String> filters)
            throws IOException {
        SearchResponse<Map> response = esClient.search(s -> {
            s.index(indexName)
             .query(q -> q
                     .bool(b -> {
                         b.must(m -> m
                                 .match(mq -> mq
                                         .field(MetadataKeys.CHUNK_TEXT)
                                         .query(query)));
                         if (filters != null && !filters.isEmpty()) {
                             filters.forEach((field, value) ->
                                     b.filter(f -> f
                                             .term(t -> t
                                                     .field(field)
                                                     .value(value))));
                         }
                         return b;
                     }))
             .size(topK);

            return s;
        }, Map.class);

        return mapHits(response);
    }

    /** 阶段 2：将 ES 命中的 Hit 映射为 RetrievalResult。 */
    private List<RetrievalResult> mapHits(SearchResponse<Map> response) {
        List<RetrievalResult> results = new ArrayList<>();
        for (Hit<Map> hit : response.hits().hits()) {
            Map<String, Object> source = hit.source();
            if (source == null) {
                continue;
            }

            RetrievalResult result = RetrievalResult.builder()
                    .chunkId(getLong(source, MetadataKeys.CHUNK_ID))
                    .chunkText((String) source.get(MetadataKeys.CHUNK_TEXT))
                    .score(hit.score())
                    .sectionPath((String) source.get(MetadataKeys.SECTION_PATH))
                    .documentId(getLong(source, MetadataKeys.DOCUMENT_ID))
                    .parentBlockId(getLong(source, MetadataKeys.PARENT_BLOCK_ID))
                    .source("keyword")
                    .build();
            results.add(result);
        }
        return results;
    }

    /** 从 Map 中安全获取 Long（处理 Integer → Long 转换）。 */
    private Long getLong(Map<String, Object> source, String key) {
        Object value = source.get(key);
        if (value == null) {
            return null;
        }
        if (value instanceof Long l) {
            return l;
        }
        if (value instanceof Integer i) {
            return i.longValue();
        }
        if (value instanceof String s) {
            try {
                return Long.parseLong(s);
            } catch (NumberFormatException e) {
                log.debug("字段 {} 值无法解析为 Long: {}", key, s);
                return null;
            }
        }
        return null;
    }
}
