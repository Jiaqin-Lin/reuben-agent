package com.reubenagent.chat.support;

/**
 * Phase 8 将提供的检索落库实现标记接口。
 *
 * <p>仅作为 {@link NoopChatRetrievalObserveStore} 的 {@code @ConditionalOnMissingBean} 探针类型，
 * Phase 8 实现该接口并标注 {@code @Primary} 后，noop 实现自动停用。</p>
 *
 * @author reuben
 * @since 2026-06-25
 */
public interface MybatisChatRetrievalObserveStore extends ChatRetrievalObserveStore {
}
