package com.reubenagent.rag.service;

import com.reubenagent.rag.dto.RagRetrieveRequest;
import com.reubenagent.rag.vo.RagRetrieveResponse;

/**
 * RAG 混合检索引擎 —— 编排向量 + 关键词双通道并行检索与 RRF 融合。
 *
 * @author reuben
 * @since 2026-06-21
 */
public interface IRagRetrievalService {

    /**
     * 执行混合检索召回。
     *
     * @param request 检索请求（query 必填）
     * @return 检索响应，含融合后结果与总耗时
     */
    RagRetrieveResponse retrieve(RagRetrieveRequest request);
}
