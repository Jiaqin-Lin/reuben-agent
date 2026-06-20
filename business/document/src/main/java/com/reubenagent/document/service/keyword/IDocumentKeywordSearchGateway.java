package com.reubenagent.document.service.keyword;

import com.reubenagent.document.dto.DocumentRetrieveRequest;
import com.reubenagent.document.entity.DocumentChunk;

import java.util.List;

/**
 * 文档关键字搜索网关 —— 全文检索的抽象适配层。
 *
 * <p>默认使用 {@link NoOpKeywordSearchGateway}（空实现），
 * 当 Elasticsearch 可用时自动切换到 {@code EsDocumentKeywordSearchGateway}。
 * 调用方无需做 null check——始终注入本接口即可。</p>
 *
 * @author reuben
 * @since 2026-06-21
 */
public interface IDocumentKeywordSearchGateway {

    /**
     * 将文档切块索引到全文检索引擎。
     *
     * @param chunks 待索引的切块列表
     */
    void indexChunks(List<DocumentChunk> chunks);

    /**
     * 全文检索。
     *
     * @param request 检索请求
     * @return Spring AI Document 列表
     */
    List<org.springframework.ai.document.Document> search(DocumentRetrieveRequest request);

    /**
     * 删除指定文档的全部索引数据。
     *
     * @param documentId 文档 ID
     */
    void deleteByDocumentId(Long documentId);
}
