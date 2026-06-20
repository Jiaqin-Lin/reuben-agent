package com.reubenagent.document.config;

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
 * PostgreSQL + pgvector 数据源配置 —— 为文档向量网关创建独立的 HikariCP 连接池和 JdbcTemplate。
 *
 * <p>pgvector 是 PostgreSQL 的向量扩展，用于存储和检索文档切块的向量嵌入。
 * 本配置创建独立于 MySQL 主数据源的连接池，供 {@code DefaultDocumentVectorGateway} 使用。</p>
 *
 * @author reuben
 * @since 2026-06-20
 */
@Slf4j
@Configuration
@EnableConfigurationProperties(DocumentProperties.class)
@ConditionalOnProperty(prefix = "reuben.document.pgvector", name = "enabled", havingValue = "true", matchIfMissing = true)
public class DocumentPgVectorConfiguration {

    /** 创建 pgvector 专用的 HikariCP 连接池和 JdbcTemplate 封装。 */
    @Bean(name = "documentPgVectorJdbcSupport")
    public DocumentPgVectorJdbcSupport documentPgVectorJdbcSupport(DocumentProperties properties) {
        DocumentProperties.Pgvector pg = properties.getPgvector();
        HikariDataSource dataSource = new HikariDataSource();
        dataSource.setDriverClassName("org.postgresql.Driver");
        dataSource.setJdbcUrl(buildJdbcUrl(pg));
        dataSource.setUsername(pg.getUsername());
        dataSource.setPassword(pg.getPassword());
        dataSource.setPoolName(pg.getPoolName());
        dataSource.setMaximumPoolSize(pg.getMaximumPoolSize());
        dataSource.setMinimumIdle(pg.getMinimumIdle());
        log.info("PGVector 连接池已创建: {}", pg.getPoolName());
        return new DocumentPgVectorJdbcSupport(dataSource);
    }

    /** 从 JdbcSupport 中提取 JdbcTemplate，供 Gateway 层注入使用。 */
    @Bean(name = "documentPgVectorJdbcTemplate")
    public JdbcTemplate documentPgVectorJdbcTemplate(
            @Qualifier("documentPgVectorJdbcSupport") DocumentPgVectorJdbcSupport jdbcSupport) {
        return jdbcSupport.getJdbcTemplate();
    }

    private String buildJdbcUrl(DocumentProperties.Pgvector pg) {
        return "jdbc:postgresql://"
                + pg.getHost()
                + ":"
                + pg.getPort()
                + "/"
                + pg.getDatabase()
                + "?stringtype=unspecified";
    }

    /** HikariCP 数据源与 JdbcTemplate 封装，实现 DisposableBean 以在关闭时释放连接池。 */
    public static class DocumentPgVectorJdbcSupport implements DisposableBean {

        private final HikariDataSource dataSource;

        private final JdbcTemplate jdbcTemplate;

        public DocumentPgVectorJdbcSupport(HikariDataSource dataSource) {
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
