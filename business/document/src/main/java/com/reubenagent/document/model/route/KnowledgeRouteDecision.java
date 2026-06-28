package com.reubenagent.document.model.route;

import com.reubenagent.document.enums.KnowledgeRouteStatus;
import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * 知识路由决策结果 —— 三层候选列表 + 置信度 + 路由状态。
 *
 * @author reuben
 * @since 2026-06-28
 */
@Data
@Builder
public class KnowledgeRouteDecision {

    /** scope 候选列表（按分数降序） */
    private List<ScopeRouteCandidate> scopes;

    /** topic 候选列表（按分数降序） */
    private List<TopicRouteCandidate> topics;

    /** document 候选列表（按分数降序） */
    private List<DocumentRouteCandidate> documents;

    /** 路由置信度（0~1） */
    private BigDecimal confidence;

    /** 路由状态 */
    private KnowledgeRouteStatus routeStatus;

    /** 路由原因简述 */
    private String reason;

    /** 返回最高分文档候选，无候选时返回 null。 */
    public DocumentRouteCandidate topDocument() {
        if (documents == null || documents.isEmpty()) return null;
        return Collections.max(documents, Comparator.comparing(DocumentRouteCandidate::getScore));
    }

    /** 返回最高分 topic 候选，无候选时返回 null。 */
    public TopicRouteCandidate topTopic() {
        if (topics == null || topics.isEmpty()) return null;
        return Collections.max(topics, Comparator.comparing(TopicRouteCandidate::getScore));
    }

    /** 返回最高分 scope 候选，无候选时返回 null。 */
    public ScopeRouteCandidate topScope() {
        if (scopes == null || scopes.isEmpty()) return null;
        return Collections.max(scopes, Comparator.comparing(ScopeRouteCandidate::getScore));
    }
}
