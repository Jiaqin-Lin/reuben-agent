package com.reubenagent.chat;

import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 对话模块集成测试 DDL —— 手建 chat 表（与 {@code sql/reuben_agent_mysql.sql} 结构一致）。
 *
 * <p>对标 document 模块的 {@code DocumentTestSchema}。</p>
 */
public final class ChatTestSchema {

    private ChatTestSchema() {
    }

    /** 删除所有 chat 测试表。 */
    public static void dropTables(JdbcTemplate jdbcTemplate) {
        jdbcTemplate.execute("DROP TABLE IF EXISTS reuben_agent_chat_checkpoint");
        jdbcTemplate.execute("DROP TABLE IF EXISTS reuben_agent_chat_thread");
        jdbcTemplate.execute("DROP TABLE IF EXISTS reuben_agent_chat_retrieval_result");
        jdbcTemplate.execute("DROP TABLE IF EXISTS reuben_agent_chat_channel_execution");
        jdbcTemplate.execute("DROP TABLE IF EXISTS reuben_agent_chat_trace_stage");
        jdbcTemplate.execute("DROP TABLE IF EXISTS reuben_agent_chat_stage_benchmark");
        jdbcTemplate.execute("DROP TABLE IF EXISTS reuben_agent_chat_memory_summary");
        jdbcTemplate.execute("DROP TABLE IF EXISTS reuben_agent_chat_turn");
        jdbcTemplate.execute("DROP TABLE IF EXISTS reuben_agent_chat_conversation");
    }

    /** 创建会话表。 */
    public static void createConversationTable(JdbcTemplate jdbcTemplate) {
        jdbcTemplate.execute("""
            CREATE TABLE reuben_agent_chat_conversation (
                id BIGINT NOT NULL,
                conversation_id VARCHAR(64) NOT NULL,
                session_status TINYINT DEFAULT 1,
                chat_mode TINYINT DEFAULT NULL,
                title VARCHAR(256) DEFAULT NULL,
                selected_document_id BIGINT DEFAULT NULL,
                selected_document_name VARCHAR(512) DEFAULT NULL,
                create_time DATETIME DEFAULT NULL,
                update_time DATETIME DEFAULT NULL,
                is_deleted TINYINT DEFAULT 0,
                PRIMARY KEY (id),
                UNIQUE KEY uk_conversation_id (conversation_id)
            )
            """);
    }

    /** 创建轮次表。 */
    public static void createTurnTable(JdbcTemplate jdbcTemplate) {
        jdbcTemplate.execute("""
            CREATE TABLE reuben_agent_chat_turn (
                id BIGINT NOT NULL,
                conversation_id VARCHAR(64) NOT NULL,
                user_prompt TEXT DEFAULT NULL,
                reply_content MEDIUMTEXT DEFAULT NULL,
                reasoning_note_list TEXT DEFAULT NULL,
                source_snapshot_list TEXT DEFAULT NULL,
                followup_suggestion_list TEXT DEFAULT NULL,
                tool_trace_list TEXT DEFAULT NULL,
                debug_trace_json MEDIUMTEXT DEFAULT NULL,
                turn_status TINYINT DEFAULT 1,
                execution_mode TINYINT DEFAULT NULL,
                finish_note VARCHAR(512) DEFAULT NULL,
                first_token_latency_ms BIGINT DEFAULT NULL,
                total_latency_ms BIGINT DEFAULT NULL,
                create_time DATETIME DEFAULT NULL,
                update_time DATETIME DEFAULT NULL,
                is_deleted TINYINT DEFAULT 0,
                PRIMARY KEY (id),
                KEY idx_conversation_turn (conversation_id, id)
            )
            """);
    }

    /** 创建记忆摘要表。 */
    public static void createMemorySummaryTable(JdbcTemplate jdbcTemplate) {
        jdbcTemplate.execute("""
            CREATE TABLE reuben_agent_chat_memory_summary (
                id BIGINT NOT NULL,
                conversation_id VARCHAR(64) NOT NULL,
                covered_turn_id BIGINT DEFAULT NULL,
                covered_turn_count INT DEFAULT 0,
                compression_count INT DEFAULT 0,
                summary_version INT DEFAULT 1,
                summary_text TEXT DEFAULT NULL,
                summary_json MEDIUMTEXT DEFAULT NULL,
                last_source_edit_time DATETIME DEFAULT NULL,
                create_time DATETIME DEFAULT NULL,
                update_time DATETIME DEFAULT NULL,
                is_deleted TINYINT DEFAULT 0,
                PRIMARY KEY (id),
                UNIQUE KEY uk_conversation_id (conversation_id)
            )
            """);
    }

    /** 创建 checkpoint 表。 */
    public static void createCheckpointTable(JdbcTemplate jdbcTemplate) {
        jdbcTemplate.execute("""
            CREATE TABLE reuben_agent_chat_checkpoint (
                id BIGINT NOT NULL,
                thread_id VARCHAR(64) NOT NULL,
                checkpoint_id VARCHAR(128) NOT NULL,
                parent_checkpoint_id VARCHAR(128) DEFAULT NULL,
                messages_json MEDIUMTEXT DEFAULT NULL,
                state_json MEDIUMTEXT DEFAULT NULL,
                create_time DATETIME DEFAULT NULL,
                update_time DATETIME DEFAULT NULL,
                is_deleted TINYINT DEFAULT 0,
                PRIMARY KEY (id),
                KEY idx_thread_checkpoint (thread_id, checkpoint_id)
            )
            """);
    }

    /** 创建 thread 表。 */
    public static void createThreadTable(JdbcTemplate jdbcTemplate) {
        jdbcTemplate.execute("""
            CREATE TABLE reuben_agent_chat_thread (
                id BIGINT NOT NULL,
                thread_id VARCHAR(64) NOT NULL,
                thread_name VARCHAR(256) DEFAULT NULL,
                latest_checkpoint_id VARCHAR(128) DEFAULT NULL,
                create_time DATETIME DEFAULT NULL,
                update_time DATETIME DEFAULT NULL,
                is_deleted TINYINT DEFAULT 0,
                PRIMARY KEY (id),
                UNIQUE KEY uk_thread_id (thread_id)
            )
            """);
    }

    /** 创建全部 chat 表（按依赖顺序）。 */
    public static void createAllTables(JdbcTemplate jdbcTemplate) {
        createConversationTable(jdbcTemplate);
        createTurnTable(jdbcTemplate);
        createMemorySummaryTable(jdbcTemplate);
        createThreadTable(jdbcTemplate);
        createCheckpointTable(jdbcTemplate);
    }
}
