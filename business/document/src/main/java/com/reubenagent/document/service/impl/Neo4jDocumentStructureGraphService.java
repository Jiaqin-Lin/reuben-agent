package com.reubenagent.document.service.impl;

import com.reubenagent.common.enums.DocumentManageCode;
import com.reubenagent.common.exception.DocumentException;
import com.reubenagent.document.config.DocumentProperties;
import com.reubenagent.document.model.graph.GraphItem;
import com.reubenagent.document.model.graph.GraphSection;
import com.reubenagent.document.service.DocumentStructureGraphService;
import lombok.extern.slf4j.Slf4j;
import org.neo4j.driver.Driver;
import org.neo4j.driver.Query;
import org.neo4j.driver.Result;
import org.neo4j.driver.Session;
import org.neo4j.driver.SessionConfig;
import org.neo4j.driver.Value;
import org.neo4j.driver.types.Node;
import org.neo4j.driver.types.Type;
import org.neo4j.driver.types.TypeSystem;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Neo4j 图查询服务 —— 参数化 Cypher 查询章节/条目，异常打 warn 后抛
 * {@link DocumentException}(NEO4J_QUERY_FAILED) 让 Composite 回退 MySQL。
 *
 * @author reuben
 * @since 2026-06-28
 */
@Slf4j
@Service
@ConditionalOnBean(name = "documentManageNeo4jDriver")
public class Neo4jDocumentStructureGraphService implements DocumentStructureGraphService {

    private static final Type NODE_TYPE = TypeSystem.getDefault().NODE();

    private final Driver driver;
    private final DocumentProperties properties;

    public Neo4jDocumentStructureGraphService(Driver documentManageNeo4jDriver, DocumentProperties properties) {
        this.driver = documentManageNeo4jDriver;
        this.properties = properties;
    }

    @Override
    public boolean isGraphAvailable(Long documentId) {
        try (Session session = newSession()) {
            Result result = session.run(new Query(
                    "MATCH (s:Section {documentId: $documentId}) RETURN count(s) AS cnt LIMIT 1",
                    params("documentId", documentId)));
            return result.hasNext() && result.next().get("cnt").asLong() > 0;
        } catch (Exception e) {
            log.warn("Neo4j isGraphAvailable 查询失败: documentId={} error={}", documentId, e.getMessage());
            return false;
        }
    }

    @Override
    public GraphSection findSectionById(Long documentId, Long sectionNodeId) {
        if (sectionNodeId == null) {
            return null;
        }
        return querySingleSection(
                "MATCH (s:Section {documentId: $documentId, nodeId: $nodeId}) RETURN s",
                params("documentId", documentId, "nodeId", sectionNodeId));
    }

    @Override
    public GraphSection findSectionByCode(Long documentId, String nodeCode) {
        if (isBlank(nodeCode)) {
            return null;
        }
        return querySingleSection(
                "MATCH (s:Section {documentId: $documentId, nodeCode: $nodeCode}) RETURN s",
                params("documentId", documentId, "nodeCode", nodeCode));
    }

    @Override
    public GraphSection findSectionByTitle(Long documentId, String title) {
        if (isBlank(title)) {
            return null;
        }
        String normalized = normalizeTitle(title);
        GraphSection exact = querySingleSection(
                "MATCH (s:Section {documentId: $documentId, normalizedTitle: $normalizedTitle}) RETURN s",
                params("documentId", documentId, "normalizedTitle", normalized));
        if (exact != null) {
            return exact;
        }
        return querySingleSection(
                "MATCH (s:Section {documentId: $documentId}) WHERE s.normalizedTitle CONTAINS $normalizedTitle RETURN s LIMIT 1",
                params("documentId", documentId, "normalizedTitle", normalized));
    }

    @Override
    public GraphSection findSectionByCanonicalPath(Long documentId, String canonicalPath) {
        if (isBlank(canonicalPath)) {
            return null;
        }
        return querySingleSection(
                "MATCH (s:Section {documentId: $documentId, canonicalPath: $canonicalPath}) RETURN s",
                params("documentId", documentId, "canonicalPath", canonicalPath));
    }

    @Override
    public GraphSection findBestSection(Long documentId, String topic, String facet) {
        List<GraphSection> sections = listSections(documentId);
        if (sections.isEmpty()) {
            return null;
        }
        String topicLc = lower(topic);
        String facetLc = lower(facet);
        GraphSection best = null;
        double bestScore = 0;
        for (GraphSection s : sections) {
            double score = scoreSection(s, topicLc, facetLc);
            if (score > bestScore) {
                bestScore = score;
                best = s;
            }
        }
        return best;
    }

    @Override
    public List<GraphSection> listSections(Long documentId) {
        return querySections(
                "MATCH (s:Section {documentId: $documentId}) RETURN s ORDER BY s.nodeNo",
                params("documentId", documentId));
    }

    @Override
    public List<GraphSection> listChildren(Long documentId, Long sectionNodeId) {
        if (sectionNodeId == null) {
            return List.of();
        }
        return querySections(
                "MATCH (p:Section {documentId: $documentId, nodeId: $nodeId})-[:HAS_CHILD]->(s:Section) RETURN s ORDER BY s.nodeNo",
                params("documentId", documentId, "nodeId", sectionNodeId));
    }

    @Override
    public GraphSection parentSection(Long documentId, Long sectionNodeId) {
        if (sectionNodeId == null) {
            return null;
        }
        return querySingleSection(
                "MATCH (p:Section {documentId: $documentId})-[:HAS_CHILD]->(s:Section {nodeId: $nodeId}) RETURN p LIMIT 1",
                params("documentId", documentId, "nodeId", sectionNodeId));
    }

    @Override
    public GraphSection previousSibling(Long documentId, Long sectionNodeId) {
        if (sectionNodeId == null) {
            return null;
        }
        // 直接读节点的 prevSiblingNodeId 字段，避免依赖 NEXT_SIBLING 关系方向
        return querySingleSection(
                "MATCH (s:Section {documentId: $documentId, nodeId: $nodeId}) RETURN s",
                params("documentId", documentId, "nodeId", sectionNodeId),
                s -> {
                    Long prevId = s.getPrevSiblingNodeId();
                    return prevId == null ? null : findSectionById(documentId, prevId);
                });
    }

    @Override
    public GraphSection nextSibling(Long documentId, Long sectionNodeId) {
        if (sectionNodeId == null) {
            return null;
        }
        return querySingleSection(
                "MATCH (s:Section {documentId: $documentId, nodeId: $nodeId}) RETURN s",
                params("documentId", documentId, "nodeId", sectionNodeId),
                s -> {
                    Long nextId = s.getNextSiblingNodeId();
                    return nextId == null ? null : findSectionById(documentId, nextId);
                });
    }

    @Override
    public GraphItem findItemByIndex(Long documentId, Long sectionNodeId, Integer itemIndex) {
        if (sectionNodeId == null || itemIndex == null) {
            return null;
        }
        List<GraphItem> items = queryItems(
                "MATCH (s:Section {documentId: $documentId, nodeId: $nodeId})-[:HAS_ITEM]->(i:Item) WHERE i.itemIndex = $itemIndex RETURN i",
                params("documentId", documentId, "nodeId", sectionNodeId, "itemIndex", itemIndex));
        return items.isEmpty() ? null : items.get(0);
    }

    @Override
    public List<GraphItem> listItems(Long documentId, Long sectionNodeId) {
        if (sectionNodeId == null) {
            return List.of();
        }
        List<GraphItem> items = queryItems(
                "MATCH (s:Section {documentId: $documentId, nodeId: $nodeId})-[:HAS_ITEM]->(i:Item) RETURN i",
                params("documentId", documentId, "nodeId", sectionNodeId));
        items.sort(Comparator.comparing(GraphItem::getItemIndex,
                Comparator.nullsLast(Comparator.naturalOrder())));
        return items;
    }

    @Override
    public List<GraphItem> searchItemsInSection(Long documentId, Long sectionNodeId, String keyword) {
        if (sectionNodeId == null || isBlank(keyword)) {
            return List.of();
        }
        String needle = keyword.trim().toLowerCase();
        List<GraphItem> result = new ArrayList<>();
        collectItems(documentId, sectionNodeId, needle, result);
        return result;
    }

    private void collectItems(Long documentId, Long sectionNodeId, String needle, List<GraphItem> sink) {
        for (GraphItem item : listItems(documentId, sectionNodeId)) {
            if (containsLower(item.getContentText(), needle)
                    || containsLower(item.getAnchorText(), needle)
                    || containsLower(item.getTitle(), needle)) {
                sink.add(item);
            }
        }
        for (GraphSection child : listChildren(documentId, sectionNodeId)) {
            collectItems(documentId, child.getNodeId(), needle, sink);
        }
    }

    // ============ Cypher 执行与映射 ============

    private GraphSection querySingleSection(String cypher, Map<String, Object> params) {
        return querySingleSection(cypher, params, null);
    }

    /**
     * 查询单个章节，可选后处理（如基于结果字段再查兄弟节点）。
     *
     * @param postProcess 拿到首条 section 后的转换函数，为 null 则直接返回
     */
    private GraphSection querySingleSection(String cypher, Map<String, Object> params,
                                            java.util.function.Function<GraphSection, GraphSection> postProcess) {
        try (Session session = newSession()) {
            Result result = session.run(new Query(cypher, params));
            if (!result.hasNext()) {
                return null;
            }
            GraphSection section = mapSection(result.next().get("s"));
            if (section == null) {
                return null;
            }
            return postProcess == null ? section : postProcess.apply(section);
        } catch (DocumentException e) {
            throw e;
        } catch (Exception e) {
            log.warn("Neo4j 查询章节失败: cypher={} error={}", firstLine(cypher), e.getMessage());
            throw new DocumentException(DocumentManageCode.NEO4J_QUERY_FAILED, cypher, e);
        }
    }

    private List<GraphSection> querySections(String cypher, Map<String, Object> params) {
        try (Session session = newSession()) {
            Result result = session.run(new Query(cypher, params));
            List<GraphSection> sections = new ArrayList<>();
            while (result.hasNext()) {
                GraphSection s = mapSection(result.next().get("s"));
                if (s != null) {
                    sections.add(s);
                }
            }
            return sections;
        } catch (DocumentException e) {
            throw e;
        } catch (Exception e) {
            log.warn("Neo4j 查询章节列表失败: cypher={} error={}", firstLine(cypher), e.getMessage());
            throw new DocumentException(DocumentManageCode.NEO4J_QUERY_FAILED, cypher, e);
        }
    }

    private List<GraphItem> queryItems(String cypher, Map<String, Object> params) {
        try (Session session = newSession()) {
            Result result = session.run(new Query(cypher, params));
            List<GraphItem> items = new ArrayList<>();
            while (result.hasNext()) {
                GraphItem i = mapItem(result.next().get("i"));
                if (i != null) {
                    items.add(i);
                }
            }
            return items;
        } catch (DocumentException e) {
            throw e;
        } catch (Exception e) {
            log.warn("Neo4j 查询条目列表失败: cypher={} error={}", firstLine(cypher), e.getMessage());
            throw new DocumentException(DocumentManageCode.NEO4J_QUERY_FAILED, cypher, e);
        }
    }

    private GraphSection mapSection(Value value) {
        Node node = asNode(value);
        if (node == null) {
            return null;
        }
        return GraphSection.builder()
                .nodeId(asLong(node.get("nodeId")))
                .documentId(asLong(node.get("documentId")))
                .parseTaskId(asLong(node.get("parseTaskId")))
                .nodeNo(asInteger(node.get("nodeNo")))
                .depth(asInteger(node.get("depth")))
                .parentNodeId(asLong(node.get("parentNodeId")))
                .prevSiblingNodeId(asLong(node.get("prevSiblingNodeId")))
                .nextSiblingNodeId(asLong(node.get("nextSiblingNodeId")))
                .nodeCode(asText(node.get("nodeCode")))
                .title(asText(node.get("title")))
                .anchorText(asText(node.get("anchorText")))
                .sectionPath(asText(node.get("sectionPath")))
                .canonicalPath(asText(node.get("canonicalPath")))
                .contentText(asText(node.get("contentText")))
                .build();
    }

    private GraphItem mapItem(Value value) {
        Node node = asNode(value);
        if (node == null) {
            return null;
        }
        return GraphItem.builder()
                .nodeId(asLong(node.get("nodeId")))
                .documentId(asLong(node.get("documentId")))
                .parseTaskId(asLong(node.get("parseTaskId")))
                .nodeNo(asInteger(node.get("nodeNo")))
                .nodeType(asInteger(node.get("nodeType")))
                .sectionNodeId(asLong(node.get("sectionNodeId")))
                .prevSiblingNodeId(asLong(node.get("prevSiblingNodeId")))
                .nextSiblingNodeId(asLong(node.get("nextSiblingNodeId")))
                .title(asText(node.get("title")))
                .anchorText(asText(node.get("anchorText")))
                .sectionPath(asText(node.get("sectionPath")))
                .canonicalPath(asText(node.get("canonicalPath")))
                .contentText(asText(node.get("contentText")))
                .itemIndex(asInteger(node.get("itemIndex")))
                .build();
    }

    private static Node asNode(Value value) {
        if (value == null || value.isNull() || !value.hasType(NODE_TYPE)) {
            return null;
        }
        return value.asNode();
    }

    private Session newSession() {
        String database = properties.getNeo4j().getDatabase();
        if (database == null || database.isBlank()) {
            return driver.session();
        }
        return driver.session(SessionConfig.forDatabase(database));
    }

    private double scoreSection(GraphSection s, String topic, String facet) {
        double score = 0;
        if (topic != null) {
            if (containsLower(s.getSectionPath(), topic)) {
                score += 100;
            }
            if (containsLower(s.getTitle(), topic)) {
                score += 90;
            }
            if (containsLower(s.getAnchorText(), topic)) {
                score += 80;
            }
            if (containsLower(s.getContentText(), topic)) {
                score += 45;
            }
        }
        if (facet != null && !facet.isBlank()) {
            if (containsLower(s.getTitle(), facet)) {
                score += 5;
            } else if (containsLower(s.getContentText(), facet)) {
                score += 1;
            }
        }
        return score;
    }

    // ============ Value 安全读取 ============

    private static Long asLong(Value v) {
        return v == null || v.isNull() ? null : v.asLong();
    }

    private static Integer asInteger(Value v) {
        return v == null || v.isNull() ? null : v.asInt();
    }

    private static String asText(Value v) {
        return v == null || v.isNull() ? null : v.asString();
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private static String lower(String s) {
        return s == null ? null : s.toLowerCase();
    }

    private static boolean containsLower(String hay, String lowerNeedle) {
        return hay != null && lowerNeedle != null && hay.toLowerCase().contains(lowerNeedle);
    }

    private static String normalizeTitle(String title) {
        return title == null ? "" : title.trim().toLowerCase().replaceAll("\\s+", "");
    }

    private static String firstLine(String s) {
        return s == null ? "" : s.split("\n")[0];
    }

    /** 构造参数 Map 的便捷方法 */
    private static Map<String, Object> params(Object... kv) {
        Map<String, Object> map = new java.util.LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            map.put((String) kv[i], kv[i + 1]);
        }
        return map;
    }
}
