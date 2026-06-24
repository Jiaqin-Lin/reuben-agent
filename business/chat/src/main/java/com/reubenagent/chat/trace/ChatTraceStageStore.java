package com.reubenagent.chat.trace;

import com.reubenagent.chat.enums.ChatTraceStageCode;
import com.reubenagent.chat.enums.ChatTraceStageState;

/**
 * 全链路追踪 stage 持久化仓储。
 *
 * <p>Phase 8 落 {@code reuben_agent_chat_trace_stage} 表；Phase 2 注入 no-op 默认实现，
 * 让 {@link ChatTraceRecorder} 不空指针。</p>
 *
 * @author reuben
 * @since 2026-06-24
 */
public interface ChatTraceStageStore {

    /** 起始 stage，返回 stageId。 */
    long startStage(String conversationId, Long turnId, String traceId,
                    ChatTraceStageCode stageCode, int stageLevel, Long parentStageId,
                    String executionMode, String summaryText, Object snapshot);

    /** 收尾 stage（成功/失败/跳过统一入口）。 */
    void finishStage(long stageId, ChatTraceStageState state, String summaryText,
                     String errorMessage, Object snapshot, long durationMs);
}
