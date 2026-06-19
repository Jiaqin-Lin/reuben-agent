package com.reubenagent.document;

import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 文档模块集成测试 DDL —— 所有测试类共享的唯一建表逻辑。
 *
 * <p>表结构与实体 {@code @TableName} 注解一致（{@code reuben_agent_} 前缀）。</p>
 */
public final class DocumentTestSchema {

    private DocumentTestSchema() {
    }

    /** 删除所有测试表（先子后父，避免外键约束冲突） */
    public static void dropTables(JdbcTemplate jdbcTemplate) {
        jdbcTemplate.execute("DROP TABLE IF EXISTS reuben_agent_document_task_log");
        jdbcTemplate.execute("DROP TABLE IF EXISTS reuben_agent_document_task");
        jdbcTemplate.execute("DROP TABLE IF EXISTS reuben_agent_document_structure_node");
        jdbcTemplate.execute("DROP TABLE IF EXISTS reuben_agent_document");
    }

    /** 创建 document 表 */
    public static void createDocumentTable(JdbcTemplate jdbcTemplate) {
        jdbcTemplate.execute("""
            CREATE TABLE reuben_agent_document (
                id              BIGINT        NOT NULL PRIMARY KEY,
                document_name   VARCHAR(512)  DEFAULT NULL,
                original_file_name VARCHAR(512) DEFAULT NULL,
                file_type       TINYINT       DEFAULT NULL,
                media_type      VARCHAR(128)  DEFAULT NULL,
                file_size       BIGINT        DEFAULT NULL,
                storage_type    TINYINT       DEFAULT NULL,
                bucket_name     VARCHAR(128)  DEFAULT NULL,
                object_name     VARCHAR(512)  DEFAULT NULL,
                object_url      VARCHAR(1024) DEFAULT NULL,
                parse_status    TINYINT       DEFAULT 1,
                strategy_status TINYINT       DEFAULT 1,
                index_status    TINYINT       DEFAULT 1,
                char_count      INT           DEFAULT NULL,
                token_count     INT           DEFAULT NULL,
                structure_level TINYINT       DEFAULT 0,
                content_quality_level TINYINT DEFAULT 0,
                parse_success_text_path VARCHAR(512) DEFAULT NULL,
                parse_error_msg VARCHAR(1024) DEFAULT NULL,
                knowledge_scope_code VARCHAR(128) DEFAULT NULL,
                knowledge_scope_name VARCHAR(256) DEFAULT NULL,
                business_category VARCHAR(256) DEFAULT NULL,
                document_tags   VARCHAR(1024) DEFAULT NULL,
                current_strategy_plan_id BIGINT DEFAULT NULL,
                latest_parse_task_id BIGINT DEFAULT NULL,
                structure_node_count INT DEFAULT NULL,
                latest_index_task_id BIGINT DEFAULT NULL,
                create_time     DATETIME      DEFAULT NULL,
                update_time     DATETIME      DEFAULT NULL,
                is_deleted      TINYINT       DEFAULT 0
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
            """);
    }

    /** 创建 document_task 表 */
    public static void createDocumentTaskTable(JdbcTemplate jdbcTemplate) {
        jdbcTemplate.execute("""
            CREATE TABLE reuben_agent_document_task (
                id              BIGINT        NOT NULL PRIMARY KEY,
                document_id     BIGINT        NOT NULL,
                strategy_plan_id BIGINT       DEFAULT NULL,
                task_type       TINYINT       DEFAULT NULL,
                task_status     TINYINT       DEFAULT 1,
                current_stage   TINYINT       DEFAULT NULL,
                trigger_source  TINYINT       DEFAULT 1,
                strategy_snapshot VARCHAR(256) DEFAULT NULL,
                retry_count     INT           DEFAULT 0,
                start_time      DATETIME      DEFAULT NULL,
                finish_time     DATETIME      DEFAULT NULL,
                cost_millis     BIGINT        DEFAULT NULL,
                error_code      VARCHAR(64)   DEFAULT NULL,
                error_msg       VARCHAR(1024) DEFAULT NULL,
                ext_json        TEXT          DEFAULT NULL,
                create_time     DATETIME      DEFAULT NULL,
                update_time     DATETIME      DEFAULT NULL,
                is_deleted      TINYINT       DEFAULT 0
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
            """);
    }

    /** 创建 document_task_log 表 */
    public static void createDocumentTaskLogTable(JdbcTemplate jdbcTemplate) {
        jdbcTemplate.execute("""
            CREATE TABLE reuben_agent_document_task_log (
                id              BIGINT        NOT NULL PRIMARY KEY,
                task_id         BIGINT        NOT NULL,
                document_id     BIGINT        NOT NULL,
                stage_type      TINYINT       DEFAULT NULL,
                event_type      TINYINT       DEFAULT NULL,
                log_level       TINYINT       DEFAULT 1,
                operator_type   TINYINT       DEFAULT 1,
                operator_id     BIGINT        DEFAULT NULL,
                content         VARCHAR(2048) DEFAULT NULL,
                detail_json     TEXT          DEFAULT NULL,
                create_time     DATETIME      DEFAULT NULL,
                update_time     DATETIME      DEFAULT NULL,
                is_deleted      TINYINT       DEFAULT 0
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
            """);
    }

    /** 创建 document_structure_node 表 */
    public static void createDocumentStructureNodeTable(JdbcTemplate jdbcTemplate) {
        jdbcTemplate.execute("""
            CREATE TABLE reuben_agent_document_structure_node (
                id                  BIGINT        NOT NULL PRIMARY KEY,
                document_id         BIGINT        NOT NULL,
                parse_task_id       BIGINT        NOT NULL,
                node_no             INT           NOT NULL,
                node_type           TINYINT       DEFAULT NULL,
                parent_node_id      BIGINT        DEFAULT NULL,
                prev_sibling_node_id BIGINT       DEFAULT NULL,
                next_sibling_node_id BIGINT       DEFAULT NULL,
                depth               INT           DEFAULT 0,
                node_code           VARCHAR(128)  DEFAULT NULL,
                title               VARCHAR(1024) DEFAULT NULL,
                anchor_text         VARCHAR(512)  DEFAULT NULL,
                canonical_path      VARCHAR(1024) DEFAULT NULL,
                section_path        VARCHAR(1024) DEFAULT NULL,
                content_text        MEDIUMTEXT    DEFAULT NULL,
                item_index          INT           DEFAULT NULL,
                create_time         DATETIME      DEFAULT NULL,
                update_time         DATETIME      DEFAULT NULL,
                is_deleted          TINYINT       DEFAULT 0
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
            """);
    }

    /** 一步创建全部表 */
    public static void createAllTables(JdbcTemplate jdbcTemplate) {
        createDocumentTable(jdbcTemplate);
        createDocumentTaskTable(jdbcTemplate);
        createDocumentTaskLogTable(jdbcTemplate);
        createDocumentStructureNodeTable(jdbcTemplate);
    }
}