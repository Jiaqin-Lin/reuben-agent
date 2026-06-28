package com.reubenagent.document.service.impl;

import com.reubenagent.common.enums.DocumentManageCode;
import com.reubenagent.common.exception.DocumentException;
import com.reubenagent.document.config.DocumentProperties;
import com.reubenagent.document.entity.DocumentStructureNode;
import com.reubenagent.document.enums.DocumentStructureNodeTypeEnum;
import com.reubenagent.document.model.graph.GraphItem;
import com.reubenagent.document.model.graph.GraphSection;
import com.reubenagent.document.service.DocumentStructureGraphService;
import com.reubenagent.document.service.IDocumentStructureNodeService;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * MySQL 回退图服务 —— 全量加载结构节点到内存，在 Java 侧做图遍历。
 *
 * <p>使用 {@link ConcurrentHashMap} 缓存 per documentId 的节点列表，大文档（超过
 * {@code cacheNodeThreshold}）降级为每次 SQL 直查，避免单文档占用过多内存。
 * 章节按 sectionPath > title > anchorText > contentText 打分定位。</p>
 *
 * @author reuben
 * @since 2026-06-28
 */
@Slf4j
@Service("mysqlDocumentStructureGraphService")
public class MysqlDocumentStructureGraphService implements DocumentStructureGraphService {

    private final IDocumentStructureNodeService structureNodeService;
    private final DocumentProperties properties;

    /** per documentId 的全量节点缓存 */
    private final ConcurrentHashMap<Long, List<DocumentStructureNode>> nodeCache = new ConcurrentHashMap<>();

    @Autowired
    public MysqlDocumentStructureGraphService(IDocumentStructureNodeService structureNodeService,
                                              DocumentProperties properties) {
        this.structureNodeService = structureNodeService;
        this.properties = properties;
    }

    // ============ 缓存管理 ============

    /** 解析/投影完成后主动失效缓存 */
    public void evict(Long documentId) {
        if (documentId != null) {
            nodeCache.remove(documentId);
        }
    }

    private List<DocumentStructureNode> loadNodes(Long documentId) {
        int threshold = properties.getNeo4j().getCacheNodeThreshold();
        List<DocumentStructureNode> cached = nodeCache.get(documentId);
        if (cached != null) {
            return cached;
        }
        List<DocumentStructureNode> nodes = structureNodeService.listDocumentNodes(documentId, null);
        if (nodes.size() <= threshold) {
            nodeCache.put(documentId, nodes);
        }
        return nodes;
    }

    // ============ 图可用性 ============

    @Override
    public boolean isGraphAvailable(Long documentId) {
        return !loadNodes(documentId).isEmpty();
    }

    // ============ 章节查询 ============

    @Override
    public GraphSection findSectionById(Long documentId, Long sectionNodeId) {
        if (sectionNodeId == null) {
            return null;
        }
        for (DocumentStructureNode node : loadNodes(documentId)) {
            if (sectionNodeId.equals(node.getId())) {
                return isSection(node) ? toSection(node) : null;
            }
        }
        return null;
    }

    @Override
    public GraphSection findSectionByCode(Long documentId, String nodeCode) {
        if (nodeCode == null || nodeCode.isBlank()) {
            return null;
        }
        for (DocumentStructureNode node : loadNodes(documentId)) {
            if (isSection(node) && nodeCode.equals(node.getNodeCode())) {
                return toSection(node);
            }
        }
        return null;
    }

    @Override
    public GraphSection findSectionByTitle(Long documentId, String title) {
        if (title == null || title.isBlank()) {
            return null;
        }
        String needle = title.trim();
        for (DocumentStructureNode node : loadNodes(documentId)) {
            if (isSection(node) && needle.equals(trimTo(node.getTitle()))) {
                return toSection(node);
            }
        }
        for (DocumentStructureNode node : loadNodes(documentId)) {
            if (isSection(node) && containsIgnoreCase(node.getTitle(), needle)) {
                return toSection(node);
            }
        }
        return null;
    }

    @Override
    public GraphSection findSectionByCanonicalPath(Long documentId, String canonicalPath) {
        if (canonicalPath == null || canonicalPath.isBlank()) {
            return null;
        }
        for (DocumentStructureNode node : loadNodes(documentId)) {
            if (isSection(node) && canonicalPath.equals(node.getCanonicalPath())) {
                return toSection(node);
            }
        }
        return null;
    }

    @Override
    public GraphSection findBestSection(Long documentId, String topic, String facet) {
        List<DocumentStructureNode> sections = loadNodes(documentId).stream()
                .filter(this::isSection)
                .collect(Collectors.toList());
        if (sections.isEmpty()) {
            return null;
        }
        DocumentStructureNode best = null;
        double bestScore = 0;
        String topicLc = lower(topic);
        String facetLc = lower(facet);
        for (DocumentStructureNode node : sections) {
            double score = scoreSection(node, topicLc, facetLc);
            if (score > bestScore) {
                bestScore = score;
                best = node;
            }
        }
        return best != null ? toSection(best) : null;
    }

    @Override
    public List<GraphSection> listSections(Long documentId) {
        return loadNodes(documentId).stream()
                .filter(this::isSection)
                .map(this::toSection)
                .collect(Collectors.toList());
    }

    @Override
    public List<GraphSection> listChildren(Long documentId, Long sectionNodeId) {
        if (sectionNodeId == null) {
            return List.of();
        }
        return loadNodes(documentId).stream()
                .filter(n -> isSection(n) && sectionNodeId.equals(n.getParentNodeId()))
                .sorted(Comparator.comparing(DocumentStructureNode::getNodeNo,
                        Comparator.nullsLast(Comparator.naturalOrder())))
                .map(this::toSection)
                .collect(Collectors.toList());
    }

    @Override
    public GraphSection parentSection(Long documentId, Long sectionNodeId) {
        DocumentStructureNode node = findRawNode(documentId, sectionNodeId);
        if (node == null || node.getParentNodeId() == null) {
            return null;
        }
        return findSectionById(documentId, node.getParentNodeId());
    }

    @Override
    public GraphSection previousSibling(Long documentId, Long sectionNodeId) {
        DocumentStructureNode node = findRawNode(documentId, sectionNodeId);
        if (node == null || node.getPrevSiblingNodeId() == null) {
            return null;
        }
        return findSectionById(documentId, node.getPrevSiblingNodeId());
    }

    @Override
    public GraphSection nextSibling(Long documentId, Long sectionNodeId) {
        DocumentStructureNode node = findRawNode(documentId, sectionNodeId);
        if (node == null || node.getNextSiblingNodeId() == null) {
            return null;
        }
        return findSectionById(documentId, node.getNextSiblingNodeId());
    }

    // ============ 条目查询 ============

    @Override
    public GraphItem findItemByIndex(Long documentId, Long sectionNodeId, Integer itemIndex) {
        if (sectionNodeId == null || itemIndex == null) {
            return null;
        }
        for (DocumentStructureNode node : loadNodes(documentId)) {
            if (isItem(node)
                    && sectionNodeId.equals(resolveItemSectionId(documentId, node))
                    && itemIndex.equals(node.getItemIndex())) {
                return toItem(documentId, node);
            }
        }
        return null;
    }

    @Override
    public List<GraphItem> listItems(Long documentId, Long sectionNodeId) {
        if (sectionNodeId == null) {
            return List.of();
        }
        return loadNodes(documentId).stream()
                .filter(n -> isItem(n) && sectionNodeId.equals(resolveItemSectionId(documentId, n)))
                .sorted(Comparator.comparing(DocumentStructureNode::getItemIndex,
                        Comparator.nullsLast(Comparator.naturalOrder())))
                .map(n -> toItem(documentId, n))
                .collect(Collectors.toList());
    }

    @Override
    public List<GraphItem> searchItemsInSection(Long documentId, Long sectionNodeId, String keyword) {
        if (keyword == null || keyword.isBlank()) {
            return List.of();
        }
        String needle = keyword.trim().toLowerCase();
        List<GraphItem> result = new ArrayList<>();
        for (GraphItem item : listItems(documentId, sectionNodeId)) {
            if (containsLower(item.getContentText(), needle)
                    || containsLower(item.getAnchorText(), needle)
                    || containsLower(item.getTitle(), needle)) {
                result.add(item);
            }
        }
        // 递归子章节
        for (GraphSection child : listChildren(documentId, sectionNodeId)) {
            result.addAll(searchItemsInSection(documentId, child.getNodeId(), keyword));
        }
        return result;
    }

    // ============ 内部工具 ============

    private DocumentStructureNode findRawNode(Long documentId, Long nodeId) {
        if (nodeId == null) {
            return null;
        }
        for (DocumentStructureNode node : loadNodes(documentId)) {
            if (nodeId.equals(node.getId())) {
                return node;
            }
        }
        return null;
    }

    /** item 的所属章节：STEP/LIST_ITEM 的 parentNode 若也是 item 则继续上溯 */
    private Long resolveItemSectionId(Long documentId, DocumentStructureNode item) {
        Long parent = item.getParentNodeId();
        while (parent != null) {
            DocumentStructureNode pNode = findRawNode(documentId, parent);
            if (pNode == null) {
                return null;
            }
            if (isSection(pNode)) {
                return pNode.getId();
            }
            parent = pNode.getParentNodeId();
        }
        return null;
    }

    private boolean isSection(DocumentStructureNode node) {
        Integer t = node.getNodeType();
        return DocumentStructureNodeTypeEnum.ROOT.getCode().equals(t)
                || DocumentStructureNodeTypeEnum.CHAPTER.getCode().equals(t);
    }

    private boolean isItem(DocumentStructureNode node) {
        Integer t = node.getNodeType();
        return DocumentStructureNodeTypeEnum.STEP.getCode().equals(t)
                || DocumentStructureNodeTypeEnum.LIST_ITEM.getCode().equals(t);
    }

    private GraphSection toSection(DocumentStructureNode n) {
        return GraphSection.builder()
                .nodeId(n.getId())
                .documentId(n.getDocumentId())
                .parseTaskId(n.getParseTaskId())
                .nodeNo(n.getNodeNo())
                .depth(n.getDepth())
                .parentNodeId(n.getParentNodeId())
                .prevSiblingNodeId(n.getPrevSiblingNodeId())
                .nextSiblingNodeId(n.getNextSiblingNodeId())
                .nodeCode(n.getNodeCode())
                .title(n.getTitle())
                .anchorText(n.getAnchorText())
                .sectionPath(n.getSectionPath())
                .canonicalPath(n.getCanonicalPath())
                .contentText(n.getContentText())
                .build();
    }

    private GraphItem toItem(Long documentId, DocumentStructureNode n) {
        return GraphItem.builder()
                .nodeId(n.getId())
                .documentId(documentId)
                .parseTaskId(n.getParseTaskId())
                .nodeNo(n.getNodeNo())
                .nodeType(n.getNodeType())
                .sectionNodeId(resolveItemSectionId(documentId, n))
                .prevSiblingNodeId(n.getPrevSiblingNodeId())
                .nextSiblingNodeId(n.getNextSiblingNodeId())
                .title(n.getTitle())
                .anchorText(n.getAnchorText())
                .sectionPath(n.getSectionPath())
                .canonicalPath(n.getCanonicalPath())
                .contentText(n.getContentText())
                .itemIndex(n.getItemIndex())
                .build();
    }

    private double scoreSection(DocumentStructureNode node, String topic, String facet) {
        double score = 0;
        if (topic != null) {
            if (containsLower(node.getSectionPath(), topic)) {
                score += 100;
            }
            if (containsLower(node.getTitle(), topic)) {
                score += 90;
            }
            if (containsLower(node.getAnchorText(), topic)) {
                score += 80;
            }
            if (containsLower(node.getContentText(), topic)) {
                score += 45;
            }
        }
        if (facet != null && !facet.isBlank()) {
            if (containsLower(node.getTitle(), facet)) {
                score += 5;
            } else if (containsLower(node.getContentText(), facet)) {
                score += 1;
            }
        }
        return score;
    }

    private static boolean containsIgnoreCase(String hay, String needle) {
        return hay != null && needle != null && hay.toLowerCase().contains(needle.toLowerCase());
    }

    private static boolean containsLower(String hay, String lowerNeedle) {
        return hay != null && lowerNeedle != null && hay.toLowerCase().contains(lowerNeedle);
    }

    private static String lower(String s) {
        return s == null ? null : s.toLowerCase();
    }

    private static String trimTo(String s) {
        return s == null ? null : s.trim();
    }

    /** MySQL 回退不做图投影，本方法保留供 Composite 显式调用以清缓存 */
    @SuppressWarnings("unused")
    private void guardDocumentExists(Long documentId) {
        if (documentId == null) {
            throw new DocumentException(DocumentManageCode.GRAPH_NOT_AVAILABLE, "documentId 为空");
        }
    }

    /** 占位：供 Composite 在投影完成后批量预热缓存（可选） */
    public void warmCache(Long documentId) {
        evict(documentId);
        loadNodes(documentId);
    }

    /** 暴露 nodeMap 供需要 id→node 查找的场景 */
    public Map<Long, DocumentStructureNode> nodeMap(Long documentId) {
        Map<Long, DocumentStructureNode> map = new java.util.LinkedHashMap<>();
        for (DocumentStructureNode node : loadNodes(documentId)) {
            map.put(node.getId(), node);
        }
        return map;
    }
}
