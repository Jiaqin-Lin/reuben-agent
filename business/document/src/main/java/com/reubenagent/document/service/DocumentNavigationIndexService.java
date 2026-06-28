package com.reubenagent.document.service;

import com.reubenagent.document.entity.DocumentStructureNode;
import com.reubenagent.document.model.es.NavigationSectionHit;

import java.util.List;

/**
 * 文档导航索引服务接口 —— 管理 {@code reuben_agent_document_navigation} ES 索引。
 *
 * <p>在文档解析管线中构建，在 chat 管线中被 {@code DocumentQuestionRouter} 消费。</p>
 *
 * @author reuben
 * @since 2026-06-28
 */
public interface DocumentNavigationIndexService {

    /** 重建指定文档的导航索引：删旧 → bulk 写新节点 */
    void reindexDocumentNodes(Long documentId, Long parseTaskId, List<DocumentStructureNode> nodes);

    /** 按文档删除导航索引 */
    void deleteByDocumentId(Long documentId);

    /**
     * 四维章节搜索：topic / facet / informationNeed / question 任意非空即参与查询。
     *
     * @param documentId       文档 ID
     * @param topic            主题
     * @param facet            侧面
     * @param informationNeed  信息需求
     * @param question         原始问题
     * @param size             返回上限
     * @return 命中章节列表（按分数降序）
     */
    List<NavigationSectionHit> searchSections(Long documentId, String topic, String facet,
                                              String informationNeed, String question, int size);
}
