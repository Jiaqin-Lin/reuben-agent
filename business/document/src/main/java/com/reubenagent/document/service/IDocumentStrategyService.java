package com.reubenagent.document.service;

import com.reubenagent.document.entity.Document;
import com.reubenagent.document.entity.DocumentStrategyPlan;
import com.reubenagent.document.entity.DocumentStrategyStep;
import com.reubenagent.document.model.DocumentParseResult;
import com.reubenagent.document.model.DocumentStrategyPlanDraft;
import com.reubenagent.document.model.ParentBlockCandidate;

import java.util.List;

/**
 * 文档策略服务 —— 基于解析结果推荐最优分块方案并执行策略。
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

    /**
     * 执行分块策略，将解析文本按方案步骤切分为父子块候选列表。
     *
     * @param document   文档实体
     * @param plan       已确认的策略方案
     * @param steps      方案下的步骤列表
     * @param parsedText 解析后的纯文本
     * @return 父子块候选列表
     */
    List<ParentBlockCandidate> buildParentBlocks(
            Document document,
            DocumentStrategyPlan plan,
            List<DocumentStrategyStep> steps,
            String parsedText);
}
