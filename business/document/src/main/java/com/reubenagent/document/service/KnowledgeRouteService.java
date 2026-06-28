package com.reubenagent.document.service;

import com.reubenagent.document.model.route.KnowledgeRouteDecision;

/**
 * 知识路由引擎 —— 三级路由（scope → topic → document）。
 *
 * <p>综合语义向量 + ES 词法 + 实体词命中三道评分，选出最匹配的知识范围和文档。</p>
 *
 * @author reuben
 * @since 2026-06-28
 */
public interface KnowledgeRouteService {

    /**
     * 执行全量三级路由。
     *
     * @param question        原始用户问题
     * @param rewriteQuestion 改写后的问题（可选）
     * @return 路由决策结果
     */
    KnowledgeRouteDecision route(String question, String rewriteQuestion);

    /**
     * 记录影子评估路由（SHADOW 模式，仅评估不干预）。
     *
     * @param conversationId    会话 ID
     * @param turnId            对话轮次
     * @param selectedDocumentId 用户选中的文档 ID
     * @param question          原始问题
     * @param rewriteQuestion   改写问题
     */
    void recordShadowRoute(String conversationId, Long turnId, Long selectedDocumentId,
                           String question, String rewriteQuestion);

    /**
     * 记录自动路由结果（AUTO 模式，路由引擎自动选择文档）。
     *
     * @param conversationId  会话 ID
     * @param turnId          对话轮次
     * @param question        原始问题
     * @param rewriteQuestion 改写问题
     * @param decision        路由决策结果
     */
    void recordAutoRoute(String conversationId, Long turnId, String question,
                         String rewriteQuestion, KnowledgeRouteDecision decision);
}
