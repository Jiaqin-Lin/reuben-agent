package com.reubenagent.document.service;

public interface IDocumentAsyncProcessService {

    void handleParseStrategyRoute(Long documentId, Long taskId);

    /**
     * 执行索引构建 —— 从策略确认到向量入库的完整编排。
     *
     * @param documentId 文档 ID
     * @param taskId     索引任务 ID
     * @param planId     策略方案 ID
     */
    void handleIndexBuild(Long documentId, Long taskId, Long planId);

}
