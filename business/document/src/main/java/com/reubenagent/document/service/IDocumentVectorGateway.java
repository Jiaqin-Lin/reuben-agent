package com.reubenagent.document.service;

import com.reubenagent.document.entity.DocumentChunk;
import com.reubenagent.document.model.DocumentVectorizationResult;

import java.util.List;

/**
 * 文档向量网关 —— 向量数据库的抽象适配层。
 *
 * <p>负责将文档切块通过 EmbeddingModel 转为向量并写入向量存储（如 PGVector）。
 * 支撑后续的语义相似度检索。</p>
 *
 * @author reuben
 * @since 2026-06-20
 */
public interface IDocumentVectorGateway {

    /**
     * 对文档切块列表进行分批向量化并写入向量存储。
     *
     * <p>不修改入参 chunk 的 vectorStatus——由调用方根据返回的
     * {@link DocumentVectorizationResult} 自行更新 MySQL。</p>
     *
     * @param chunks 待向量化的文档切块列表
     * @return 向量化结果，包含成功/失败的 chunk ID
     */
    DocumentVectorizationResult vectorize(List<DocumentChunk> chunks);

    /**
     * 删除指定文档在向量存储中的全部向量数据。
     *
     * @param documentId 文档 ID
     */
    void deleteByDocumentId(Long documentId);
}
