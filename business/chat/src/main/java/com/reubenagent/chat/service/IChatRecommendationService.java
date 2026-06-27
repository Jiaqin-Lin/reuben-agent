package com.reubenagent.chat.service;

import com.reubenagent.chat.model.SearchReference;

import java.util.List;

/**
 * 推荐追问服务 —— 基于当前问答 + 引用，让 LLM 生成最多 N 条用户可能想继续追问的问题。
 *
 * <p>对标 super-agent {@code RecommendationService}，reuben 修正（计划问题 16）：
 * <ul>
 *   <li>失败时 <b>warn + 落 trace</b>，而非双层 catch 静默吞；</li>
 *   <li>JSON 提取用 {@link com.reubenagent.chat.support.ChatJsonCodec#extractFirstBalancedArray}，
 *       不裸 {@code indexOf('[')}；</li>
 *   <li>超时用 {@code CompletableFuture.orTimeout}，阈值来自 {@link com.reubenagent.chat.config.ChatProperties.Recommendation}。</li>
 * </ul></p>
 *
 * @author reuben
 * @since 2026-06-27
 */
public interface IChatRecommendationService {

    /**
     * 生成推荐追问。
     *
     * @param question   当前用户提问
     * @param answer     当前回答（空则跳过生成）
     * @param references 当前轮引用（可空，注入 prompt 帮助生成更聚焦的追问）
     * @param traceRecorder 追踪记录器（可空，落 RECOMMENDATION stage + 失败 trace error）
     * @return 推荐追问列表，关闭 / 失败 / 超时返回空集合（前端可显示占位）
     */
    List<String> recommend(String question, String answer, List<SearchReference> references,
                           com.reubenagent.chat.trace.ChatTraceRecorder traceRecorder);
}
