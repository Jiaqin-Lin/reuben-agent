package com.reubenagent.document.model.route;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * 路由查询上下文 —— 携带原始问题、改写结果、分词列表和 query embedding。
 *
 * @author reuben
 * @since 2026-06-28
 */
@Data
@Builder
public class RouteQueryContext {

    /** 原始用户问题 */
    private String originalQuestion;

    /** 改写后的问题（可能为 null） */
    private String rewriteQuestion;

    /** 路由文本（originalQuestion + " " + rewriteQuestion，用于语义 embedding） */
    private String routingText;

    /** 查询分词列表 */
    private List<String> queryTerms;

    /** 查询向量（由 EmbeddingModel 生成） */
    private float[] queryEmbedding;
}
