package com.reubenagent.chat.support;

/**
 * 全链路追踪 store 标记接口（Phase 8 提供落库实现）。
 *
 * <p>Phase 6 引入此接口仅为 {@link NoopChatRetrievalObserveStore} 提供
 * {@code @ConditionalOnMissingBean} 探针类型——Phase 8 实现该接口并标注
 * {@code @Primary} 后，noop 实现自动停用。</p>
 *
 * @author reuben
 * @since 2026-06-25
 */
public interface ChatRetrievalObserveStore {
}
