package com.reubenagent.document.service.impl;

import com.alibaba.fastjson.JSON;
import com.reubenagent.common.enums.DocumentManageCode;
import com.reubenagent.common.exception.DocumentException;
import com.reubenagent.document.config.DocumentProperties;
import com.reubenagent.document.entity.DocumentChunk;
import com.reubenagent.document.model.DocumentVectorizationResult;
import com.reubenagent.document.service.IDocumentVectorGateway;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * PGVector 向量网关实现 —— 负责文档切块的向量化（Embedding）与 pgvector 存储。
 *
 * <p>核心流程：</p>
 * <ol>
 *   <li>过滤空文本 chunk</li>
 *   <li>按 {@code DocumentProperties.Pgvector.batchSize} 分批</li>
 *   <li>每批调用 {@link EmbeddingModel#embed} 生成向量</li>
 *   <li>通过 JDBC batchUpdate 执行 UPSERT（ON CONFLICT DO UPDATE）</li>
 *   <li>返回 {@link DocumentVectorizationResult}（不修改入参 chunk）</li>
 * </ol>
 *
 * @author reuben
 * @since 2026-06-20
 */
@Slf4j
@Service
@AllArgsConstructor
public class DefaultDocumentVectorGateway implements IDocumentVectorGateway {

    private static final String UPSERT_SQL_TEMPLATE = """
        INSERT INTO %s
        (id, document_id, task_id, chunk_id, chunk_no, parent_block_id,
         section_path, chunk_text, char_count, embedding, embedding_model,
         metadata_json, create_time, edit_time)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS vector), ?, CAST(? AS jsonb), NOW(), NOW())
        ON CONFLICT (id) DO UPDATE SET
            document_id = EXCLUDED.document_id,
            task_id = EXCLUDED.task_id,
            chunk_id = EXCLUDED.chunk_id,
            chunk_no = EXCLUDED.chunk_no,
            parent_block_id = EXCLUDED.parent_block_id,
            section_path = EXCLUDED.section_path,
            chunk_text = EXCLUDED.chunk_text,
            char_count = EXCLUDED.char_count,
            embedding = EXCLUDED.embedding,
            embedding_model = EXCLUDED.embedding_model,
            metadata_json = EXCLUDED.metadata_json,
            edit_time = NOW()
        """;

    private static final String DELETE_BY_DOCUMENT_SQL = "DELETE FROM %s WHERE document_id = ?";

    @Qualifier("documentPgVectorJdbcTemplate")
    private final JdbcTemplate pgVectorJdbcTemplate;

    private final ObjectProvider<EmbeddingModel> embeddingModelProvider;

    private final DocumentProperties documentProperties;

    @Override
    public DocumentVectorizationResult vectorize(List<DocumentChunk> chunks) {
        List<Long> successChunkIds = new ArrayList<>();
        List<Long> failedChunkIds = new ArrayList<>();

        if (chunks == null || chunks.isEmpty()) {
            return DocumentVectorizationResult.builder()
                    .totalCount(0)
                    .successCount(0)
                    .failedCount(0)
                    .successChunkIds(successChunkIds)
                    .failedChunkIds(failedChunkIds)
                    .build();
        }

        EmbeddingModel embeddingModel = requireEmbeddingModel();
        DocumentProperties.Pgvector pgConfig = documentProperties.getPgvector();
        String tableName = pgConfig.getTableName();
        int batchSize = pgConfig.getBatchSize();

        // 过滤有效 chunk
        List<DocumentChunk> validChunks = chunks.stream()
                .filter(chunk -> chunk != null && chunk.getChunkText() != null && !chunk.getChunkText().isBlank())
                .toList();
        if (validChunks.isEmpty()) {
            log.warn("所有 chunk 文本均为空，跳过向量化");
            return DocumentVectorizationResult.builder()
                    .totalCount(chunks.size())
                    .successCount(0)
                    .failedCount(chunks.size())
                    .successChunkIds(successChunkIds)
                    .failedChunkIds(chunks.stream().map(DocumentChunk::getId).toList())
                    .build();
        }

        String upsertSql = UPSERT_SQL_TEMPLATE.formatted(tableName);
        String embeddingModelName = pgConfig.getEmbeddingModel();
        int totalBatchCount = (validChunks.size() + batchSize - 1) / batchSize;

        log.info("开始文档向量化，chunkCount={}, batchSize={}, batchCount={}, embeddingModel={}",
                validChunks.size(), batchSize, totalBatchCount, embeddingModelName);

        for (int start = 0; start < validChunks.size(); start += batchSize) {
            int end = Math.min(start + batchSize, validChunks.size());
            List<DocumentChunk> batch = validChunks.subList(start, end);
            int batchIndex = (start / batchSize) + 1;

            log.info("处理 embedding 批次 {}/{}, chunkRange=[{}, {}], batchSize={}",
                    batchIndex, totalBatchCount, start + 1, end, batch.size());

            try {
                List<float[]> embeddings = embeddingModel.embed(batch.stream()
                        .map(DocumentChunk::getChunkText)
                        .toList());
                if (embeddings.size() != batch.size()) {
                    throw new DocumentException(DocumentManageCode.VECTORIZE_FAILED,
                            "EmbeddingModel 返回向量数(" + embeddings.size()
                                    + ")与请求数(" + batch.size() + ")不一致");
                }
                batchUpsert(upsertSql, batch, embeddings, embeddingModelName);
                batch.forEach(chunk -> successChunkIds.add(chunk.getId()));
                log.info("embedding 批次完成 {}/{}, batchSize={}", batchIndex, totalBatchCount, batch.size());
            } catch (Exception e) {
                log.error("embedding 批次失败 {}/{}, batchSize={}", batchIndex, totalBatchCount, batch.size(), e);
                batch.forEach(chunk -> failedChunkIds.add(chunk.getId()));
            }
        }

        // 空文本的 chunk 也算失败
        for (DocumentChunk chunk : chunks) {
            if (chunk != null && (chunk.getChunkText() == null || chunk.getChunkText().isBlank())) {
                failedChunkIds.add(chunk.getId());
            }
        }

        DocumentVectorizationResult result = DocumentVectorizationResult.builder()
                .totalCount(chunks.size())
                .successCount(successChunkIds.size())
                .failedCount(failedChunkIds.size())
                .successChunkIds(successChunkIds)
                .failedChunkIds(failedChunkIds)
                .build();

        log.info("文档向量化完成，totalCount={}, successCount={}, failedCount={}",
                result.getTotalCount(), result.getSuccessCount(), result.getFailedCount());
        return result;
    }

    @Override
    public void deleteByDocumentId(Long documentId) {
        if (documentId == null) {
            return;
        }
        String tableName = documentProperties.getPgvector().getTableName();
        String deleteSql = DELETE_BY_DOCUMENT_SQL.formatted(tableName);
        try {
            int deleted = pgVectorJdbcTemplate.update(deleteSql, documentId);
            log.info("已删除文档向量数据，documentId={}, deletedRows={}", documentId, deleted);
        } catch (Exception e) {
            throw new DocumentException(DocumentManageCode.VECTORIZE_FAILED,
                    "删除 PGVector 数据失败，documentId=" + documentId, e);
        }
    }

    // ============ 私有方法 ============

    private void batchUpsert(String upsertSql,
                             List<DocumentChunk> batch,
                             List<float[]> embeddings,
                             String embeddingModelName) {
        pgVectorJdbcTemplate.batchUpdate(upsertSql, new BatchPreparedStatementSetter() {
            @Override
            public void setValues(PreparedStatement ps, int index) throws SQLException {
                DocumentChunk chunk = batch.get(index);
                float[] embedding = embeddings.get(index);
                String metadataJson = buildMetadataJson(chunk, embeddingModelName);

                ps.setLong(1, chunk.getId());
                ps.setLong(2, defaultLong(chunk.getDocumentId()));
                ps.setObject(3, chunk.getTaskId(), Types.BIGINT);
                ps.setLong(4, chunk.getId());
                ps.setInt(5, defaultInt(chunk.getChunkNo()));
                ps.setObject(6, chunk.getParentBlockId(), Types.BIGINT);
                ps.setString(7, chunk.getSectionPath());
                ps.setString(8, chunk.getChunkText());
                ps.setInt(9, defaultInt(chunk.getCharCount()));
                ps.setString(10, toVectorLiteral(embedding));
                ps.setString(11, embeddingModelName);
                ps.setString(12, metadataJson);
            }

            @Override
            public int getBatchSize() {
                return batch.size();
            }
        });
    }

    private String buildMetadataJson(DocumentChunk chunk, String embeddingModelName) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("documentId", chunk.getDocumentId());
        metadata.put("taskId", chunk.getTaskId());
        metadata.put("planId", chunk.getPlanId());
        metadata.put("chunkNo", chunk.getChunkNo());
        metadata.put("parentBlockId", chunk.getParentBlockId());
        metadata.put("sourceType", chunk.getSourceType());
        metadata.put("sectionPath", chunk.getSectionPath());
        metadata.put("structureNodeId", chunk.getStructureNodeId());
        metadata.put("structureNodeType", chunk.getStructureNodeType());
        metadata.put("canonicalPath", chunk.getCanonicalPath());
        metadata.put("itemIndex", chunk.getItemIndex());
        metadata.put("charCount", chunk.getCharCount());
        metadata.put("tokenCount", chunk.getTokenCount());
        metadata.put("embeddingModel", embeddingModelName);
        return JSON.toJSONString(metadata);
    }

    private String toVectorLiteral(float[] embedding) {
        if (embedding == null || embedding.length == 0) {
            throw new DocumentException(DocumentManageCode.VECTORIZE_FAILED,
                    "EmbeddingModel 返回了空向量");
        }
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

    private EmbeddingModel requireEmbeddingModel() {
        EmbeddingModel model = embeddingModelProvider.getIfAvailable();
        if (model == null) {
            throw new DocumentException(DocumentManageCode.VECTORIZE_FAILED,
                    "当前未找到可用的 EmbeddingModel，无法执行向量化");
        }
        return model;
    }

    private int defaultInt(Integer value) {
        return value != null ? value : 0;
    }

    private long defaultLong(Long value) {
        return value != null ? value : 0L;
    }
}
