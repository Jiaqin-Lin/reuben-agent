package com.reubenagent.rag.service.impl;

import com.reubenagent.common.exception.BusinessException;
import com.reubenagent.common.exception.ValidationException;
import com.reubenagent.rag.config.RagProperties;
import com.reubenagent.rag.dto.RagRetrieveRequest;
import com.reubenagent.rag.enums.RagErrorCode;
import com.reubenagent.rag.model.RetrievalResult;
import com.reubenagent.rag.model.RewriteResult;
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
import java.util.LinkedHashSet;
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
 *   <li>子问题拆解：改写阶段产出多子问题时，逐子问题检索 + chunkId 去重合流</li>
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
        validateRequest(request);

        long start = System.currentTimeMillis();

        RagProperties.Retrieval retrievalConfig = ragProperties.getRetrieval();
        int candidateTopK = retrievalConfig.getCandidateTopK();
        // 通道检索数至少为 candidateTopK，确保 Rerank 有足够候选可排
        int vectorTopK = Math.max(retrievalConfig.getVectorTopK(), candidateTopK);
        int keywordTopK = Math.max(retrievalConfig.getKeywordTopK(), candidateTopK);
        int finalTopK = request.getTopK() != null ? request.getTopK() : retrievalConfig.getFinalTopK();
        int rrfK = retrievalConfig.getRrfK();
        long timeoutMs = retrievalConfig.getChannelTimeoutMs();
        Map<String, String> filters = request.getFilterFields();

        try {
            // 阶段 2：Query Rewrite — LLM 改写查询，可能拆分为子问题
            String originalQuery = request.getQuery();
            RewriteResult rewriteResult = queryRewriteService.rewrite(originalQuery);
            String rewrittenQuery = rewriteResult.isUsedRewrite()
                    ? rewriteResult.getRewrittenQuery() : null;
            if (rewrittenQuery != null) {
                log.info("查询改写: '{}' → '{}' subs={} split={}",
                        originalQuery, rewrittenQuery,
                        rewriteResult.getSubQuestions().size(), rewriteResult.isShouldSplit());
            }

            // 阶段 3-9：执行检索（拆分为多子问题或单次检索）
            List<RetrievalResult> allResults;
            List<String> subQueries = resolveSubQueries(rewriteResult);
            if (subQueries.size() <= 1) {
                String searchQuery = rewriteResult.getRewrittenQuery();
                allResults = executeSingleQueryPipeline(
                        searchQuery, originalQuery, vectorTopK, keywordTopK,
                        finalTopK, candidateTopK, rrfK, timeoutMs, filters);
            } else {
                allResults = executeMultiQueryPipeline(
                        subQueries, vectorTopK, keywordTopK,
                        finalTopK, candidateTopK, rrfK, timeoutMs, filters);
            }

            long totalCostMs = System.currentTimeMillis() - start;

            log.info("RAG 检索完成: query='{}', rewritten={}, subQueries={}, final={}, costMs={}",
                    originalQuery, rewrittenQuery != null, subQueries.size(),
                    allResults.size(), totalCostMs);

            return RagRetrieveResponse.builder()
                    .results(allResults)
                    .totalCostMs(totalCostMs)
                    .rewrittenQuery(rewrittenQuery)
                    .subQueries(rewriteResult.isShouldSplit() && subQueries.size() > 1
                            ? subQueries : null)
                    .usedRewrite(rewriteResult.isUsedRewrite())
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

    /** 解析子查询列表（拆分时为多个，否则为单元素列表）。 */
    private List<String> resolveSubQueries(RewriteResult rewriteResult) {
        if (rewriteResult.hasSubQuestions() && rewriteResult.isShouldSplit()
                && rewriteResult.getSubQuestions().size() > 1) {
            return rewriteResult.getSubQuestions();
        }
        return List.of(rewriteResult.getRewrittenQuery());
    }

    // ======================== 单 Query 检索管线 ========================

    /**
     * 执行单次检索管线：双通道 → evidence gate → RRF 融合 → 父块提升 → Rerank → topK 截断。
     *
     * @param searchQuery   用于向量/关键词检索的改写查询
     * @param rerankQuery   用于 Rerank 的查询（通常为原始查询，保留用户意图）
     * @param candidateTopK RRF 融合后进入 Rerank 的候选数（与 super-agent 对齐）
     */
    private List<RetrievalResult> executeSingleQueryPipeline(
            String searchQuery, String rerankQuery, int vectorTopK, int keywordTopK,
            int finalTopK, int candidateTopK, int rrfK, long timeoutMs,
            Map<String, String> filters) {

        // 阶段 3：并行调用两个通道
        CompletableFuture<List<RetrievalResult>> vectorFuture =
                CompletableFuture.supplyAsync(() ->
                        vectorChannel.retrieve(searchQuery, vectorTopK, filters))
                        .orTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                        .exceptionally(ex -> {
                            log.warn("向量通道异常/超时，降级返回空列表: {}", ex.getMessage());
                            return List.of();
                        });

        CompletableFuture<List<RetrievalResult>> keywordFuture =
                CompletableFuture.supplyAsync(() ->
                        keywordChannel.retrieve(searchQuery, keywordTopK, filters))
                        .orTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                        .exceptionally(ex -> {
                            log.warn("关键词通道异常/超时，降级返回空列表: {}", ex.getMessage());
                            return List.of();
                        });

        // 阶段 4：等待两个通道都完成（或超时降级）
        CompletableFuture.allOf(vectorFuture, keywordFuture).join();

        List<RetrievalResult> vectorResults = getFutureResults(vectorFuture, "向量");
        List<RetrievalResult> keywordResults = getFutureResults(keywordFuture, "关键词");

        log.debug("双通道检索完成: vector={}, keyword={}", vectorResults.size(), keywordResults.size());

        // 阶段 5：Evidence Gates — 过滤弱相关噪声
        RagProperties.Retrieval retrievalConfig = ragProperties.getRetrieval();
        List<RetrievalResult> gatedVector = applyEvidenceGates(
                vectorResults, retrievalConfig.getMinVectorSimilarity(),
                retrievalConfig.getKeywordRelativeScoreFloor());

        List<RetrievalResult> gatedKeyword = applyEvidenceGates(
                keywordResults, retrievalConfig.getMinVectorSimilarity(),
                retrievalConfig.getKeywordRelativeScoreFloor());

        // 阶段 6：RRF 融合 → 保留 candidateTopK 条进入 Rerank
        List<RetrievalResult> fused = rrfFusionService.fuse(
                gatedVector, gatedKeyword, rrfK, candidateTopK);

        // 阶段 7：Parent Block Elevation — 小 chunk 替换为父块完整文本
        List<RetrievalResult> elevated = elevationService.elevate(fused);

        // 阶段 8：Rerank — cross-encoder 精排
        List<RetrievalResult> reranked = rerankService.rerank(rerankQuery, elevated);

        // 阶段 9：截断到 finalTopK
        return reranked.size() > finalTopK
                ? new ArrayList<>(reranked.subList(0, finalTopK))
                : reranked;
    }

    private List<RetrievalResult> getFutureResults(CompletableFuture<List<RetrievalResult>> future,
                                                    String channel) {
        try {
            return future.get();
        } catch (Exception e) {
            log.warn("{}通道获取结果失败: {}", channel, e.getMessage());
            return List.of();
        }
    }

    // ======================== 多子问题检索 + 合流 ========================

    /**
     * 对多个子问题逐个检索，按 chunkId 去重合流，保留首次出现顺序。
     */
    private List<RetrievalResult> executeMultiQueryPipeline(
            List<String> subQueries, int vectorTopK, int keywordTopK,
            int finalTopK, int candidateTopK, int rrfK, long timeoutMs,
            Map<String, String> filters) {

        LinkedHashSet<Long> seenChunkIds = new LinkedHashSet<>();
        List<RetrievalResult> merged = new ArrayList<>();

        for (String subQuery : subQueries) {
            List<RetrievalResult> subResults = executeSingleQueryPipeline(
                    subQuery, subQuery, vectorTopK, keywordTopK,
                    finalTopK, candidateTopK, rrfK, timeoutMs, filters);

            for (RetrievalResult r : subResults) {
                if (r.getChunkId() != null && seenChunkIds.add(r.getChunkId())) {
                    merged.add(r);
                }
            }
        }

        log.info("多子问题检索合并: subQueries={}, mergedResults={}", subQueries.size(), merged.size());
        return merged;
    }

    // ======================== Evidence Gates ========================

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
