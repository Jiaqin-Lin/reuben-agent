package com.reubenagent.document.service;

import com.reubenagent.document.model.graph.GraphItem;
import com.reubenagent.document.model.graph.GraphSection;

import java.util.List;

/**
 * 文档结构图服务接口 —— 提供章节/条目的图遍历查询能力。
 *
 * <p>实现包括 Neo4j（{@code Neo4jDocumentStructureGraphService}）与 MySQL 回退
 * （{@code MysqlDocumentStructureGraphService}），由 {@code CompositeDocumentStructureGraphService}
 * 组合路由。</p>
 *
 * @author reuben
 * @since 2026-06-28
 */
public interface DocumentStructureGraphService {

    /** 图数据是否可用（已投影到图库） */
    boolean isGraphAvailable(Long documentId);

    GraphSection findSectionById(Long documentId, Long sectionNodeId);

    GraphSection findSectionByCode(Long documentId, String nodeCode);

    GraphSection findSectionByTitle(Long documentId, String title);

    GraphSection findSectionByCanonicalPath(Long documentId, String canonicalPath);

    /** 按主题/侧面在文档内打分定位最佳章节 */
    GraphSection findBestSection(Long documentId, String topic, String facet);

    List<GraphSection> listSections(Long documentId);

    List<GraphSection> listChildren(Long documentId, Long sectionNodeId);

    GraphSection parentSection(Long documentId, Long sectionNodeId);

    GraphSection previousSibling(Long documentId, Long sectionNodeId);

    GraphSection nextSibling(Long documentId, Long sectionNodeId);

    GraphItem findItemByIndex(Long documentId, Long sectionNodeId, Integer itemIndex);

    List<GraphItem> listItems(Long documentId, Long sectionNodeId);

    List<GraphItem> searchItemsInSection(Long documentId, Long sectionNodeId, String keyword);
}
