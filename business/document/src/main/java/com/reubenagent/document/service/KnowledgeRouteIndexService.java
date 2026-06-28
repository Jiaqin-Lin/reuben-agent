package com.reubenagent.document.service;

import com.reubenagent.document.model.es.RouteLexicalHit;

import java.util.List;

/**
 * 知识路由 ES 索引服务 —— 管理 {@code reuben_agent_knowledge_route} 索引。
 *
 * <p>为路由引擎提供词法匹配通道，支持按实体类型搜索和全量刷新。</p>
 *
 * @author reuben
 * @since 2026-06-28
 */
public interface KnowledgeRouteIndexService {

    /**
     * 全量重建索引：清空 → 重新加载 scope / topic / document 三类实体 → bulk 写入。
     */
    void refreshAll();

    /**
     * 按实体类型进行词法搜索。
     *
     * @param routingText 路由文本（用户问题 + rewrite）
     * @param entityType  实体类型过滤（scope / topic / document），null 表示不过滤
     * @param size        返回数量上限
     * @return 命中列表（按 ES 分数降序）
     */
    List<RouteLexicalHit> search(String routingText, String entityType, int size);

    /**
     * 按文档 ID 删除路由记录。
     *
     * @param documentId 文档 ID
     */
    void deleteDocumentRoute(Long documentId);
}
