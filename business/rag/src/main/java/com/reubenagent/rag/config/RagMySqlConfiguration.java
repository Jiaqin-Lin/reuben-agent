package com.reubenagent.rag.config;

import com.zaxxer.hikari.HikariDataSource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * RAG 模块 MySQL 只读数据源配置 —— 用于查询 parent block 文本。
 *
 * <p>与 document 模块的 MySQL DataSource 隔离，使用独立连接池名 {@code RagMySqlPool}。
 * 此连接为只读，仅供 parent block elevation 查询使用。</p>
 *
 * @author reuben
 * @since 2026-06-22
 */
@Slf4j
@Configuration
@EnableConfigurationProperties(RagProperties.class)
public class RagMySqlConfiguration {

    /** 创建 RAG 专用的 MySQL HikariCP 连接池（只读）。 */
    @Bean(name = "ragMySqlJdbcSupport")
    public RagMySqlJdbcSupport ragMySqlJdbcSupport(RagProperties properties) {
        RagProperties.Mysql mysql = properties.getMysql();
        HikariDataSource dataSource = new HikariDataSource();
        dataSource.setDriverClassName("com.mysql.cj.jdbc.Driver");
        dataSource.setJdbcUrl(buildJdbcUrl(mysql));
        dataSource.setUsername(mysql.getUsername());
        dataSource.setPassword(mysql.getPassword());
        dataSource.setPoolName(mysql.getPoolName());
        dataSource.setReadOnly(mysql.isReadOnly());
        dataSource.setMaximumPoolSize(3);
        dataSource.setMinimumIdle(1);
        log.info("RAG MySQL 只读连接池已创建: {}", mysql.getPoolName());
        return new RagMySqlJdbcSupport(dataSource);
    }

    /** 从 JdbcSupport 中提取 JdbcTemplate，供 ParentBlockElevationService 注入使用。 */
    @Bean(name = "ragMySqlJdbcTemplate")
    public JdbcTemplate ragMySqlJdbcTemplate(
            @Qualifier("ragMySqlJdbcSupport") RagMySqlJdbcSupport jdbcSupport) {
        return jdbcSupport.getJdbcTemplate();
    }

    private String buildJdbcUrl(RagProperties.Mysql mysql) {
        return "jdbc:mysql://"
                + mysql.getHost()
                + ":"
                + mysql.getPort()
                + "/"
                + mysql.getDatabase()
                + "?useUnicode=true&characterEncoding=UTF-8&serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true";
    }

    /** HikariCP 数据源与 JdbcTemplate 封装，实现 DisposableBean 以在关闭时释放连接池。 */
    public static class RagMySqlJdbcSupport implements DisposableBean {

        private final HikariDataSource dataSource;

        private final JdbcTemplate jdbcTemplate;

        public RagMySqlJdbcSupport(HikariDataSource dataSource) {
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
