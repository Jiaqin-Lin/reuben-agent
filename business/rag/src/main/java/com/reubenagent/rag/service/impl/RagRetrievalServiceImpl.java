package com.reubenagent.rag.service.impl;

import com.reubenagent.common.exception.BusinessException;
import com.reubenagent.common.exception.ValidationException;
import com.reubenagent.rag.config.RagProperties;
import com.reubenagent.rag.dto.RagRetrieveRequest;
import com.reubenagent.rag.enums.RagErrorCode;
import com.reubenagent.rag.model.RetrievalResult;
import com.reubenagent.rag.service.IRagRetrievalService;
import com.reubenagent.rag.service.KeywordRetrievalChannel;
import com.reubenagent.rag.service.ParentBlockElevationService;
import com.reubenagent.rag.service.QueryRewriteService;
import com.reubenagent.rag.service.RerankService;
import com.reubenagent.rag.service.RrfFusionService;
import com.reubenagent.rag.service.VectorRetrievalChannel;
import com.reubenagent.rag.vo.RagRetrieveResponse;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
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
 *   <li>证据门控、查询改写、父块提升各司其职，Pipeline 清晰可测</li>
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
    private final QueryRewriteService queryRewriteService;
    private final ParentBlockElevationService elevationService;
    private final RerankService rerankService;
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
            // 阶段 2：Query Rewrite — LLM 改写查询，提升召回命中率
            String originalQuery = request.getQuery();
            String searchQuery = queryRewriteService.rewrite(originalQuery);
            String rewrittenQuery = searchQuery.equals(originalQuery) ? null : searchQuery;
            if (rewrittenQuery != null) {
                log.info("查询改写: '{}' → '{}'", originalQuery, rewrittenQuery);
            }

            // 阶段 3：并行调用两个通道
            String finalSearchQuery = searchQuery;
            CompletableFuture<List<RetrievalResult>> vectorFuture =
                    CompletableFuture.supplyAsync(() ->
                            vectorChannel.retrieve(finalSearchQuery, vectorTopK, filters))
                            .orTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                            .exceptionally(ex -> {
                                log.warn("向量通道异常/超时，降级返回空列表: {}", ex.getMessage());
                                return List.of();
                            });

            CompletableFuture<List<RetrievalResult>> keywordFuture =
                    CompletableFuture.supplyAsync(() ->
                            keywordChannel.retrieve(finalSearchQuery, keywordTopK, filters))
                            .orTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                            .exceptionally(ex -> {
                                log.warn("关键词通道异常/超时，降级返回空列表: {}", ex.getMessage());
                                return List.of();
                            });

            // 阶段 4：等待两个通道都完成（或超时降级）
            CompletableFuture.allOf(vectorFuture, keywordFuture).join();

            List<RetrievalResult> vectorResults = vectorFuture.get();
            List<RetrievalResult> keywordResults = keywordFuture.get();

            log.debug("双通道检索完成: vector={}, keyword={}", vectorResults.size(), keywordResults.size());

            // 阶段 5：Evidence Gates — 过滤弱相关噪声
            List<RetrievalResult> gatedVector = applyEvidenceGates(
                    vectorResults, retrievalConfig.getMinVectorSimilarity(),
                    retrievalConfig.getKeywordRelativeScoreFloor());

            List<RetrievalResult> gatedKeyword = applyEvidenceGates(
                    keywordResults, retrievalConfig.getMinVectorSimilarity(),
                    retrievalConfig.getKeywordRelativeScoreFloor());

            // 阶段 6：RRF 融合（暂不截断，留给 elevation 后）
            int fusionLimit = gatedVector.size() + gatedKeyword.size();
            List<RetrievalResult> fused = rrfFusionService.fuse(
                    gatedVector, gatedKeyword, rrfK, Math.max(fusionLimit, finalTopK));

            // 阶段 7：Parent Block Elevation — 小 chunk 替换为父块完整文本
            List<RetrievalResult> elevated = elevationService.elevate(fused);

            // 阶段 8：Rerank — cross-encoder 精排
            List<RetrievalResult> reranked = rerankService.rerank(originalQuery, elevated);

            // 阶段 9：截断到 finalTopK
            List<RetrievalResult> results = reranked.size() > finalTopK
                    ? new ArrayList<>(reranked.subList(0, finalTopK))
                    : reranked;

            long totalCostMs = System.currentTimeMillis() - start;

            log.info("RAG 检索完成: query='{}', rewritten={}, finalTopK={}, fused={}, elevated={}, reranked={}, final={}, costMs={}",
                    originalQuery, rewrittenQuery != null, finalTopK,
                    fused.size(), elevated.size(), reranked.size(), results.size(), totalCostMs);

            return RagRetrieveResponse.builder()
                    .results(results)
                    .totalCostMs(totalCostMs)
                    .rewrittenQuery(rewrittenQuery)
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

    /**
     * 应用证据门控，过滤弱相关噪声。
     *
     * <p>根据 source 字段区分通道类型：
     * <ul>
     *   <li>向量通道（source="vector"）：采用绝对余弦相似度阈值 {@code minSimilarity}</li>
     *   <li>关键词通道（source="keyword"）：采用相对分数阈值，结果分数低于通道最高分 ×
     *       {@code relativeFloor} 的被丢弃</li>
     * </ul>
     * 混入的 "hybrid" source（不应在此阶段出现）按向量阈值处理。
     *
     * @param results       原始检索结果列表
     * @param minSimilarity 向量通道最低余弦相似度
     * @param relativeFloor 关键词通道相对分数下限（0~1）
     * @return 过滤后的结果列表
     */
    private List<RetrievalResult> applyEvidenceGates(
            List<RetrievalResult> results, double minSimilarity, double relativeFloor) {

        if (results == null || results.isEmpty()) {
            return List.of();
        }

        // 找出关键词通道的最高分（用于相对阈值计算）
        double keywordMaxScore = results.stream()
                .filter(r -> "keyword".equals(r.getSource()))
                .mapToDouble(r -> r.getScore() != null ? r.getScore() : 0.0)
                .max()
                .orElse(0.0);

        // 如果关键词最高分为 0，跳过相对阈值过滤（避免全量误杀）
        boolean skipKeywordGate = keywordMaxScore <= 0.0;

        List<RetrievalResult> filtered = new ArrayList<>();
        int vectorBefore = 0, vectorAfter = 0;
        int keywordBefore = 0, keywordAfter = 0;

        for (RetrievalResult r : results) {
            Double score = r.getScore();
            if (score == null) {
                continue; // 无分数直接丢弃
            }

            String source = r.getSource();
            if ("keyword".equals(source)) {
                keywordBefore++;
                if (skipKeywordGate || score >= keywordMaxScore * relativeFloor) {
                    filtered.add(r);
                    keywordAfter++;
                }
            } else {
                // vector / hybrid — 用绝对阈值
                vectorBefore++;
                if (score >= minSimilarity) {
                    filtered.add(r);
                    vectorAfter++;
                }
            }
        }

        log.debug("Evidence gates: vector {}/{} passed, keyword {}/{} passed (minSim={}, relFloor={}, kwMaxScore={})",
                vectorAfter, vectorBefore, keywordAfter, keywordBefore,
                minSimilarity, relativeFloor, keywordMaxScore);

        return filtered;
    }
}
