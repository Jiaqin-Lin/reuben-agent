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
 * 知识路由 ES 索引初始化器 —— 应用启动后检查并创建 {@code reuben_agent_knowledge_route} 索引。
 *
 * <p>使用 {@link ApplicationReadyEvent} 确保 ES Client 已就绪。analyzer 尝试使用
 * {@code ik_max_word} / {@code ik_smart}，失败时回退 {@code standard} 并打 warn 日志。</p>
 *
 * @author reuben
 * @since 2026-06-28
 */
@Slf4j
@Component
@AllArgsConstructor
public class KnowledgeRouteElasticsearchIndexInitializer {

    private final ObjectProvider<ElasticsearchClient> esClientProvider;
    private final DocumentProperties properties;

    @EventListener(ApplicationReadyEvent.class)
    public void initialize() {
        ElasticsearchClient esClient = esClientProvider.getIfAvailable();
        if (esClient == null) {
            log.info("ElasticsearchClient 不可用，跳过知识路由索引初始化");
            return;
        }

        Elasticsearch esProps = properties.getElasticsearch();
        String indexName = esProps.getRouteIndexName();

        try {
            boolean exists = esClient.indices().exists(
                    ExistsRequest.of(e -> e.index(indexName))).value();
            if (exists) {
                log.info("知识路由 ES 索引已存在: indexName={}", indexName);
                return;
            }

            String analyzer = esProps.getAnalyzer();
            String searchAnalyzer = esProps.getSearchAnalyzer();
            String effectiveAnalyzer = analyzer;
            String effectiveSearchAnalyzer = searchAnalyzer;

            try {
                esClient.indices().create(buildCreateRequest(indexName, analyzer, searchAnalyzer));
            } catch (Exception e) {
                log.warn("使用 analyzer={} 创建索引失败，回退 standard: indexName={} error={}",
                        analyzer, indexName, e.getMessage());
                effectiveAnalyzer = "standard";
                effectiveSearchAnalyzer = "standard";
                esClient.indices().create(buildCreateRequest(indexName, effectiveAnalyzer, effectiveSearchAnalyzer));
            }

            log.info("知识路由 ES 索引已创建: indexName={} analyzer={} searchAnalyzer={}",
                    indexName, effectiveAnalyzer, effectiveSearchAnalyzer);

        } catch (IOException e) {
            log.error("知识路由 ES 索引初始化失败: indexName={}", indexName, e);
        }
    }

    private CreateIndexRequest buildCreateRequest(String indexName, String analyzer, String searchAnalyzer) {
        return CreateIndexRequest.of(c -> c
                .index(indexName)
                .settings(s -> s
                        .numberOfShards("1")
                        .numberOfReplicas("0")
                        .analysis(a -> a
                                .analyzer("default_analyzer", da -> da
                                        .custom(ca -> ca
                                                .tokenizer("ik_max_word")))
                                .analyzer("default_search_analyzer", dsa -> dsa
                                        .custom(csa -> csa
                                                .tokenizer("ik_smart")))))
                .mappings(m -> m
                        .properties("routeId", p -> p.keyword(k -> k))
                        .properties("entityType", p -> p.keyword(k -> k))
                        .properties("entityCode", p -> p.keyword(k -> k))
                        .properties("documentId", p -> p.long_(lp -> lp))
                        .properties("scopeCode", p -> p.keyword(k -> k))
                        .properties("scopeName", p -> p.text(t -> t.analyzer(analyzer).searchAnalyzer(searchAnalyzer)))
                        .properties("topicCode", p -> p.keyword(k -> k))
                        .properties("topicName", p -> p.text(t -> t.analyzer(analyzer).searchAnalyzer(searchAnalyzer)))
                        .properties("documentName", p -> p.text(t -> t.analyzer(analyzer).searchAnalyzer(searchAnalyzer)))
                        .properties("businessCategory", p -> p.keyword(k -> k))
                        .properties("displayName", p -> p.text(t -> t.analyzer(analyzer).searchAnalyzer(searchAnalyzer)))
                        .properties("descriptionText", p -> p.text(t -> t.analyzer(analyzer).searchAnalyzer(searchAnalyzer)))
                        .properties("aliasesText", p -> p.text(t -> t.analyzer(analyzer).searchAnalyzer(searchAnalyzer)))
                        .properties("examplesText", p -> p.text(t -> t.analyzer(analyzer).searchAnalyzer(searchAnalyzer)))
                        .properties("summaryText", p -> p.text(t -> t.analyzer(analyzer).searchAnalyzer(searchAnalyzer)))
                        .properties("routeText", p -> p.text(t -> t.analyzer(analyzer).searchAnalyzer(searchAnalyzer)))
                        .properties("entityTerms", p -> p.keyword(k -> k))
                        .properties("tags", p -> p.keyword(k -> k))));
    }
}
