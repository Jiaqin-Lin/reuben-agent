package com.reubenagent.document.service.impl;

import com.alibaba.fastjson.JSON;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.reubenagent.document.config.DocumentProperties;
import com.reubenagent.document.entity.Document;
import com.reubenagent.document.entity.DocumentStructureNode;
import com.reubenagent.document.entity.DocumentTask;
import com.reubenagent.document.enums.DocumentStructureNodeTypeEnum;
import com.reubenagent.document.mapper.IDocumentMapper;
import com.reubenagent.document.mapper.IDocumentTaskMapper;
import com.reubenagent.document.service.DocumentStructureGraphProjectionService;
import com.reubenagent.document.service.IDocumentStructureNodeService;
import lombok.extern.slf4j.Slf4j;
import org.neo4j.driver.Driver;
import org.neo4j.driver.Query;
import org.neo4j.driver.Session;
import org.neo4j.driver.SessionConfig;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Neo4j 图投影服务 —— MySQL 结构节点 → Neo4j Document/Section/Item + 关系。
 *
 * <p>单次 {@code session.executeWrite} 事务内完成清空 + 创建节点 + 建关系，
 * 失败打 warn 日志并落 task {@code extJson} 记录 graph_index_status=FAILED。</p>
 *
 * @author reuben
 * @since 2026-06-28
 */
@Slf4j
@Service
@ConditionalOnBean(name = "documentManageNeo4jDriver")
public class Neo4jDocumentStructureGraphProjectionService implements DocumentStructureGraphProjectionService {

    private final Driver driver;
    private final DocumentProperties properties;
    private final IDocumentStructureNodeService structureNodeService;
    private final IDocumentMapper documentMapper;
    private final ObjectProvider<IDocumentTaskMapper> taskMapperProvider;

    public Neo4jDocumentStructureGraphProjectionService(Driver documentManageNeo4jDriver,
                                                        DocumentProperties properties,
                                                        IDocumentStructureNodeService structureNodeService,
                                                        IDocumentMapper documentMapper,
                                                        ObjectProvider<IDocumentTaskMapper> taskMapperProvider) {
        this.driver = documentManageNeo4jDriver;
        this.properties = properties;
        this.structureNodeService = structureNodeService;
        this.documentMapper = documentMapper;
        this.taskMapperProvider = taskMapperProvider;
    }

    @jakarta.annotation.PostConstruct
    public void initSchema() {
        try (Session session = newSession()) {
            session.run("CREATE INDEX IF NOT EXISTS FOR (d:Document) ON (d.documentId)");
            session.run("CREATE INDEX IF NOT EXISTS FOR (s:Section) ON (s.nodeId)");
            session.run("CREATE INDEX IF NOT EXISTS FOR (s:Section) ON (s.documentId)");
            session.run("CREATE INDEX IF NOT EXISTS FOR (s:Section) ON (s.documentId, s.nodeId)");
            session.run("CREATE INDEX IF NOT EXISTS FOR (s:Section) ON (s.documentId, s.nodeCode)");
            session.run("CREATE INDEX IF NOT EXISTS FOR (s:Section) ON (s.documentId, s.normalizedTitle)");
            session.run("CREATE INDEX IF NOT EXISTS FOR (i:Item) ON (i.nodeId)");
            session.run("CREATE INDEX IF NOT EXISTS FOR (i:Item) ON (i.documentId, i.nodeId)");
            log.info("Neo4j 图索引初始化完成");
        } catch (Exception e) {
            log.warn("Neo4j 图索引初始化失败（可能已存在）: {}", e.getMessage());
        }
    }

    @Override
    public void projectToGraph(Long documentId, Long parseTaskId) {
        projectToGraph(documentId, parseTaskId, null, null);
    }

    /**
     * 投影指定节点列表到 Neo4j。可直接传入已加载的结构节点，避免重复查库；
     * nodes 为 null 时回退到 {@code structureNodeService} 全量加载。
     */
    public void projectToGraph(Long documentId, Long parseTaskId,
                               String documentNameIn,
                               List<DocumentStructureNode> nodesIn) {
        if (documentId == null) {
            return;
        }
        List<DocumentStructureNode> nodes = nodesIn;
        if (nodes == null) {
            if (structureNodeService == null) {
                log.warn("structureNodeService 未注入且 nodes 为空，跳过投影: documentId={}", documentId);
                return;
            }
            nodes = structureNodeService.listDocumentNodes(documentId, parseTaskId);
        }
        if (nodes.isEmpty()) {
            log.info("文档无结构节点，跳过 Neo4j 投影: documentId={}", documentId);
            return;
        }
        String documentName = documentNameIn;
        if (documentName == null && documentMapper != null) {
            Document document = documentMapper.selectById(documentId);
            documentName = document != null ? document.getDocumentName() : null;
        }
        final List<DocumentStructureNode> finalNodes = nodes;
        final String finalDocumentName = documentName;
        try (Session session = newSession()) {
            session.executeWrite(tx -> {
                // 清空旧图
                tx.run(new Query(
                        "MATCH (n) WHERE (n:Document OR n:Section OR n:Item) AND n.documentId = $documentId " +
                                "DETACH DELETE n",
                        Map.of("documentId", documentId)));

                // 创建 Document 节点
                tx.run(new Query(
                        "CREATE (d:Document {documentId: $documentId, documentName: $documentName, " +
                                "parseTaskId: $parseTaskId})",
                        Map.of("documentId", documentId,
                                "documentName", nullToEmpty(finalDocumentName),
                                "parseTaskId", parseTaskId)));

                // 分类节点
                List<Map<String, Object>> sections = new ArrayList<>();
                List<Map<String, Object>> items = new ArrayList<>();
                for (DocumentStructureNode node : finalNodes) {
                    Integer nodeType = node.getNodeType();
                    if (isSection(nodeType)) {
                        sections.add(toSectionParams(node));
                    } else if (isItem(nodeType)) {
                        items.add(toItemParams(node));
                    }
                }

                // 批量创建 Section
                for (Map<String, Object> params : sections) {
                    tx.run(new Query(
                            "CREATE (s:Section {nodeId: $nodeId, documentId: $documentId, parseTaskId: $parseTaskId, " +
                                    "nodeNo: $nodeNo, depth: $depth, parentNodeId: $parentNodeId, " +
                                    "prevSiblingNodeId: $prevSiblingNodeId, nextSiblingNodeId: $nextSiblingNodeId, " +
                                    "nodeCode: $nodeCode, title: $title, normalizedTitle: $normalizedTitle, " +
                                    "anchorText: $anchorText, sectionPath: $sectionPath, canonicalPath: $canonicalPath, " +
                                    "contentText: $contentText})",
                            params));
                }

                // 批量创建 Item
                for (Map<String, Object> params : items) {
                    tx.run(new Query(
                            "CREATE (i:Item {nodeId: $nodeId, documentId: $documentId, parseTaskId: $parseTaskId, " +
                                    "nodeNo: $nodeNo, nodeType: $nodeType, sectionNodeId: $sectionNodeId, " +
                                    "prevSiblingNodeId: $prevSiblingNodeId, nextSiblingNodeId: $nextSiblingNodeId, " +
                                    "title: $title, anchorText: $anchorText, sectionPath: $sectionPath, " +
                                    "canonicalPath: $canonicalPath, contentText: $contentText, itemIndex: $itemIndex})",
                            params));
                }

                // 关系：Document -[:HAS_SECTION]-> Section / Section -[:BELONGS_TO_DOCUMENT]-> Document
                tx.run(new Query(
                        "MATCH (d:Document {documentId: $documentId}), (s:Section {documentId: $documentId}) " +
                                "MERGE (d)-[:HAS_SECTION]->(s) MERGE (s)-[:BELONGS_TO_DOCUMENT]->(d)",
                        Map.of("documentId", documentId)));
                tx.run(new Query(
                        "MATCH (d:Document {documentId: $documentId}), (i:Item {documentId: $documentId}) " +
                                "MERGE (d)-[:HAS_ITEM]->(i) MERGE (i)-[:BELONGS_TO_DOCUMENT]->(d)",
                        Map.of("documentId", documentId)));

                // 父子 Section
                for (DocumentStructureNode node : finalNodes) {
                    if (isSection(node.getNodeType()) && node.getParentNodeId() != null) {
                        tx.run(new Query(
                                "MATCH (p:Section {nodeId: $parentId}), (s:Section {nodeId: $childId}) " +
                                        "MERGE (p)-[:HAS_CHILD]->(s)",
                                Map.of("parentId", node.getParentNodeId(), "childId", node.getId())));
                    }
                }

                // 兄弟链 Section
                for (DocumentStructureNode node : finalNodes) {
                    if (isSection(node.getNodeType()) && node.getNextSiblingNodeId() != null) {
                        tx.run(new Query(
                                "MATCH (a:Section {nodeId: $aId}), (b:Section {nodeId: $bId}) " +
                                        "MERGE (a)-[:NEXT_SIBLING]->(b) MERGE (b)-[:PREV_SIBLING]->(a)",
                                Map.of("aId", node.getId(), "bId", node.getNextSiblingNodeId())));
                    }
                }

                // Section -[:HAS_ITEM]-> Item
                for (Map<String, Object> params : items) {
                    Long sectionNodeId = (Long) params.get("sectionNodeId");
                    Long nodeId = (Long) params.get("nodeId");
                    if (sectionNodeId != null && nodeId != null) {
                        tx.run(new Query(
                                "MATCH (s:Section {nodeId: $sectionNodeId}), (i:Item {nodeId: $nodeId}) " +
                                        "MERGE (s)-[:HAS_ITEM]->(i) MERGE (i)-[:BELONGS_TO_SECTION]->(s)",
                                Map.of("sectionNodeId", sectionNodeId, "nodeId", nodeId)));
                    }
                }

                // 兄弟链 Item
                for (DocumentStructureNode node : finalNodes) {
                    if (isItem(node.getNodeType()) && node.getNextSiblingNodeId() != null) {
                        tx.run(new Query(
                                "MATCH (a:Item {nodeId: $aId}), (b:Item {nodeId: $bId}) " +
                                        "MERGE (a)-[:NEXT_ITEM]->(b) MERGE (b)-[:PREV_ITEM]->(a)",
                                Map.of("aId", node.getId(), "bId", node.getNextSiblingNodeId())));
                    }
                }
                return null;
            });

            markAvailable(documentId, true);
            updateTaskGraphStatus(parseTaskId, "SUCCESS", null);
            log.info("Neo4j 图投影完成: documentId={}, sections={}, items={}",
                    documentId, sectionsCount(finalNodes), itemsCount(finalNodes));
        } catch (Exception e) {
            log.warn("Neo4j 图投影失败: documentId={} error={}", documentId, e.getMessage());
            markAvailable(documentId, false);
            updateTaskGraphStatus(parseTaskId, "FAILED", e.getMessage());
        }
    }

    @Override
    public void deleteByDocumentId(Long documentId) {
        if (documentId == null) {
            return;
        }
        try (Session session = newSession()) {
            session.run(new Query(
                    "MATCH (n) WHERE n.documentId = $documentId DETACH DELETE n",
                    Map.of("documentId", documentId)));
            markAvailable(documentId, false);
            log.info("Neo4j 图数据已删除: documentId={}", documentId);
        } catch (Exception e) {
            log.warn("Neo4j 图删除失败: documentId={} error={}", documentId, e.getMessage());
        }
    }

    @Override
    public void markAvailable(Long documentId, boolean available) {
        CompositeGraphAvailabilityCache.set(documentId, available);
    }

    // ============ 内部工具 ============

    private Session newSession() {
        String database = properties.getNeo4j().getDatabase();
        if (database == null || database.isBlank()) {
            return driver.session();
        }
        return driver.session(SessionConfig.forDatabase(database));
    }

    private boolean isSection(Integer nodeType) {
        return DocumentStructureNodeTypeEnum.ROOT.getCode().equals(nodeType)
                || DocumentStructureNodeTypeEnum.CHAPTER.getCode().equals(nodeType);
    }

    private boolean isItem(Integer nodeType) {
        return DocumentStructureNodeTypeEnum.STEP.getCode().equals(nodeType)
                || DocumentStructureNodeTypeEnum.LIST_ITEM.getCode().equals(nodeType);
    }

    private Map<String, Object> toSectionParams(DocumentStructureNode n) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("nodeId", n.getId());
        p.put("documentId", n.getDocumentId());
        p.put("parseTaskId", n.getParseTaskId());
        p.put("nodeNo", n.getNodeNo());
        p.put("depth", n.getDepth());
        p.put("parentNodeId", n.getParentNodeId());
        p.put("prevSiblingNodeId", n.getPrevSiblingNodeId());
        p.put("nextSiblingNodeId", n.getNextSiblingNodeId());
        p.put("nodeCode", nullToEmpty(n.getNodeCode()));
        p.put("title", nullToEmpty(n.getTitle()));
        p.put("normalizedTitle", normalizeTitle(n.getTitle()));
        p.put("anchorText", nullToEmpty(n.getAnchorText()));
        p.put("sectionPath", nullToEmpty(n.getSectionPath()));
        p.put("canonicalPath", nullToEmpty(n.getCanonicalPath()));
        p.put("contentText", nullToEmpty(n.getContentText()));
        return p;
    }

    private Map<String, Object> toItemParams(DocumentStructureNode n) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("nodeId", n.getId());
        p.put("documentId", n.getDocumentId());
        p.put("parseTaskId", n.getParseTaskId());
        p.put("nodeNo", n.getNodeNo());
        p.put("nodeType", n.getNodeType());
        p.put("sectionNodeId", n.getParentNodeId());
        p.put("prevSiblingNodeId", n.getPrevSiblingNodeId());
        p.put("nextSiblingNodeId", n.getNextSiblingNodeId());
        p.put("title", nullToEmpty(n.getTitle()));
        p.put("anchorText", nullToEmpty(n.getAnchorText()));
        p.put("sectionPath", nullToEmpty(n.getSectionPath()));
        p.put("canonicalPath", nullToEmpty(n.getCanonicalPath()));
        p.put("contentText", nullToEmpty(n.getContentText()));
        p.put("itemIndex", n.getItemIndex());
        return p;
    }

    private static String normalizeTitle(String title) {
        return title == null ? "" : title.trim().toLowerCase().replaceAll("\\s+", "");
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    private long sectionsCount(List<DocumentStructureNode> nodes) {
        return nodes.stream().filter(n -> isSection(n.getNodeType())).count();
    }

    private long itemsCount(List<DocumentStructureNode> nodes) {
        return nodes.stream().filter(n -> isItem(n.getNodeType())).count();
    }

    private void updateTaskGraphStatus(Long parseTaskId, String status, String errorMsg) {
        if (parseTaskId == null || taskMapperProvider == null) {
            return;
        }
        IDocumentTaskMapper taskMapper = taskMapperProvider.getIfAvailable();
        if (taskMapper == null) {
            return;
        }
        try {
            Map<String, Object> ext = new HashMap<>();
            ext.put("graph_index_status", status);
            ext.put("last_graph_index_time", System.currentTimeMillis());
            if (errorMsg != null) {
                ext.put("graph_index_error_msg", truncate(errorMsg, 900));
            }
            taskMapper.update(null, new LambdaUpdateWrapper<DocumentTask>()
                    .eq(DocumentTask::getId, parseTaskId)
                    .set(DocumentTask::getExtJson, JSON.toJSONString(ext)));
        } catch (Exception e) {
            log.warn("更新 task graph 状态失败: taskId={} error={}", parseTaskId, e.getMessage());
        }
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max);
    }
}
