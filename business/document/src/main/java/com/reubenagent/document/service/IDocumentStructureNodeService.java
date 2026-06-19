package com.reubenagent.document.service;

import com.reubenagent.document.entity.DocumentStructureNode;
import com.reubenagent.document.model.DocumentIntermediateStructureNode;

import java.util.List;
import java.util.Map;

/**
 * 文档结构节点服务接口 —— 管理解析管线产出的树形结构节点。
 *
 * @author reuben
 * @since 2026-06-19
 */
public interface IDocumentStructureNodeService {

    /**
     * 持久化文档结构节点（先清后写）。
     *
     * @param documentId 文档 ID
     * @param parseTaskId 解析任务 ID
     * @param nodes      管线产出的中间节点列表
     * @return 持久化后的实体列表
     */
    List<DocumentStructureNode> saveNodes(Long documentId,
                                          Long parseTaskId,
                                          List<DocumentIntermediateStructureNode> nodes);

    /**
     * 按文档 ID 查询节点列表，按 nodeNo 升序。
     *
     * @param documentId  文档 ID
     * @param parseTaskId 解析任务 ID（可选，为 null 时不过滤）
     * @return 节点列表（无结果时返回空列表）
     */
    List<DocumentStructureNode> listDocumentNodes(Long documentId, Long parseTaskId);

    /**
     * 构建 ID → 节点映射表。
     *
     * @param documentId  文档 ID
     * @param parseTaskId 解析任务 ID（可选）
     * @return 有序映射表（LinkedHashMap 保持 nodeNo 顺序）
     */
    Map<Long, DocumentStructureNode> nodeMap(Long documentId, Long parseTaskId);

    /**
     * 删除指定文档的所有结构节点。
     *
     * @param documentId 文档 ID，为 null 时直接返回
     */
    void deleteByDocumentId(Long documentId);
}
