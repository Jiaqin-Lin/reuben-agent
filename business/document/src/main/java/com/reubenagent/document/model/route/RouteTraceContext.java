package com.reubenagent.document.model.route;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/**
 * 路由追踪上下文 —— 封装一次路由决策的完整信息，用于异步写入 trace 表。
 *
 * @author reuben
 * @since 2026-06-28
 */
@Data
@Builder
public class RouteTraceContext {

    /** 会话 ID */
    private String conversationId;

    /** 对话轮次 */
    private Long turnId;

    /** 原始问题 */
    private String question;

    /** 改写问题 */
    private String rewriteQuestion;

    /** 路由模式：shadow / auto */
    private String mode;

    /** scope 候选列表 */
    private List<ScopeRouteCandidate> scopeCandidates;

    /** topic 候选列表 */
    private List<TopicRouteCandidate> topicCandidates;

    /** document 候选列表 */
    private List<DocumentRouteCandidate> documentCandidates;

    /** 用户实际选中的文档 ID（SHADOW 模式） */
    private Long selectedDocumentId;

    /** 路由置信度 */
    private BigDecimal confidence;

    /** 路由状态码 */
    private Integer routeStatus;

    /** 失败信息 */
    private String errorMsg;
}
