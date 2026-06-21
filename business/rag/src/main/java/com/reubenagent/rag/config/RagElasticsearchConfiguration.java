package com.reubenagent.rag.config;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import jakarta.annotation.PostConstruct;

/**
 * RAG 模块 Elasticsearch 配置 —— 复用 Spring Boot 自动配置的 {@link ElasticsearchClient}。
 *
 * <p>不创建新的 ES 连接，仅验证已有 Bean 可用性。
 * 当 ES 不可用时，{@code KeywordRetrievalChannel} 通过 {@code ObjectProvider} 做防御性降级。</p>
 *
 * @author reuben
 * @since 2026-06-21
 */
@Slf4j
@Configuration
@EnableConfigurationProperties(RagProperties.class)
@ConditionalOnBean(ElasticsearchClient.class)
public class RagElasticsearchConfiguration {

    @PostConstruct
    void logEsStatus() {
        log.info("ElasticsearchClient 已就绪，关键词检索通道可用");
    }
}
