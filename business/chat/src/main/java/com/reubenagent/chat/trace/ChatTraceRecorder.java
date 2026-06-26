package com.reubenagent.chat.trace;

import com.reubenagent.chat.enums.ChatTraceStageCode;
import com.reubenagent.chat.enums.ChatTraceStageState;
import com.reubenagent.chat.enums.ExecutionMode;
import com.reubenagent.chat.model.debug.ChatModelUsageTrace;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;

/**
 * 全链路追踪记录器 —— 每轮对话一个实例。
 *
 * <p>Phase 8 接入完整链路：
 * <ul>
 *   <li>stage 句柄委托 {@link ChatTraceStageStore} 落库（{@code startStage}/{@code completeStage}/{@code failStage}）；</li>
 *   <li>per-turn {@link ChatModelUsageTrace} 通过 {@link #traceSink()} 接收，{@link #snapshotModelUsageTraces()} 快照；</li>
 *   <li>{@link ChatStageBenchmarkService} 在 stage 收尾时记录耗时，构建 p50/p90/p99 基线。</li>
 * </ul></p>
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
    private final ChatStageBenchmarkService benchmarkService;

    /** per-turn 模型调用追踪累积（synchronized list：调用频率低，简单即可） */
    private final List<ChatModelUsageTrace> perTurnTraces = Collections.synchronizedList(new ArrayList<>());

    public ChatTraceRecorder(ChatTraceStageStore traceStageStore,
                             String conversationId, Long turnId, String traceId) {
        this(traceStageStore, null, conversationId, turnId, traceId);
    }

    public ChatTraceRecorder(ChatTraceStageStore traceStageStore,
                             ChatStageBenchmarkService benchmarkService,
                             String conversationId, Long turnId, String traceId) {
        this.traceStageStore = traceStageStore;
        this.benchmarkService = benchmarkService;
        this.conversationId = conversationId;
        this.turnId = turnId;
        this.traceId = traceId;
    }

    /** stage 句柄 —— 持有 stageId / 起始时间 / stageCode / executionMode（用于 benchmark）。 */
    public record StageHandle(long stageId, long startTimeMs, ChatTraceStageCode stageCode,
                              String executionMode) {
        public static StageHandle noop(ChatTraceStageCode code) {
            return new StageHandle(-1L, System.currentTimeMillis(), code, null);
        }

        public static StageHandle of(ChatTraceStageCode code, String executionMode) {
            return new StageHandle(-1L, System.currentTimeMillis(), code, executionMode);
        }
    }

    public StageHandle startStage(ChatTraceStageCode stageCode, String executionMode,
                                  String summaryText, Object snapshot) {
        if (traceStageStore == null) {
            return StageHandle.of(stageCode, executionMode);
        }
        try {
            long stageId = traceStageStore.startStage(conversationId, turnId, traceId,
                    stageCode, 1, null, executionMode, summaryText, snapshot);
            return new StageHandle(stageId, System.currentTimeMillis(), stageCode, executionMode);
        } catch (Exception e) {
            log.warn("追踪 stage 起始落库失败，降级 no-op → stage={} err={}", stageCode, e.getMessage());
            return StageHandle.of(stageCode, executionMode);
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
        if (handle == null) {
            return;
        }
        long duration = System.currentTimeMillis() - handle.startTimeMs();
        if (traceStageStore != null && handle.stageId() >= 0) {
            try {
                traceStageStore.finishStage(handle.stageId(), state, summaryText, errorMessage, snapshot, duration);
            } catch (Exception e) {
                // 阶段：追踪落库失败不中断主流程
                log.warn("追踪 stage 收尾落库失败 → stage={} state={} err={}",
                        handle.stageCode(), state, e.getMessage());
            }
        }
        recordBenchmark(handle, state, duration);
    }

    private void recordBenchmark(StageHandle handle, ChatTraceStageState state, long durationMs) {
        if (benchmarkService == null || handle == null) {
            return;
        }
        // 仅 COMPLETED 计入基线，FAILED 耗时通常不可代表正常性能
        if (state != ChatTraceStageState.COMPLETED) {
            return;
        }
        ExecutionMode mode = parseExecutionMode(handle.executionMode());
        if (mode == null) {
            return;
        }
        try {
            benchmarkService.recordDuration(handle.stageCode(), mode, durationMs);
        } catch (Exception e) {
            log.debug("benchmark 记录失败（可忽略） → stage={} err={}", handle.stageCode(), e.getMessage());
        }
    }

    private ExecutionMode parseExecutionMode(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        try {
            return ExecutionMode.valueOf(name);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** 暴露 per-turn 追踪 sink，由调用方传给 {@code ObservedChatModelService.callText/streamText}。 */
    public Consumer<ChatModelUsageTrace> traceSink() {
        return perTurnTraces::add;
    }

    /** 快照本轮累积的模型调用追踪（落 turn model_usage_json 用）。 */
    public List<ChatModelUsageTrace> snapshotModelUsageTraces() {
        synchronized (perTurnTraces) {
            return List.copyOf(perTurnTraces);
        }
    }
}
