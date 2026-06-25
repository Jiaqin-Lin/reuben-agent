package com.reubenagent.chat.support;

import com.reubenagent.chat.model.orchestrate.ChatRetrievalResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;

/**
 * 检索可观测落库 noop 占位 —— Phase 6 仅保留方法签名，Phase 8 接入 MyBatis 落库。
 *
 * <p>Phase 6 实现 {@link ChatRetrievalObserveStore} 接口（作为 noop 探针），
 * 让 adapter 侧的 store 注入不空指针；Phase 8 提供真正落 {@code reuben_agent_chat_retrieval_result}
 * 与 {@code reuben_agent_chat_channel_execution} 的 {@code @Primary} 实现后，
 * 本类因 {@link ConditionalOnMissingBean} 自动停用。</p>
 *
 * <p>当前实现：失败 warn 不中断（落 trace error，不静默吞），与 super-agent 问题 5 一致的修正。</p>
 *
 * @author reuben
 * @since 2026-06-25
 */
@Slf4j
@Component
@ConditionalOnMissingBean(MybatisChatRetrievalObserveStore.class)
public class NoopChatRetrievalObserveStore implements ChatRetrievalObserveStore,
        IChatRetrievalObserveStoreInternal {

    @Override
    public void recordRetrievalResults(String conversationId, Long turnId, String traceId,
                                       List<ChatRetrievalResult> aggregated) {
        log.debug("[noop] 检索结果落库跳过 → conversationId={} turnId={} groups={}",
                conversationId, turnId, aggregated == null ? 0 : aggregated.size());
    }

    @Override
    public void recordChannelExecution(String conversationId, Long turnId, String traceId,
                                       Integer subQuestionIndex, String channelType,
                                       long durationMs, int recalledCount, int acceptedCount,
                                       BigDecimal maxScore, BigDecimal minScore) {
        log.debug("[noop] 通道执行落库跳过 → conversationId={} channel={} recalled={} accepted={}",
                conversationId, channelType, recalledCount, acceptedCount);
    }
}
