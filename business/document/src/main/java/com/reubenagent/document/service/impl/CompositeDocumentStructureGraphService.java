package com.reubenagent.document.service.impl;

import com.reubenagent.common.exception.DocumentException;
import com.reubenagent.document.model.graph.GraphItem;
import com.reubenagent.document.model.graph.GraphSection;
import com.reubenagent.document.service.DocumentStructureGraphService;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.function.Function;

/**
 * 组合图服务 —— 优先 Neo4j，不可用或查询失败时回退 MySQL。
 *
 * <p>使用 {@link CompositeGraphAvailabilityCache} 缓存 per documentId 的图可用性，
 * 避免每次查询都做 Cypher 探活；Neo4j 查询抛 {@link DocumentException}(NEO4J_QUERY_FAILED) 时自动回退。</p>
 *
 * @author reuben
 * @since 2026-06-28
 */
@Slf4j
@Primary
@Service
@AllArgsConstructor
public class CompositeDocumentStructureGraphService implements DocumentStructureGraphService {

    private final MysqlDocumentStructureGraphService mysqlGraphService;
    private final ObjectProvider<Neo4jDocumentStructureGraphService> neo4jGraphServiceProvider;

    @Override
    public boolean isGraphAvailable(Long documentId) {
        return mysqlGraphService.isGraphAvailable(documentId);
    }

    /**
     * 优先走 Neo4j，失败/不可用回退 MySQL。
     *
     * @param documentId 文档 ID
     * @param neo4jFn    Neo4j 查询函数
     * @param mysqlFn    MySQL 回退函数
     */
    private <T> T route(Long documentId,
                        Function<Neo4jDocumentStructureGraphService, T> neo4jFn,
                        Function<MysqlDocumentStructureGraphService, T> mysqlFn) {
        Neo4jDocumentStructureGraphService neo4j = neo4jGraphServiceProvider.getIfAvailable();
        if (neo4j == null) {
            return mysqlFn.apply(mysqlGraphService);
        }
        Boolean cached = CompositeGraphAvailabilityCache.get(documentId);
        if (Boolean.FALSE.equals(cached)) {
            return mysqlFn.apply(mysqlGraphService);
        }
        try {
            if (cached == null) {
                boolean available = neo4j.isGraphAvailable(documentId);
                CompositeGraphAvailabilityCache.set(documentId, available);
                if (!available) {
                    return mysqlFn.apply(mysqlGraphService);
                }
            }
            return neo4jFn.apply(neo4j);
        } catch (DocumentException e) {
            log.warn("Neo4j 查询失败，回退 MySQL: documentId={} code={} error={}",
                    documentId, e.getDocumentCode(), e.getMessage());
            return mysqlFn.apply(mysqlGraphService);
        }
    }

    @Override
    public GraphSection findSectionById(Long documentId, Long sectionNodeId) {
        return route(documentId,
                n -> n.findSectionById(documentId, sectionNodeId),
                m -> m.findSectionById(documentId, sectionNodeId));
    }

    @Override
    public GraphSection findSectionByCode(Long documentId, String nodeCode) {
        return route(documentId,
                n -> n.findSectionByCode(documentId, nodeCode),
                m -> m.findSectionByCode(documentId, nodeCode));
    }

    @Override
    public GraphSection findSectionByTitle(Long documentId, String title) {
        return route(documentId,
                n -> n.findSectionByTitle(documentId, title),
                m -> m.findSectionByTitle(documentId, title));
    }

    @Override
    public GraphSection findSectionByCanonicalPath(Long documentId, String canonicalPath) {
        return route(documentId,
                n -> n.findSectionByCanonicalPath(documentId, canonicalPath),
                m -> m.findSectionByCanonicalPath(documentId, canonicalPath));
    }

    @Override
    public GraphSection findBestSection(Long documentId, String topic, String facet) {
        return route(documentId,
                n -> n.findBestSection(documentId, topic, facet),
                m -> m.findBestSection(documentId, topic, facet));
    }

    @Override
    public List<GraphSection> listSections(Long documentId) {
        return route(documentId,
                Neo4jDocumentStructureGraphService::listSections,
                MysqlDocumentStructureGraphService::listSections,
                documentId);
    }

    @Override
    public List<GraphSection> listChildren(Long documentId, Long sectionNodeId) {
        return route(documentId,
                n -> n.listChildren(documentId, sectionNodeId),
                m -> m.listChildren(documentId, sectionNodeId));
    }

    @Override
    public GraphSection parentSection(Long documentId, Long sectionNodeId) {
        return route(documentId,
                n -> n.parentSection(documentId, sectionNodeId),
                m -> m.parentSection(documentId, sectionNodeId));
    }

    @Override
    public GraphSection previousSibling(Long documentId, Long sectionNodeId) {
        return route(documentId,
                n -> n.previousSibling(documentId, sectionNodeId),
                m -> m.previousSibling(documentId, sectionNodeId));
    }

    @Override
    public GraphSection nextSibling(Long documentId, Long sectionNodeId) {
        return route(documentId,
                n -> n.nextSibling(documentId, sectionNodeId),
                m -> m.nextSibling(documentId, sectionNodeId));
    }

    @Override
    public GraphItem findItemByIndex(Long documentId, Long sectionNodeId, Integer itemIndex) {
        return route(documentId,
                n -> n.findItemByIndex(documentId, sectionNodeId, itemIndex),
                m -> m.findItemByIndex(documentId, sectionNodeId, itemIndex));
    }

    @Override
    public List<GraphItem> listItems(Long documentId, Long sectionNodeId) {
        return route(documentId,
                n -> n.listItems(documentId, sectionNodeId),
                m -> m.listItems(documentId, sectionNodeId));
    }

    @Override
    public List<GraphItem> searchItemsInSection(Long documentId, Long sectionNodeId, String keyword) {
        return route(documentId,
                n -> n.searchItemsInSection(documentId, sectionNodeId, keyword),
                m -> m.searchItemsInSection(documentId, sectionNodeId, keyword));
    }

    /** 三参列表路由辅助 */
    private <T> List<T> route(Long documentId,
                              java.util.function.BiFunction<Neo4jDocumentStructureGraphService, Long, List<T>> neo4jFn,
                              java.util.function.BiFunction<MysqlDocumentStructureGraphService, Long, List<T>> mysqlFn,
                              Long documentIdArg) {
        return route(documentId, n -> neo4jFn.apply(n, documentIdArg), m -> mysqlFn.apply(m, documentIdArg));
    }
}
