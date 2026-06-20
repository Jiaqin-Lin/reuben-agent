package com.reubenagent.document.service.keyword;

import com.reubenagent.document.dto.DocumentRetrieveRequest;
import com.reubenagent.document.entity.DocumentChunk;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;

/**
 * 关键字搜索网关的空实现 —— 当 Elasticsearch 未部署时的默认行为。
 *
 * <p>所有方法均为空操作 + debug 日志，保证调用方无需判空。
 * 当 {@code ElasticsearchClient} bean 存在时，
 * {@code EsDocumentKeywordSearchGateway} 通过 {@code @Primary} 自动替换本实现。</p>
 *
 * @author reuben
 * @since 2026-06-21
 */
@Slf4j
@Primary
@Component
public class NoOpKeywordSearchGateway implements IDocumentKeywordSearchGateway {

    @Override
    public void indexChunks(List<DocumentChunk> chunks) {
        log.debug("Keyword search disabled, skipping index of {} chunks", chunks != null ? chunks.size() : 0);
    }

    @Override
    public List<org.springframework.ai.document.Document> search(DocumentRetrieveRequest request) {
        log.debug("Keyword search disabled, returning empty results for query: {}",
                request != null ? request.getQuery() : null);
        return Collections.emptyList();
    }

    @Override
    public void deleteByDocumentId(Long documentId) {
        log.debug("Keyword search disabled, skipping delete for documentId={}", documentId);
    }
}
