package com.reubenagent.document.config;

import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.neo4j.driver.AuthTokens;
import org.neo4j.driver.Config;
import org.neo4j.driver.Driver;
import org.neo4j.driver.GraphDatabase;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.TimeUnit;

/**
 * Neo4j 条件配置 —— 仅在 {@code reuben.document.neo4j.enabled=true} 时创建独立 Driver Bean。
 *
 * <p>使用原生 {@link Driver}（非 Spring Data Neo4j），与 {@code spring.neo4j} 自动配置解耦。
 * Bean 名即方法名 {@code documentManageNeo4jDriver}，下游服务按此名注入。</p>
 *
 * @author reuben
 * @since 2026-06-28
 */
@Slf4j
@Configuration
@AllArgsConstructor
@ConditionalOnProperty(prefix = "reuben.document.neo4j", name = "enabled", havingValue = "true")
public class DocumentManageNeo4jConfiguration {

    private final DocumentProperties properties;

    @Bean(destroyMethod = "close")
    public Driver documentManageNeo4jDriver() {
        DocumentProperties.Neo4j neo4j = properties.getNeo4j();
        Config config = Config.builder()
                .withConnectionTimeout(neo4j.getQueryTimeoutSeconds(), TimeUnit.SECONDS)
                .withMaxConnectionPoolSize(neo4j.getMaxConnectionPoolSize())
                .withConnectionAcquisitionTimeout(neo4j.getConnectionAcquisitionTimeoutMs(), TimeUnit.MILLISECONDS)
                .build();
        Driver driver = GraphDatabase.driver(
                neo4j.getUri(),
                AuthTokens.basic(neo4j.getUsername(), neo4j.getPassword()),
                config);
        log.info("Neo4j Driver 已创建: uri={} database={} poolSize={}",
                neo4j.getUri(), neo4j.getDatabase(), neo4j.getMaxConnectionPoolSize());
        return driver;
    }
}
