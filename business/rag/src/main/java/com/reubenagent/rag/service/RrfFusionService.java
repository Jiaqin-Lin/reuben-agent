package com.reubenagent.rag.service;

import com.reubenagent.rag.model.RetrievalResult;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * RRF (Reciprocal Rank Fusion) 融合服务 —— 纯函数，无状态。
 *
 * <p>将向量通道和关键词通道的检索结果按 chunkId 去重合并，
 * 使用 RRF 公式重新计算分数，同一 chunk 在两通道都命中时 source 标记为 {@code "hybrid"}。</p>
 *
 * <h3>RRF 公式</h3>
 * <pre>RRF_score(d) = Σ(c∈channels) 1 / (K + rank_c(d))</pre>
 * <p>其中 K=60，rank 从 1 开始（通道内第一条结果 rank=1）。</p>
 *
 * <h3>复杂度</h3>
 * <p>{@link LinkedHashMap} 单次遍历 O(n)，不用 HashMap 逐条 merge。</p>
 *
 * @author reuben
 * @since 2026-06-21
 */
@Component
public class RrfFusionService {

    /**
     * 对两个通道的结果执行 RRF 融合。
     *
     * @param vectorResults  向量通道检索结果
     * @param keywordResults 关键词通道检索结果
     * @param k              RRF 公式 K 值（典型 60）
     * @param finalTopK      融合后最终返回数量
     * @return 按 RRF 分数降序排列、截断到 finalTopK 的结果列表
     */
    public List<RetrievalResult> fuse(List<RetrievalResult> vectorResults,
                                      List<RetrievalResult> keywordResults,
                                      int k, int finalTopK) {
        // 防御：null → 空列表
        List<RetrievalResult> vector = vectorResults != null ? vectorResults : List.of();
        List<RetrievalResult> keyword = keywordResults != null ? keywordResults : List.of();

        // 快速路径：两通道都为空
        if (vector.isEmpty() && keyword.isEmpty()) {
            return List.of();
        }

        // 阶段 1：LinkedHashMap 按 chunkId 去重合并，O(n) 单次遍历
        LinkedHashMap<Long, RetrievalResult> merged = new LinkedHashMap<>();

        // 向量通道：rank 从 1 开始
        for (int i = 0; i < vector.size(); i++) {
            RetrievalResult r = vector.get(i);
            Long chunkId = r.getChunkId();
            double rrfScore = 1.0 / (k + i + 1);

            RetrievalResult entry = merged.get(chunkId);
            if (entry != null) {
                // 同一 chunk 已在关键词通道出现过 → 累加分数，标记 hybrid
                entry.setScore(entry.getScore() + rrfScore);
                entry.setSource("hybrid");
            } else {
                merged.put(chunkId, RetrievalResult.builder()
                        .chunkId(chunkId)
                        .chunkText(r.getChunkText())
                        .score(rrfScore)
                        .sectionPath(r.getSectionPath())
                        .documentId(r.getDocumentId())
                        .parentBlockId(r.getParentBlockId())
                        .source("vector")
                        .build());
            }
        }

        // 关键词通道：rank 从 1 开始
        for (int i = 0; i < keyword.size(); i++) {
            RetrievalResult r = keyword.get(i);
            Long chunkId = r.getChunkId();
            double rrfScore = 1.0 / (k + i + 1);

            RetrievalResult entry = merged.get(chunkId);
            if (entry != null) {
                // 同一 chunk 已在向量通道出现过 → 累加分数，标记 hybrid
                entry.setScore(entry.getScore() + rrfScore);
                entry.setSource("hybrid");
            } else {
                merged.put(chunkId, RetrievalResult.builder()
                        .chunkId(chunkId)
                        .chunkText(r.getChunkText())
                        .score(rrfScore)
                        .sectionPath(r.getSectionPath())
                        .documentId(r.getDocumentId())
                        .parentBlockId(r.getParentBlockId())
                        .source("keyword")
                        .build());
            }
        }

        // 阶段 2：按 score 降序排序 → 截断到 finalTopK
        List<RetrievalResult> fused = new ArrayList<>(merged.values());
        fused.sort(Comparator.comparingDouble(RetrievalResult::getScore).reversed());

        if (fused.size() > finalTopK) {
            return fused.subList(0, finalTopK);
        }
        return fused;
    }
}
