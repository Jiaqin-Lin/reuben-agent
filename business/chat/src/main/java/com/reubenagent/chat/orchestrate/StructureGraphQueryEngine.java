package com.reubenagent.chat.orchestrate;

import com.reubenagent.chat.config.ChatProperties;
import com.reubenagent.document.model.graph.GraphItem;
import com.reubenagent.document.model.graph.GraphItemWithContext;
import com.reubenagent.document.model.graph.GraphQueryResult;
import com.reubenagent.document.model.graph.GraphSection;
import com.reubenagent.document.model.graph.GraphSectionWithChildren;
import com.reubenagent.document.model.graph.GraphSectionWithSiblings;
import com.reubenagent.document.service.DocumentStructureGraphService;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 结构图查询引擎 —— 封装 {@link DocumentStructureGraphService} 的高层图遍历组合查询，
 * 供 {@link GraphOnlyExecutor} / {@link GraphThenEvidenceExecutor} 复用。
 *
 * @author reuben
 * @since 2026-06-28
 */
@Slf4j
@Service
@AllArgsConstructor
public class StructureGraphQueryEngine {

    private final DocumentStructureGraphService graphService;
    private final ChatProperties properties;

    /** 按主题定位最佳章节并返回其子章节 */
    public GraphSectionWithChildren findSectionWithChildren(Long documentId, String topic) {
        GraphSection target = graphService.findBestSection(documentId, topic, null);
        if (target == null) {
            return null;
        }
        return findSectionWithChildren(documentId, target.getNodeId());
    }

    public GraphSectionWithChildren findSectionWithChildren(Long documentId, Long sectionNodeId) {
        GraphSection target = graphService.findSectionById(documentId, sectionNodeId);
        if (target == null) {
            return null;
        }
        List<GraphSection> children = graphService.listChildren(documentId, sectionNodeId);
        return GraphSectionWithChildren.builder()
                .section(target)
                .children(children == null ? Collections.emptyList() : children)
                .build();
    }

    /** 章节邻接查询：目标 + 父 + 前后兄弟 */
    public GraphSectionWithSiblings findSectionWithSiblings(Long documentId, Long sectionNodeId) {
        GraphSection target = graphService.findSectionById(documentId, sectionNodeId);
        if (target == null) {
            return null;
        }
        return GraphSectionWithSiblings.builder()
                .section(target)
                .parent(graphService.parentSection(documentId, sectionNodeId))
                .previousSibling(graphService.previousSibling(documentId, sectionNodeId))
                .nextSibling(graphService.nextSibling(documentId, sectionNodeId))
                .build();
    }

    /** 按章节主题定位条目 */
    public GraphItemWithContext findItemInSection(Long documentId, String sectionTopic, Integer itemIndex) {
        GraphSection target = graphService.findBestSection(documentId, sectionTopic, null);
        if (target == null) {
            return null;
        }
        return findItemInSection(documentId, target.getNodeId(), itemIndex);
    }

    public GraphItemWithContext findItemInSection(Long documentId, Long sectionNodeId, Integer itemIndex) {
        GraphSection section = graphService.findSectionById(documentId, sectionNodeId);
        if (section == null) {
            return null;
        }
        List<GraphItem> items = graphService.listItems(documentId, sectionNodeId);
        GraphItem target = null;
        if (itemIndex != null) {
            target = graphService.findItemByIndex(documentId, sectionNodeId, itemIndex);
        }
        return GraphItemWithContext.builder()
                .section(section)
                .item(target)
                .siblingItems(items == null ? Collections.emptyList() : items)
                .build();
    }

    /** 递归遍历章节树搜索条目 */
    public List<GraphItem> searchItemsInSectionTree(Long documentId, Long sectionNodeId, String keyword) {
        if (sectionNodeId == null || keyword == null || keyword.isBlank()) {
            return Collections.emptyList();
        }
        return graphService.searchItemsInSection(documentId, sectionNodeId, keyword);
    }

    /** 聚合查询：章节 + 子节点 + items + 目标 item + 匹配 items + 父/兄弟 */
    public GraphQueryResult buildGraphResult(Long documentId, Long targetSectionNodeId,
                                             Integer targetItemIndex, String itemKeyword) {
        GraphSection target = graphService.findSectionById(documentId, targetSectionNodeId);
        if (target == null) {
            return null;
        }
        List<GraphSection> children = graphService.listChildren(documentId, targetSectionNodeId);
        List<GraphItem> allItems = graphService.listItems(documentId, targetSectionNodeId);
        GraphItem targetItem = targetItemIndex != null
                ? graphService.findItemByIndex(documentId, targetSectionNodeId, targetItemIndex)
                : null;
        List<GraphItem> matchedItems = itemKeyword != null && !itemKeyword.isBlank()
                ? graphService.searchItemsInSection(documentId, targetSectionNodeId, itemKeyword)
                : Collections.emptyList();
        return GraphQueryResult.builder()
                .targetSection(target)
                .parentSection(graphService.parentSection(documentId, targetSectionNodeId))
                .previousSibling(graphService.previousSibling(documentId, targetSectionNodeId))
                .nextSibling(graphService.nextSibling(documentId, targetSectionNodeId))
                .targetItem(targetItem)
                .children(children == null ? Collections.emptyList() : children)
                .matchedItems(matchedItems == null ? Collections.emptyList() : matchedItems)
                .allItems(allItems == null ? Collections.emptyList() : allItems)
                .build();
    }

    /** 安全列出文档全部章节，失败返回空列表 */
    public List<GraphSection> safeListSections(Long documentId) {
        try {
            List<GraphSection> sections = graphService.listSections(documentId);
            return sections == null ? Collections.emptyList() : sections;
        } catch (Exception e) {
            log.warn("列出文档章节失败 → documentId={} err={}", documentId, e.getMessage());
            return new ArrayList<>();
        }
    }

    public ChatProperties.Navigation navigationConfig() {
        return properties.getNavigation();
    }

    /** 暴露底层图服务，供 executor 做章节定位等直查 */
    public DocumentStructureGraphService graphService() {
        return graphService;
    }
}
