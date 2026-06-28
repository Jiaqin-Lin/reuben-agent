package com.reubenagent.document.service.impl;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.BulkRequest;
import co.elastic.clients.elasticsearch.core.BulkResponse;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import com.reubenagent.document.config.DocumentProperties;
import com.reubenagent.document.entity.DocumentStructureNode;
import com.reubenagent.document.enums.DocumentStructureNodeTypeEnum;
import com.reubenagent.document.model.es.DocumentNavigationIndexRecord;
import com.reubenagent.document.model.es.NavigationSectionHit;
import com.reubenagent.document.service.DocumentNavigationIndexService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * ES 导航章节索引服务 —— 管理 {@code reuben_agent_document_navigation} 索引。
 *
 * <p>只索引 SECTION 类型节点（ROOT/CHAPTER）；STEP/LIST_ITEM 不入导航索引。
 * 四维搜索：filter(documentId + nodeType=SECTION) + should(matchPhrase + multiMatch)。</p>
 *
 * @author reuben
 * @since 2026-06-28
 */
@Slf4j
@Service
@ConditionalOnProperty(prefix = "reuben.document.elasticsearch", name = "enabled", havingValue = "true", matchIfMissing = true)
public class EsDocumentNavigationIndexService implements DocumentNavigationIndexService {

    private static final int SEARCH_CAP = 20;

    private final ObjectProvider<ElasticsearchClient> esClientProvider;
    private final String indexName;

    public EsDocumentNavigationIndexService(ObjectProvider<ElasticsearchClient> esClientProvider,
                                            DocumentProperties properties) {
        this.esClientProvider = esClientProvider;
        this.indexName = properties.getElasticsearch().getNavigationIndexName();
    }

    @Override
    public void reindexDocumentNodes(Long documentId, Long parseTaskId, List<DocumentStructureNode> nodes) {
        if (documentId == null) {
            return;
        }
        ElasticsearchClient esClient = esClientProvider.getIfAvailable();
        if (esClient == null) {
            log.warn("ElasticsearchClient 不可用，跳过导航索引重建: documentId={}", documentId);
            return;
        }

        // 先删旧索引
        deleteByDocumentId(documentId);
        if (nodes == null || nodes.isEmpty()) {
            log.info("无结构节点，导航索引已清空: documentId={}", documentId);
            return;
        }

        // 只索引 SECTION 类型节点
        List<DocumentNavigationIndexRecord> records = new ArrayList<>();
        for (DocumentStructureNode node : nodes) {
            if (node == null || !isSection(node)) {
                continue;
            }
            records.add(DocumentNavigationIndexRecord.builder()
                    .nodeId(node.getId())
                    .documentId(documentId)
                    .parseTaskId(node.getParseTaskId() != null ? node.getParseTaskId() : parseTaskId)
                    .nodeType(nodeTypeLabel(node.getNodeType()))
                    .nodeCode(node.getNodeCode())
                    .nodeNo(node.getNodeNo())
                    .depth(node.getDepth())
                    .parentNodeId(node.getParentNodeId())
                    .title(node.getTitle())
                    .anchorText(node.getAnchorText())
                    .sectionPath(node.getSectionPath())
                    .canonicalPath(node.getCanonicalPath())
                    .contentText(node.getContentText())
                    .itemIndex(node.getItemIndex())
                    .build());
        }
        bulkIndex(esClient, records);
        log.info("导航索引重建完成: documentId={}, sections={}", documentId, records.size());
    }

    @Override
    public void deleteByDocumentId(Long documentId) {
        if (documentId == null) {
            return;
        }
        ElasticsearchClient esClient = esClientProvider.getIfAvailable();
        if (esClient == null) {
            return;
        }
        try {
            esClient.deleteByQuery(d -> d
                    .index(indexName)
                    .refresh(true)
                    .query(q -> q.term(t -> t.field("documentId").value(documentId))));
            log.info("导航索引已删除文档记录: documentId={}", documentId);
        } catch (IOException e) {
            log.error("导航索引删除失败: documentId={}", documentId, e);
        }
    }

    @Override
    public List<NavigationSectionHit> searchSections(Long documentId, String topic, String facet,
                                                     String informationNeed, String question, int size) {
        ElasticsearchClient esClient = esClientProvider.getIfAvailable();
        if (esClient == null || documentId == null) {
            return Collections.emptyList();
        }

        List<String> queryTexts = collectQueryTexts(topic, facet, informationNeed, question);
        if (queryTexts.isEmpty()) {
            return Collections.emptyList();
        }

        try {
            SearchResponse<Map> response = esClient.search(s -> {
                s.index(indexName)
                 .query(q -> q.bool(b -> {
                     b.filter(f -> f.term(t -> t.field("documentId").value(documentId)));
                     b.filter(f -> f.term(t -> t.field("nodeType").value("SECTION")));
                     for (String text : queryTexts) {
                         b.should(sh -> sh.matchPhrase(mp -> mp.field("title").boost(20.0f).query(text)));
                         b.should(sh -> sh.matchPhrase(mp -> mp.field("sectionPath").boost(15.0f).query(text)));
                         b.should(sh -> sh.multiMatch(mm -> mm
                                 .fields("title^10", "sectionPath^8", "anchorText^5", "contentText")
                                 .query(text)));
                     }
                     b.minimumShouldMatch("1");
                     return b;
                 }))
                 .size(Math.min(Math.max(size, 1), SEARCH_CAP));
                return s;
            }, Map.class);

            List<NavigationSectionHit> hits = new ArrayList<>();
            for (Hit<Map> hit : response.hits().hits()) {
                Map<String, Object> src = hit.source();
                if (src == null) {
                    continue;
                }
                hits.add(NavigationSectionHit.builder()
                        .nodeId(asLong(src.get("nodeId")))
                        .nodeCode(asString(src.get("nodeCode")))
                        .title(asString(src.get("title")))
                        .sectionPath(asString(src.get("sectionPath")))
                        .canonicalPath(asString(src.get("canonicalPath")))
                        .score(hit.score() != null ? hit.score() : 0.0)
                        .build());
            }
            log.debug("导航章节搜索完成: documentId={} hits={}", documentId, hits.size());
            return hits;
        } catch (IOException e) {
            log.error("导航章节搜索异常: documentId={}", documentId, e);
            return Collections.emptyList();
        }
    }

    // ==================== 内部工具 ====================

    private void bulkIndex(ElasticsearchClient esClient, List<DocumentNavigationIndexRecord> records) {
        if (records.isEmpty()) {
            return;
        }
        BulkRequest.Builder bulkBuilder = new BulkRequest.Builder();
        for (DocumentNavigationIndexRecord r : records) {
            String id = r.getNodeId() == null ? null : String.valueOf(r.getNodeId());
            bulkBuilder.operations(op -> op
                    .index(idx -> {
                        idx.index(indexName);
                        if (id != null) {
                            idx.id(id);
                        }
                        idx.document(toEsDoc(r));
                        return idx;
                    }));
        }
        try {
            BulkResponse response = esClient.bulk(bulkBuilder.build());
            long failedCount = response.items().stream()
                    .filter(item -> item.error() != null)
                    .count();
            if (failedCount > 0) {
                log.warn("导航 ES bulk 索引部分失败: total={} failed={}", response.items().size(), failedCount);
            }
        } catch (Exception e) {
            log.error("导航 ES bulk 索引异常: count={}", records.size(), e);
        }
    }

    private Map<String, Object> toEsDoc(DocumentNavigationIndexRecord r) {
        Map<String, Object> doc = new LinkedHashMap<>();
        if (r.getNodeId() != null) doc.put("nodeId", r.getNodeId());
        if (r.getDocumentId() != null) doc.put("documentId", r.getDocumentId());
        if (r.getParseTaskId() != null) doc.put("parseTaskId", r.getParseTaskId());
        if (r.getParentNodeId() != null) doc.put("parentNodeId", r.getParentNodeId());
        if (r.getNodeNo() != null) doc.put("nodeNo", r.getNodeNo());
        if (r.getDepth() != null) doc.put("depth", r.getDepth());
        if (r.getItemIndex() != null) doc.put("itemIndex", r.getItemIndex());
        if (r.getNodeType() != null) doc.put("nodeType", r.getNodeType());
        if (r.getNodeCode() != null) doc.put("nodeCode", r.getNodeCode());
        if (r.getCanonicalPath() != null) doc.put("canonicalPath", r.getCanonicalPath());
        if (r.getTitle() != null) doc.put("title", r.getTitle());
        if (r.getAnchorText() != null) doc.put("anchorText", r.getAnchorText());
        if (r.getSectionPath() != null) doc.put("sectionPath", r.getSectionPath());
        if (r.getContentText() != null) doc.put("contentText", r.getContentText());
        return doc;
    }

    private List<String> collectQueryTexts(String topic, String facet, String informationNeed, String question) {
        List<String> texts = new ArrayList<>();
        addIfNotBlank(texts, topic);
        addIfNotBlank(texts, facet);
        addIfNotBlank(texts, informationNeed);
        addIfNotBlank(texts, question);
        return texts;
    }

    private static void addIfNotBlank(List<String> list, String s) {
        if (s != null && !s.isBlank()) {
            list.add(s.trim());
        }
    }

    private boolean isSection(DocumentStructureNode node) {
        Integer t = node.getNodeType();
        return DocumentStructureNodeTypeEnum.ROOT.getCode().equals(t)
                || DocumentStructureNodeTypeEnum.CHAPTER.getCode().equals(t);
    }

    private String nodeTypeLabel(Integer nodeType) {
        if (nodeType == null) {
            return null;
        }
        if (DocumentStructureNodeTypeEnum.ROOT.getCode().equals(nodeType)) {
            return "SECTION";
        }
        if (DocumentStructureNodeTypeEnum.CHAPTER.getCode().equals(nodeType)) {
            return "SECTION";
        }
        if (DocumentStructureNodeTypeEnum.STEP.getCode().equals(nodeType)) {
            return "STEP";
        }
        if (DocumentStructureNodeTypeEnum.LIST_ITEM.getCode().equals(nodeType)) {
            return "LIST_ITEM";
        }
        return null;
    }

    private static String asString(Object v) {
        return v != null ? v.toString() : null;
    }

    private static Long asLong(Object v) {
        if (v == null) {
            return null;
        }
        if (v instanceof Number n) {
            return n.longValue();
        }
        try {
            return Long.parseLong(v.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
