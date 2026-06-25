package com.reubenagent.chat.support;

/**
 * 检索可观测落库内部接口 —— adapter 调用，由 noop（Phase 6）或 mybatis（Phase 8）实现。
 *
 * <p>Phase 8 实现 Bean 标注 {@code @Primary} 并 {@link MybatisChatRetrievalObserveStore}
 * 接口，让 Spring 在 {@code @ConditionalOnMissingBean} 上停用 noop。</p>
 *
 * @author reuben
 * @since 2026-06-25
 */
public interface IChatRetrievalObserveStoreInternal {

    /**
     * 批量落检索结果（每个子问题一组）。
     *
     * @param aggregated adapter 产出的聚合结果，含 references
     */
    void recordRetrievalResults(String conversationId, Long turnId, String traceId,
                                java.util.List<com.reubenagent.chat.model.orchestrate.ChatRetrievalResult> aggregated);

    /**
     * 落一条通道执行记录（per sub-question × channel）。
     */
    void recordChannelExecution(String conversationId, Long turnId, String traceId,
                                Integer subQuestionIndex, String channelType,
                                long durationMs, int recalledCount, int acceptedCount,
                                java.math.BigDecimal maxScore, java.math.BigDecimal minScore);
}
