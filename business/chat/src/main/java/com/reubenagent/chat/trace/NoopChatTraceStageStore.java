package com.reubenagent.chat.trace;

import com.reubenagent.chat.enums.ChatTraceStageCode;
import com.reubenagent.chat.enums.ChatTraceStageState;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Component;

/**
 * Phase 2 占位实现 —— 不落库，返回虚拟 stageId。
 *
 * <p>Phase 8 提供落 {@code reuben_agent_chat_trace_stage} 的 {@link ChatTraceStageStore}
 * 实现 Bean（用 {@code @Primary} 标注）后，本实现因 {@link ConditionalOnMissingBean}
 * 自动停用。</p>
 *
 * @author reuben
 * @since 2026-06-24
 */
@Slf4j
@Component
@ConditionalOnMissingBean(MybatisChatTraceStageStore.class)
public class NoopChatTraceStageStore implements ChatTraceStageStore {

    @Override
    public long startStage(String conversationId, Long turnId, String traceId,
                           ChatTraceStageCode stageCode, int stageLevel, Long parentStageId,
                           String executionMode, String summaryText, Object snapshot) {
        return -1L;
    }

    @Override
    public void finishStage(long stageId, ChatTraceStageState state, String summaryText,
                            String errorMessage, Object snapshot, long durationMs) {
        // no-op
    }
}


