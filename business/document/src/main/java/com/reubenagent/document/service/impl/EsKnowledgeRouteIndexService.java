package com.reubenagent.document.service.impl;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.BulkRequest;
import co.elastic.clients.elasticsearch.core.BulkResponse;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.reubenagent.document.config.DocumentProperties;
import com.reubenagent.document.config.DocumentProperties.Elasticsearch;
import com.reubenagent.document.entity.Document;
import com.reubenagent.document.entity.DocumentProfile;
import com.reubenagent.document.entity.KnowledgeScopeNode;
import com.reubenagent.document.entity.KnowledgeTopicNode;
import com.reubenagent.document.mapper.IDocumentMapper;
import com.reubenagent.document.mapper.IDocumentProfileMapper;
import com.reubenagent.document.mapper.IKnowledgeScopeNodeMapper;
import com.reubenagent.document.mapper.IKnowledgeTopicNodeMapper;
import com.reubenagent.document.model.es.KnowledgeRouteIndexRecord;
import com.reubenagent.document.model.es.RouteLexicalHit;
import com.reubenagent.document.service.KnowledgeRouteIndexService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/**
 * ES 知识路由索引服务 —— 管理 {@code reuben_agent_knowledge_route} 索引。
 *
 * <p>使用定时刷新 + {@link ReentrantLock} 保证并发安全，避免
 * {@code AtomicLong}+{@code compareAndSet} 防抖方案在高并发下的竞态问题。</p>
 *
 * @author reuben
 * @since 2026-06-28
 */
@Slf4j
@Service
@ConditionalOnProperty(prefix = "reuben.document.elasticsearch", name = "enabled", havingValue = "true", matchIfMissing = true)
public class EsKnowledgeRouteIndexService implements KnowledgeRouteIndexService {

    private static final String ENTITY_TYPE_SCOPE = "scope";
    private static final String ENTITY_TYPE_TOPIC = "topic";
    private static final String ENTITY_TYPE_DOCUMENT = "document";

    private final ObjectProvider<ElasticsearchClient> esClientProvider;
    private final IKnowledgeScopeNodeMapper scopeNodeMapper;
    private final IKnowledgeTopicNodeMapper topicNodeMapper;
    private final IDocumentMapper documentMapper;
    private final IDocumentProfileMapper documentProfileMapper;
    private final String indexName;
    private final ReentrantLock refreshLock = new ReentrantLock();
    private final ScheduledExecutorService refreshScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "es-knowledge-route-refresh");
        t.setDaemon(true);
        return t;
    });
    private volatile boolean refreshPending;

    public EsKnowledgeRouteIndexService(ObjectProvider<ElasticsearchClient> esClientProvider,
                                         IKnowledgeScopeNodeMapper scopeNodeMapper,
                                         IKnowledgeTopicNodeMapper topicNodeMapper,
                                         IDocumentMapper documentMapper,
                                         IDocumentProfileMapper documentProfileMapper,
                                         DocumentProperties properties) {
        this.esClientProvider = esClientProvider;
        this.scopeNodeMapper = scopeNodeMapper;
        this.topicNodeMapper = topicNodeMapper;
        this.documentMapper = documentMapper;
        this.documentProfileMapper = documentProfileMapper;
        this.indexName = properties.getElasticsearch().getRouteIndexName();
    }

    // ==================== 对外接口 ====================

    @Override
    public List<RouteLexicalHit> search(String routingText, String entityType, int size) {
        if (routingText == null || routingText.isBlank()) {
            return Collections.emptyList();
        }
        ElasticsearchClient esClient = esClientProvider.getIfAvailable();
        if (esClient == null) {
            log.warn("ElasticsearchClient 不可用，路由词法搜索返回空");
            return Collections.emptyList();
        }

        try {
            SearchResponse<Map> response = esClient.search(s -> {
                s.index(indexName)
                 .query(q -> q
                         .bool(b -> {
                             if (entityType != null && !entityType.isBlank()) {
                                 b.filter(f -> f.term(t -> t.field("entityType").value(entityType)));
                             }
                             b.should(sh -> sh.matchPhrase(mp -> mp.field("displayName").boost(12.0f).query(routingText)));
                             b.should(sh -> sh.multiMatch(mm -> mm
                                     .fields("displayName^10", "aliasesText^8", "examplesText^6",
                                             "summaryText^5", "routeText^4", "descriptionText^3")
                                     .query(routingText)));
                             b.minimumShouldMatch("1");
                             return b;
                         }))
                 .size(Math.min(size, 10));
                return s;
            }, Map.class);

            List<RouteLexicalHit> hits = new ArrayList<>();
            for (Hit<Map> hit : response.hits().hits()) {
                Map<String, Object> src = hit.source();
                if (src == null) continue;
                hits.add(RouteLexicalHit.builder()
                        .routeId(asString(src.get("routeId")))
                        .entityCode(asString(src.get("entityCode")))
                        .entityType(asString(src.get("entityType")))
                        .documentId(asLong(src.get("documentId")))
                        .scopeCode(asString(src.get("scopeCode")))
                        .topicCode(asString(src.get("topicCode")))
                        .documentName(asString(src.get("documentName")))
                        .score(hit.score() != null ? hit.score() : 0.0)
                        .build());
            }
            log.debug("知识路由 ES 搜索完成: entityType={} hits={}", entityType, hits.size());
            return hits;

        } catch (IOException e) {
            log.error("知识路由 ES 搜索异常: query={}", routingText, e);
            return Collections.emptyList();
        }
    }

    @Override
    public void refreshAll() {
        scheduleRefresh();
    }

    @Override
    public void deleteDocumentRoute(Long documentId) {
        if (documentId == null) return;
        ElasticsearchClient esClient = esClientProvider.getIfAvailable();
        if (esClient == null) return;

        try {
            esClient.deleteByQuery(d -> d
                    .index(indexName)
                    .refresh(true)
                    .query(q -> q.term(t -> t.field("documentId").value(documentId))));
            log.info("知识路由索引已删除文档记录: documentId={}", documentId);
        } catch (IOException e) {
            log.error("知识路由索引删除失败: documentId={}", documentId, e);
        }
    }

    // ==================== 刷新调度 ====================

    private void scheduleRefresh() {
        if (refreshPending) {
            return;
        }
        synchronized (this) {
            if (refreshPending) {
                return;
            }
            refreshPending = true;
        }
        refreshScheduler.schedule(this::doRefresh, 30, TimeUnit.SECONDS);
    }

    private void doRefresh() {
        if (!refreshLock.tryLock()) {
            log.debug("知识路由索引刷新已在执行中，跳过");
            return;
        }
        try {
            refreshPending = false;
            ElasticsearchClient esClient = esClientProvider.getIfAvailable();
            if (esClient == null) {
                log.warn("ElasticsearchClient 不可用，跳过知识路由索引刷新");
                return;
            }

            // 清空旧索引
            try {
                esClient.deleteByQuery(d -> d.index(indexName)
                        .refresh(true)
                        .query(q -> q.matchAll(m -> m)));
            } catch (IOException e) {
                log.error("知识路由索引清空失败", e);
                return;
            }

            // 三类实体批量写入
            indexScopes(esClient);
            indexTopics(esClient);
            indexDocuments(esClient);

            log.info("知识路由索引全量刷新完成");
        } catch (Exception e) {
            log.error("知识路由索引全量刷新异常", e);
        } finally {
            refreshLock.unlock();
        }
    }

    // ==================== 分类索引 ====================

    private void indexScopes(ElasticsearchClient esClient) {
        List<KnowledgeScopeNode> scopes = scopeNodeMapper.selectList(
                new LambdaQueryWrapper<KnowledgeScopeNode>().eq(KnowledgeScopeNode::getIsDeleted, 0));
        if (scopes.isEmpty()) {
            log.debug("无 scope 节点，跳过索引");
            return;
        }

        List<KnowledgeRouteIndexRecord> records = new ArrayList<>();
        for (KnowledgeScopeNode s : scopes) {
            String routeText = buildRouteText(s.getScopeName(), s.getDescription(), s.getAliases(), s.getExamples(), null);
            records.add(KnowledgeRouteIndexRecord.builder()
                    .routeId(ENTITY_TYPE_SCOPE + ":" + s.getScopeCode())
                    .entityType(ENTITY_TYPE_SCOPE)
                    .entityCode(s.getScopeCode())
                    .scopeCode(s.getScopeCode())
                    .scopeName(s.getScopeName())
                    .displayName(s.getScopeName())
                    .descriptionText(s.getDescription())
                    .aliasesText(s.getAliases())
                    .examplesText(s.getExamples())
                    .routeText(routeText)
                    .entityTerms(extractEntityTerms(s.getScopeCode(), s.getScopeName(), s.getAliases()))
                    .tags(Collections.emptyList())
                    .build());
        }
        bulkIndex(esClient, records);
        log.info("scope 路由索引完成: count={}", records.size());
    }

    private void indexTopics(ElasticsearchClient esClient) {
        List<KnowledgeTopicNode> topics = topicNodeMapper.selectList(
                new LambdaQueryWrapper<KnowledgeTopicNode>().eq(KnowledgeTopicNode::getIsDeleted, 0));
        if (topics.isEmpty()) {
            log.debug("无 topic 节点，跳过索引");
            return;
        }

        List<KnowledgeRouteIndexRecord> records = new ArrayList<>();
        for (KnowledgeTopicNode t : topics) {
            String scopeName = resolveScopeName(t.getScopeCode());
            String routeText = buildRouteText(t.getTopicName(), t.getDescription(), t.getAliases(), t.getExamples(), null);
            records.add(KnowledgeRouteIndexRecord.builder()
                    .routeId(ENTITY_TYPE_TOPIC + ":" + t.getTopicCode())
                    .entityType(ENTITY_TYPE_TOPIC)
                    .entityCode(t.getTopicCode())
                    .scopeCode(t.getScopeCode())
                    .scopeName(scopeName)
                    .topicCode(t.getTopicCode())
                    .topicName(t.getTopicName())
                    .displayName(t.getTopicName())
                    .descriptionText(t.getDescription())
                    .aliasesText(t.getAliases())
                    .examplesText(t.getExamples())
                    .routeText(routeText)
                    .entityTerms(extractEntityTerms(t.getTopicCode(), t.getTopicName(), t.getAliases()))
                    .tags(Collections.emptyList())
                    .build());
        }
        bulkIndex(esClient, records);
        log.info("topic 路由索引完成: count={}", records.size());
    }

    private void indexDocuments(ElasticsearchClient esClient) {
        List<Document> docs = documentMapper.selectList(
                new LambdaQueryWrapper<Document>().eq(Document::getIsDeleted, 0));
        if (docs.isEmpty()) {
            log.debug("无文档，跳过索引");
            return;
        }

        List<KnowledgeRouteIndexRecord> records = new ArrayList<>();
        for (Document doc : docs) {
            DocumentProfile profile = getProfile(doc.getId());
            String summary = profile != null ? profile.getDocumentSummary() : null;
            String coreTopics = profile != null ? profile.getCoreTopics() : null;
            String examples = profile != null ? profile.getExampleQuestions() : null;

            String routeText = buildRouteText(doc.getDocumentName(), null, null, examples,
                    summary + " " + (coreTopics != null ? coreTopics : ""));
            records.add(KnowledgeRouteIndexRecord.builder()
                    .routeId(ENTITY_TYPE_DOCUMENT + ":" + doc.getId())
                    .entityType(ENTITY_TYPE_DOCUMENT)
                    .documentId(doc.getId())
                    .scopeCode(doc.getKnowledgeScopeCode())
                    .scopeName(doc.getKnowledgeScopeName())
                    .businessCategory(doc.getBusinessCategory())
                    .displayName(doc.getDocumentName())
                    .documentName(doc.getDocumentName())
                    .summaryText(summary)
                    .routeText(routeText)
                    .entityTerms(extractEntityTerms(null, doc.getDocumentName(), null))
                    .tags(doc.getDocumentTags() != null
                            ? Arrays.asList(doc.getDocumentTags().split(","))
                            : Collections.emptyList())
                    .build());
        }
        bulkIndex(esClient, records);
        log.info("document 路由索引完成: count={}", records.size());
    }

    // ==================== 工具方法 ====================

    private void bulkIndex(ElasticsearchClient esClient, List<KnowledgeRouteIndexRecord> records) {
        if (records.isEmpty()) return;

        BulkRequest.Builder bulkBuilder = new BulkRequest.Builder();
        for (KnowledgeRouteIndexRecord r : records) {
            bulkBuilder.operations(op -> op
                    .index(idx -> idx
                            .index(indexName)
                            .id(r.getRouteId())
                            .document(toEsDoc(r))));
        }

        try {
            BulkResponse response = esClient.bulk(bulkBuilder.build());
            long failedCount = response.items().stream()
                    .filter(item -> item.error() != null)
                    .count();
            if (failedCount > 0) {
                log.warn("知识路由 ES bulk 索引部分失败: total={} failed={}", response.items().size(), failedCount);
            }
        } catch (Exception e) {
            log.error("知识路由 ES bulk 索引异常: count={}", records.size(), e);
        }
    }

    private Map<String, Object> toEsDoc(KnowledgeRouteIndexRecord r) {
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("routeId", r.getRouteId());
        doc.put("entityType", r.getEntityType());
        if (r.getEntityCode() != null) doc.put("entityCode", r.getEntityCode());
        if (r.getDocumentId() != null) doc.put("documentId", r.getDocumentId());
        if (r.getScopeCode() != null) doc.put("scopeCode", r.getScopeCode());
        if (r.getScopeName() != null) doc.put("scopeName", r.getScopeName());
        if (r.getTopicCode() != null) doc.put("topicCode", r.getTopicCode());
        if (r.getTopicName() != null) doc.put("topicName", r.getTopicName());
        if (r.getDocumentName() != null) doc.put("documentName", r.getDocumentName());
        if (r.getBusinessCategory() != null) doc.put("businessCategory", r.getBusinessCategory());
        if (r.getDisplayName() != null) doc.put("displayName", r.getDisplayName());
        if (r.getDescriptionText() != null) doc.put("descriptionText", r.getDescriptionText());
        if (r.getAliasesText() != null) doc.put("aliasesText", r.getAliasesText());
        if (r.getExamplesText() != null) doc.put("examplesText", r.getExamplesText());
        if (r.getSummaryText() != null) doc.put("summaryText", r.getSummaryText());
        if (r.getRouteText() != null) doc.put("routeText", r.getRouteText());
        if (r.getEntityTerms() != null) doc.put("entityTerms", r.getEntityTerms());
        if (r.getTags() != null) doc.put("tags", r.getTags());
        return doc;
    }

    private String buildRouteText(String name, String description, String aliases, String examples, String extra) {
        StringBuilder sb = new StringBuilder();
        if (name != null) sb.append(name).append(" ");
        if (description != null) sb.append(description).append(" ");
        if (aliases != null) sb.append(aliases).append(" ");
        if (examples != null) sb.append(examples).append(" ");
        if (extra != null) sb.append(extra);
        return sb.toString().trim();
    }

    /**
     * 提取实体分词列表：code + name + aliases 的分词去重。
     */
    private List<String> extractEntityTerms(String code, String name, String aliases) {
        List<String> terms = new ArrayList<>();
        if (code != null && !code.isBlank()) {
            terms.add(code.trim());
        }
        if (name != null && !name.isBlank()) {
            for (String part : name.split("[\\s、，,；;：:（）()]+")) {
                String t = part.trim();
                if (!t.isBlank()) terms.add(t);
            }
        }
        if (aliases != null && !aliases.isBlank()) {
            for (String part : aliases.split(",")) {
                String t = part.trim();
                if (!t.isBlank()) terms.add(t);
            }
        }
        return terms.stream().distinct().toList();
    }

    private String resolveScopeName(String scopeCode) {
        if (scopeCode == null) return null;
        KnowledgeScopeNode scope = scopeNodeMapper.selectOne(
                new LambdaQueryWrapper<KnowledgeScopeNode>()
                        .eq(KnowledgeScopeNode::getScopeCode, scopeCode)
                        .eq(KnowledgeScopeNode::getIsDeleted, 0));
        return scope != null ? scope.getScopeName() : null;
    }

    private DocumentProfile getProfile(Long documentId) {
        if (documentId == null) return null;
        return documentProfileMapper.selectOne(
                new LambdaQueryWrapper<DocumentProfile>()
                        .eq(DocumentProfile::getDocumentId, documentId)
                        .eq(DocumentProfile::getIsDeleted, 0));
    }

    private static String asString(Object v) {
        return v != null ? v.toString() : null;
    }

    private static Long asLong(Object v) {
        if (v == null) return null;
        if (v instanceof Number n) return n.longValue();
        try { return Long.parseLong(v.toString()); } catch (NumberFormatException e) { return null; }
    }
}
