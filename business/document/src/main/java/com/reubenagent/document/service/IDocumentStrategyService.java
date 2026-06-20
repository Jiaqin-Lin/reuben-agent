package com.reubenagent.document.service;

import com.reubenagent.document.entity.Document;
import com.reubenagent.document.model.DocumentParseResult;
import com.reubenagent.document.model.DocumentStrategyPlanDraft;

/**
 * 文档策略服务 —— 基于解析结果推荐最优分块方案。
 *
 * @author reuben
 * @since 2026-06-20
 */
public interface IDocumentStrategyService {

    /**
     * 根据文档特征与分析结果推荐分块策略。
     *
     * @param document    文档实体
     * @param parseResult 解析结果（含统计指标）
     * @return 策略方案草稿
     */
    DocumentStrategyPlanDraft recommendStrategy(Document document, DocumentParseResult parseResult);
}
