package com.reubenagent.rag.service.impl;

import com.reubenagent.common.exception.BusinessException;
import com.reubenagent.common.exception.ValidationException;
import com.reubenagent.rag.config.RagProperties;
import com.reubenagent.rag.dto.RagRetrieveRequest;
import com.reubenagent.rag.enums.RagErrorCode;
import com.reubenagent.rag.model.RetrievalResult;
import com.reubenagent.rag.service.IRagRetrievalService;
import com.reubenagent.rag.service.KeywordRetrievalChannel;
import com.reubenagent.rag.service.RrfFusionService;
import com.reubenagent.rag.service.VectorRetrievalChannel;
import com.reubenagent.rag.vo.RagRetrieveResponse;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * RAG 混合检索引擎实现 —— 编排向量 + 关键词双通道并行检索与 RRF 融合。
 *
 * <h3>与 super-agent 差异</h3>
 * <ul>
 *   <li>拆分为 {@link RrfFusionService}（纯融合）+ 本类（编排），职责清晰</li>
 *   <li>超时不吞异常 → {@code exceptionally} 降级，一个通道炸另一个照常返回</li>
 *   <li>用 {@link CompletableFuture} 默认 ForkJoinPool，v1 不引入自定义线程池</li>
 *   <li>不做 sub-question 拆分、evidence gate、parent block elevation、rerank（v1 后续迭代）</li>
 * </ul>
 *
 * @author reuben
 * @since 2026-06-21
 */
@Slf4j
@Service
@AllArgsConstructor
public class RagRetrievalServiceImpl implements IRagRetrievalService {

    private final VectorRetrievalChannel vectorChannel;
    private final KeywordRetrievalChannel keywordChannel;
    private final RrfFusionService rrfFusionService;
    private final RagProperties ragProperties;

    @Override
    public RagRetrieveResponse retrieve(RagRetrieveRequest request) {
        // 阶段 1：参数校验
        validateRequest(request);

        long start = System.currentTimeMillis();

        RagProperties.Retrieval retrievalConfig = ragProperties.getRetrieval();
        int vectorTopK = retrievalConfig.getVectorTopK();
        int keywordTopK = retrievalConfig.getKeywordTopK();
        int finalTopK = request.getTopK() != null ? request.getTopK() : retrievalConfig.getFinalTopK();
        int rrfK = retrievalConfig.getRrfK();
        long timeoutMs = retrievalConfig.getChannelTimeoutMs();
        Map<String, String> filters = request.getFilterFields();

        try {
            // 阶段 2：并行调用两个通道
            CompletableFuture<List<RetrievalResult>> vectorFuture =
                    CompletableFuture.supplyAsync(() ->
                            vectorChannel.retrieve(request.getQuery(), vectorTopK, filters))
                            .orTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                            .exceptionally(ex -> {
                                log.warn("向量通道异常/超时，降级返回空列表: {}", ex.getMessage());
                                return List.of();
                            });

            CompletableFuture<List<RetrievalResult>> keywordFuture =
                    CompletableFuture.supplyAsync(() ->
                            keywordChannel.retrieve(request.getQuery(), keywordTopK, filters))
                            .orTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                            .exceptionally(ex -> {
                                log.warn("关键词通道异常/超时，降级返回空列表: {}", ex.getMessage());
                                return List.of();
                            });

            // 阶段 3：等待两个通道都完成（或超时降级）
            CompletableFuture.allOf(vectorFuture, keywordFuture).join();

            List<RetrievalResult> vectorResults = vectorFuture.get();
            List<RetrievalResult> keywordResults = keywordFuture.get();

            log.debug("双通道检索完成: vector={}, keyword={}", vectorResults.size(), keywordResults.size());

            // 阶段 4：RRF 融合
            List<RetrievalResult> fused = rrfFusionService.fuse(
                    vectorResults, keywordResults, rrfK, finalTopK);

            long totalCostMs = System.currentTimeMillis() - start;

            log.info("RAG 检索完成: query={}, finalTopK={}, hits={}, costMs={}",
                    request.getQuery(), finalTopK, fused.size(), totalCostMs);

            return RagRetrieveResponse.builder()
                    .results(fused)
                    .totalCostMs(totalCostMs)
                    .build();

        } catch (Exception e) {
            log.error("RAG 检索失败: query={}", request.getQuery(), e);
            throw new BusinessException(
                    RagErrorCode.RETRIEVE_FAILED.getCode(),
                    RagErrorCode.RETRIEVE_FAILED.getMsg() + " —— " + e.getMessage(), e);
        }
    }

    /** 校验请求参数。 */
    private void validateRequest(RagRetrieveRequest request) {
        if (request == null || request.getQuery() == null || request.getQuery().isBlank()) {
            throw new ValidationException("query", "查询文本不能为空");
        }
    }
}
