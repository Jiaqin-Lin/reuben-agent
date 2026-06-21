package com.reubenagent.rag.service;

import com.reubenagent.rag.config.RagProperties;
import com.reubenagent.rag.model.RetrievalResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 向量检索通道 —— 将查询文本向量化后，通过余弦距离在 PGVector 中检索 TopK 相似 chunk。
 *
 * <p>防御性设计：EmbeddingModel 不可用或 SQL 异常时返回空列表，不抛异常，
 * 允许上层编排降级到单通道（关键词）继续服务。</p>
 *
 * @author reuben
 * @since 2026-06-21
 */
@Slf4j
@Component
public class VectorRetrievalChannel {

    private final JdbcTemplate pgVectorJdbcTemplate;

    private final ObjectProvider<EmbeddingModel> embeddingModelProvider;

    private final RagProperties ragProperties;

    public VectorRetrievalChannel(
            @Qualifier("ragPgVectorJdbcTemplate") JdbcTemplate pgVectorJdbcTemplate,
            ObjectProvider<EmbeddingModel> embeddingModelProvider,
            RagProperties ragProperties) {
        this.pgVectorJdbcTemplate = pgVectorJdbcTemplate;
        this.embeddingModelProvider = embeddingModelProvider;
        this.ragProperties = ragProperties;
    }

    // 阶段 1：检索入口
    public List<RetrievalResult> retrieve(String query, int topK, Map<String, String> filters) {
        // query 为空 → 空列表
        if (query == null || query.isBlank()) {
            log.warn("向量检索 query 为空，返回空列表");
            return List.of();
        }

        // 获取 EmbeddingModel
        EmbeddingModel embeddingModel = embeddingModelProvider.getIfAvailable();
        if (embeddingModel == null) {
            log.warn("EmbeddingModel 不可用，向量通道降级返回空列表");
            return List.of();
        }

        try {
            // 阶段 2：向量化查询文本
            float[] queryEmbedding = embeddingModel.embed(query);
            if (queryEmbedding == null || queryEmbedding.length == 0) {
                log.warn("EmbeddingModel 返回空向量，query={}", query);
                return List.of();
            }

            // 阶段 3：构建 SQL + 执行余弦相似度检索
            String tableName = ragProperties.getPgvector().getTableName();
            String sql = buildQuerySql(tableName, filters);
            String vectorLiteral = toPgVectorLiteral(queryEmbedding);

            Object[] params = buildQueryParams(vectorLiteral, topK, filters);
            int[] argTypes = buildQueryArgTypes(params.length, filters);

            List<RetrievalResult> results = pgVectorJdbcTemplate.query(
                    sql, params, argTypes, this::mapRow);

            log.debug("向量检索完成: query={}, topK={}, hitCount={}", query, topK, results.size());
            return results;

        } catch (Exception e) {
            log.error("向量检索异常，降级返回空列表: query={}", query, e);
            return List.of();
        }
    }

    // 阶段 3a：构建参数化 SQL
    private String buildQuerySql(String tableName, Map<String, String> filters) {
        StringBuilder sql = new StringBuilder();
        sql.append("SELECT e.id, e.chunk_id, e.chunk_text, e.section_path, ")
                .append("e.document_id, e.parent_block_id, ")
                .append("1 - (e.embedding <=> CAST(? AS vector)) AS similarity ")
                .append("FROM ").append(tableName).append(" e ")
                .append("WHERE e.chunk_text IS NOT NULL AND e.chunk_text != '' ");

        if (filters != null && !filters.isEmpty()) {
            for (String key : filters.keySet()) {
                sql.append("AND e.metadata_json->>'").append(key).append("' = ? ");
            }
        }

        sql.append("ORDER BY e.embedding <=> CAST(? AS vector) ")
                .append("LIMIT ?");
        return sql.toString();
    }

    // 阶段 3b：构建查询参数数组
    private Object[] buildQueryParams(String vectorLiteral, int topK, Map<String, String> filters) {
        int filterCount = (filters != null) ? filters.size() : 0;
        Object[] params = new Object[1 + filterCount + 1 + 1]; // vector + filters + order vector + limit
        params[0] = vectorLiteral;
        int idx = 1;
        if (filters != null) {
            for (String value : filters.values()) {
                params[idx++] = value;
            }
        }
        params[idx++] = vectorLiteral; // ORDER BY 的向量
        params[idx] = topK;
        return params;
    }

    // 阶段 3c：构建参数类型数组
    private int[] buildQueryArgTypes(int paramCount, Map<String, String> filters) {
        int filterCount = (filters != null) ? filters.size() : 0;
        int[] argTypes = new int[paramCount];
        // 所有参数都是 VARCHAR 类型（PGVector 接受字符串格式的向量）
        for (int i = 0; i < paramCount; i++) {
            argTypes[i] = java.sql.Types.VARCHAR;
        }
        return argTypes;
    }

    // 阶段 4：ResultSet → RetrievalResult 映射
    private RetrievalResult mapRow(ResultSet rs, int rowNum) throws SQLException {
        return RetrievalResult.builder()
                .chunkId(getLong(rs, "chunk_id"))
                .chunkText(rs.getString("chunk_text"))
                .score(rs.getDouble("similarity"))
                .sectionPath(rs.getString("section_path"))
                .documentId(getLong(rs, "document_id"))
                .parentBlockId(getLong(rs, "parent_block_id"))
                .source("vector")
                .build();
    }

    // 阶段 helper：float[] → pgvector 字符串字面量
    private String toPgVectorLiteral(float[] embedding) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < embedding.length; i++) {
            if (i > 0) {
                sb.append(",");
            }
            sb.append(embedding[i]);
        }
        sb.append("]");
        return sb.toString();
    }

    private Long getLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }
}
