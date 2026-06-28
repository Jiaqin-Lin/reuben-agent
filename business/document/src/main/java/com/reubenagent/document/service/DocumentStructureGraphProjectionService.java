package com.reubenagent.document.service;

/**
 * Neo4j 图投影服务接口 —— 将 MySQL 结构节点投影到 Neo4j 图库。
 *
 * @author reuben
 * @since 2026-06-28
 */
public interface DocumentStructureGraphProjectionService {

    /** 投影单个文档的结构节点到 Neo4j（先清后建） */
    void projectToGraph(Long documentId, Long parseTaskId);

    /** 删除文档在 Neo4j 中的全部图数据 */
    void deleteByDocumentId(Long documentId);

    /** 标记图可用性缓存（投影成功后调用） */
    void markAvailable(Long documentId, boolean available);
}
