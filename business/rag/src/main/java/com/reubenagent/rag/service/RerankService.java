package com.reubenagent.rag.service;

import com.reubenagent.rag.config.RagProperties;
import com.reubenagent.rag.model.RetrievalResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 重排序服务 —— 通过 cross-encoder 模型对候选结果精排。
 *
 * <h3>流程</h3>
 * <ol>
 *   <li>检查 enabled 开关 → 未启用或候选为空则原样返回</li>
 *   <li>构建 HTTP POST 到 rerank API（如 SiliconFlow）</li>
 *   <li>解析返回的 relevance_score → 更新 result.score，原分数记入 rerankScore</li>
 *   <li>按新分数降序排列，source 追加 "+rerank"</li>
 *   <li>异常 → log.warn + 返回原顺序（降级，不阻塞）</li>
 * </ol>
 *
 * <h3>与 super-agent 的差异</h3>
 * <ul>
 *   <li>super-agent 用 {@code RestClient} + {@code DocumentPostProcessor} 接口</li>
 *   <li>reuben-agent 用 {@link RestTemplate}，更简单直接</li>
 *   <li>metadata 精简为单个 {@code rerankScore} 字段</li>
 * </ul>
 *
 * @author reuben
 * @since 2026-06-22
 */
@Slf4j
@Component
public class RerankService {

    private final RagProperties ragProperties;
    private final RestTemplate restTemplate;

    public RerankService(RagProperties ragProperties) {
        this.ragProperties = ragProperties;
        RagProperties.Rerank config = ragProperties.getRerank();

        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofMillis(config.getConnectTimeoutMs()));
        factory.setReadTimeout(Duration.ofMillis(config.getReadTimeoutMs()));
        this.restTemplate = new RestTemplate(factory);
    }

    /**
     * 对候选结果执行重排序。
     *
     * @param query      原始查询文本
     * @param candidates 候选结果列表（已按上游分数降序）
     * @return 重排序后的结果（按 relevance_score 降序），异常时返回原顺序
     */
    @SuppressWarnings("unchecked")
    public List<RetrievalResult> rerank(String query, List<RetrievalResult> candidates) {
        RagProperties.Rerank config = ragProperties.getRerank();

        // 阶段 1：开关检查
        if (!config.isEnabled()) {
            log.debug("Rerank 未启用，原样返回 {} 条候选", candidates != null ? candidates.size() : 0);
            return candidates != null ? candidates : List.of();
        }

        if (candidates == null || candidates.isEmpty()) {
            return List.of();
        }

        // 阶段 2：构建请求
        List<String> documents = candidates.stream()
                .map(r -> r.getChunkText() != null ? r.getChunkText() : "")
                .toList();

        Map<String, Object> requestBody = new LinkedHashMap<>();
        requestBody.put("model", config.getModel());
        requestBody.put("query", query);
        requestBody.put("documents", documents);
        requestBody.put("top_n", Math.min(config.getTopN(), documents.size()));

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (config.getApiKey() != null && !config.getApiKey().isBlank()) {
            headers.setBearerAuth(config.getApiKey());
        }
        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(requestBody, headers);

        // 阶段 3：HTTP 调用
        try {
            log.debug("Rerank 请求: url={}, model={}, docs={}", config.getUrl(), config.getModel(), documents.size());
            ResponseEntity<Map> response = restTemplate.exchange(
                    config.getUrl(), HttpMethod.POST, entity, Map.class);

            Map<String, Object> body = response.getBody();
            if (body == null || !body.containsKey("results")) {
                log.warn("Rerank API 返回格式异常: {}", body);
                return candidates;
            }

            List<Map<String, Object>> results = (List<Map<String, Object>>) body.get("results");
            if (results == null || results.isEmpty()) {
                log.warn("Rerank API 返回空 results");
                return candidates;
            }

            // 阶段 4：映射分数 → 新建结果列表
            List<RetrievalResult> reranked = new ArrayList<>();
            for (Map<String, Object> item : results) {
                int index = ((Number) item.get("index")).intValue();
                double relevanceScore = ((Number) item.get("relevance_score")).doubleValue();

                if (index >= 0 && index < candidates.size()) {
                    RetrievalResult original = candidates.get(index);
                    String newSource = (original.getSource() != null ? original.getSource() : "unknown") + "+rerank";
                    reranked.add(RetrievalResult.builder()
                            .chunkId(original.getChunkId())
                            .chunkText(original.getChunkText())
                            .score(relevanceScore)           // 新分数
                            .rerankScore(original.getScore()) // 原 RRF 分数记入 rerankScore
                            .sectionPath(original.getSectionPath())
                            .documentId(original.getDocumentId())
                            .parentBlockId(original.getParentBlockId())
                            .source(newSource)
                            .build());
                }
            }

            // 阶段 5：按新分数降序排列
            reranked.sort(Comparator.comparingDouble(
                    r -> r.getScore() != null ? r.getScore() : 0.0));
            java.util.Collections.reverse(reranked);

            log.debug("Rerank 完成: {} 条 → {} 条", candidates.size(), reranked.size());
            return reranked;

        } catch (Exception e) {
            log.warn("Rerank 调用失败，降级返回原顺序: {}", e.getMessage());
            return candidates;
        }
    }
}
