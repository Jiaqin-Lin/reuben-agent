package com.reubenagent.chat.trace;

/**
 * Phase 8 将提供的 {@code reuben_agent_chat_trace_stage} 落库实现标记接口。
 *
 * <p>仅作为 {@link NoopChatTraceStageStore} 的 {@code @ConditionalOnMissingBean} 探针类型，
 * Phase 8 实现该接口并标注 {@code @Primary} 后，noop 实现自动停用。</p>
 *
 * @author reuben
 * @since 2026-06-24
 */
public interface MybatisChatTraceStageStore extends ChatTraceStageStore {
}
