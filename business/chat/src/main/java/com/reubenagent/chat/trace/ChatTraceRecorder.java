package com.reubenagent.chat.trace;

import com.reubenagent.chat.enums.ChatTraceStageCode;
import com.reubenagent.chat.enums.ChatTraceStageState;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.util.Collections;
import java.util.List;

/**
 * 全链路追踪记录器 —— 每轮对话一个实例。
 *
 * <p>Phase 2 仅提供 stage 句柄与 no-op 落库：{@code traceStageStore} 在 Phase 8 接入，
 * 当前 {@link #startStage} 返回内存态句柄，{@code completeStage}/{@code failStage} 仅 warn 日志，
 * 不阻塞主流程。{@link ChatTraceStageStore} 持久化由 Phase 8 完善。</p>
 *
 * <p>修正 super-agent 问题 5：追踪落库失败 <b>warn 不中断</b>（落 trace error，不静默吞、
 * 也不向上抛打断生成）。</p>
 *
 * @author reuben
 * @since 2026-06-24
 */
@Slf4j
public class ChatTraceRecorder {

    @Getter
    private final String conversationId;
    @Getter
    private final Long turnId;
    @Getter
    private final String traceId;

    private final ChatTraceStageStore traceStageStore;

    public ChatTraceRecorder(ChatTraceStageStore traceStageStore,
                             String conversationId, Long turnId, String traceId) {
        this.traceStageStore = traceStageStore;
        this.conversationId = conversationId;
        this.turnId = turnId;
        this.traceId = traceId;
    }

    /** stage 句柄 —— 持有 stageId 与起始时间。 */
    public record StageHandle(long stageId, long startTimeMs, ChatTraceStageCode stageCode) {
        public static StageHandle noop(ChatTraceStageCode code) {
            return new StageHandle(-1L, System.currentTimeMillis(), code);
        }
    }

    public StageHandle startStage(ChatTraceStageCode stageCode, String executionMode,
                                  String summaryText, Object snapshot) {
        if (traceStageStore == null) {
            return StageHandle.noop(stageCode);
        }
        try {
            long stageId = traceStageStore.startStage(conversationId, turnId, traceId,
                    stageCode, 1, null, executionMode, summaryText, snapshot);
            return new StageHandle(stageId, System.currentTimeMillis(), stageCode);
        } catch (Exception e) {
            log.warn("追踪 stage 起始落库失败，降级 no-op → stage={} err={}", stageCode, e.getMessage());
            return StageHandle.noop(stageCode);
        }
    }

    public void completeStage(StageHandle handle, String summaryText, Object snapshot) {
        finishStage(handle, ChatTraceStageState.COMPLETED, summaryText, null, snapshot);
    }

    public void failStage(StageHandle handle, String summaryText, String errorMessage, Object snapshot) {
        finishStage(handle, ChatTraceStageState.FAILED, summaryText, errorMessage, snapshot);
    }

    private void finishStage(StageHandle handle, ChatTraceStageState state,
                             String summaryText, String errorMessage, Object snapshot) {
        if (traceStageStore == null || handle == null || handle.stageId() < 0) {
            return;
        }
        try {
            long duration = System.currentTimeMillis() - handle.startTimeMs();
            traceStageStore.finishStage(handle.stageId(), state, summaryText, errorMessage, snapshot, duration);
        } catch (Exception e) {
            // 阶段：追踪落库失败不中断主流程
            log.warn("追踪 stage 收尾落库失败 → stage={} state={} err={}",
                    handle.stageCode(), state, e.getMessage());
        }
    }

    public List<Object> snapshotModelUsageTraces() {
        return Collections.emptyList();
    }
}
