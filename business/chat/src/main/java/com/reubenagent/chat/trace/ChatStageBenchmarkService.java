package com.reubenagent.chat.trace;

import com.reubenagent.chat.enums.ChatTraceStageCode;
import com.reubenagent.chat.enums.ExecutionMode;

import java.util.List;

/**
 * 对话阶段基准服务 —— 按 {@code (stageCode, executionMode)} 维度构建 p50/p90/p99 滑窗。
 *
 * <p>Phase 8 用 Redis LIST + 原子 LTRIM 维护滑窗，每 N 次 push 触发一次 flush 落库
 * （{@code reuben_agent_chat_stage_benchmark} 表，UNIQUE {@code (stage_code, execution_mode)}）。
 * benchmark 仅供观测，落库 / Redis 失败均 warn 不影响主流程。</p>
 *
 * @author reuben
 * @since 2026-06-25
 */
public interface ChatStageBenchmarkService {

    /** 记录一次 stage 耗时样本。 */
    void recordDuration(ChatTraceStageCode stageCode, ExecutionMode executionMode, long durationMs);

    /** 查询单条基准。 */
    java.util.Optional<com.reubenagent.chat.entity.ChatStageBenchmark> get(
            ChatTraceStageCode stageCode, ExecutionMode executionMode);

    /** 列出全部基准（管理 / 观测页用）。 */
    List<com.reubenagent.chat.entity.ChatStageBenchmark> listAll();
}
