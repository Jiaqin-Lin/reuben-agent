package com.reubenagent.document.config;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.indices.CreateIndexRequest;
import co.elastic.clients.elasticsearch.indices.ExistsRequest;
import com.reubenagent.document.config.DocumentProperties.Elasticsearch;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * 导航章节 ES 索引初始化器 —— 应用启动后检查并创建 {@code reuben_agent_document_navigation} 索引。
 *
 * @author reuben
 * @since 2026-06-28
 */
@Slf4j
@Component
@AllArgsConstructor
public class DocumentNavigationElasticsearchIndexInitializer {

    private final ObjectProvider<ElasticsearchClient> esClientProvider;
    private final DocumentProperties properties;

    @EventListener(ApplicationReadyEvent.class)
    public void initialize() {
        ElasticsearchClient esClient = esClientProvider.getIfAvailable();
        if (esClient == null) {
            log.info("ElasticsearchClient 不可用，跳过导航索引初始化");
            return;
        }

        Elasticsearch esProps = properties.getElasticsearch();
        String indexName = esProps.getNavigationIndexName();

        try {
            boolean exists = esClient.indices().exists(
                    ExistsRequest.of(e -> e.index(indexName))).value();
            if (exists) {
                log.info("导航 ES 索引已存在: indexName={}", indexName);
                return;
            }

            String analyzer = esProps.getAnalyzer();
            String searchAnalyzer = esProps.getSearchAnalyzer();
            String effectiveAnalyzer = analyzer;
            String effectiveSearchAnalyzer = searchAnalyzer;

            try {
                esClient.indices().create(buildCreateRequest(indexName, analyzer, searchAnalyzer));
            } catch (Exception e) {
                log.warn("使用 analyzer={} 创建导航索引失败，回退 standard: indexName={} error={}",
                        analyzer, indexName, e.getMessage());
                effectiveAnalyzer = "standard";
                effectiveSearchAnalyzer = "standard";
                esClient.indices().create(buildCreateRequest(indexName, effectiveAnalyzer, effectiveSearchAnalyzer));
            }

            log.info("导航 ES 索引已创建: indexName={} analyzer={} searchAnalyzer={}",
                    indexName, effectiveAnalyzer, effectiveSearchAnalyzer);

        } catch (IOException e) {
            log.error("导航 ES 索引初始化失败: indexName={}", indexName, e);
        }
    }

    private CreateIndexRequest buildCreateRequest(String indexName, String analyzer, String searchAnalyzer) {
        String indexTokenizer = tokenizerOf(analyzer);
        String searchTokenizer = tokenizerOf(searchAnalyzer);
        return CreateIndexRequest.of(c -> c
                .index(indexName)
                .settings(s -> s
                        .numberOfShards("1")
                        .numberOfReplicas("0")
                        .analysis(a -> a
                                .analyzer("default_analyzer", da -> da
                                        .custom(ca -> ca
                                                .tokenizer(indexTokenizer)))
                                .analyzer("default_search_analyzer", dsa -> dsa
                                        .custom(csa -> csa
                                                .tokenizer(searchTokenizer)))))
                .mappings(m -> m
                        .properties("nodeId", p -> p.long_(lp -> lp))
                        .properties("documentId", p -> p.long_(lp -> lp))
                        .properties("parseTaskId", p -> p.long_(lp -> lp))
                        .properties("parentNodeId", p -> p.long_(lp -> lp))
                        .properties("nodeNo", p -> p.integer(ip -> ip))
                        .properties("depth", p -> p.integer(ip -> ip))
                        .properties("itemIndex", p -> p.integer(ip -> ip))
                        .properties("nodeType", p -> p.keyword(k -> k))
                        .properties("nodeCode", p -> p.keyword(k -> k))
                        .properties("canonicalPath", p -> p.keyword(k -> k))
                        .properties("title", p -> p.text(t -> t.analyzer(analyzer).searchAnalyzer(searchAnalyzer)))
                        .properties("anchorText", p -> p.text(t -> t.analyzer(analyzer).searchAnalyzer(searchAnalyzer)))
                        .properties("sectionPath", p -> p.text(t -> t.analyzer(analyzer).searchAnalyzer(searchAnalyzer)))
                        .properties("contentText", p -> p.text(t -> t.analyzer(analyzer).searchAnalyzer(searchAnalyzer)))));
    }

    /** 自定义 analyzer 需要 tokenizer 名而非 analyzer 名：standard analyzer → standard tokenizer */
    private static String tokenizerOf(String analyzer) {
        if (analyzer == null || analyzer.isBlank() || "standard".equals(analyzer)) {
            return "standard";
        }
        return analyzer;
    }
}
