package com.reubenagent.rag.config;

import com.zaxxer.hikari.HikariDataSource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * RAG 模块 PGVector 只读数据源配置 —— 创建独立的 HikariCP 连接池和 JdbcTemplate。
 *
 * <p>与 document 模块的 {@code DocumentPgVectorPool} 隔离，使用独立连接池名 {@code RagPgVectorPool}。
 * 此连接为只读，仅供检索查询使用。</p>
 *
 * @author reuben
 * @since 2026-06-21
 */
@Slf4j
@Configuration
@EnableConfigurationProperties(RagProperties.class)
@ConditionalOnProperty(prefix = "reuben.rag.pgvector", name = "enabled", havingValue = "true", matchIfMissing = true)
public class RagPgVectorConfiguration {

    /** 创建 RAG 专用的 PGVector HikariCP 连接池（只读）。 */
    @Bean(name = "ragPgVectorJdbcSupport")
    public RagPgVectorJdbcSupport ragPgVectorJdbcSupport(RagProperties properties) {
        RagProperties.Pgvector pg = properties.getPgvector();
        HikariDataSource dataSource = new HikariDataSource();
        dataSource.setDriverClassName("org.postgresql.Driver");
        dataSource.setJdbcUrl(buildJdbcUrl(pg));
        dataSource.setUsername(pg.getUsername());
        dataSource.setPassword(pg.getPassword());
        dataSource.setPoolName(pg.getPoolName());
        dataSource.setReadOnly(true);
        dataSource.setMaximumPoolSize(3);
        dataSource.setMinimumIdle(1);
        log.info("RAG PGVector 只读连接池已创建: {}", pg.getPoolName());
        return new RagPgVectorJdbcSupport(dataSource);
    }

    /** 从 JdbcSupport 中提取 JdbcTemplate，供 {@code VectorRetrievalChannel} 注入使用。 */
    @Bean(name = "ragPgVectorJdbcTemplate")
    public JdbcTemplate ragPgVectorJdbcTemplate(
            @Qualifier("ragPgVectorJdbcSupport") RagPgVectorJdbcSupport jdbcSupport) {
        return jdbcSupport.getJdbcTemplate();
    }

    private String buildJdbcUrl(RagProperties.Pgvector pg) {
        return "jdbc:postgresql://"
                + pg.getHost()
                + ":"
                + pg.getPort()
                + "/"
                + pg.getDatabase()
                + "?stringtype=unspecified";
    }

    /** HikariCP 数据源与 JdbcTemplate 封装，实现 DisposableBean 以在关闭时释放连接池。 */
    public static class RagPgVectorJdbcSupport implements DisposableBean {

        private final HikariDataSource dataSource;

        private final JdbcTemplate jdbcTemplate;

        public RagPgVectorJdbcSupport(HikariDataSource dataSource) {
            this.dataSource = dataSource;
            this.jdbcTemplate = new JdbcTemplate(dataSource);
        }

        public JdbcTemplate getJdbcTemplate() {
            return jdbcTemplate;
        }

        @Override
        public void destroy() {
            dataSource.close();
        }
    }
}
