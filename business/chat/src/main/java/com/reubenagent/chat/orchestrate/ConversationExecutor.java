package com.reubenagent.chat.orchestrate;

import com.reubenagent.chat.enums.ExecutionMode;
import reactor.core.publisher.Flux;

/**
 * 对话执行器接口 —— 策略模式核心抽象。
 *
 * <p>每种 {@link ExecutionMode} 对应一个执行器，{@code execute} 返回文本块
 * {@link Flux}，由 orchestrator 订阅并 emit 到 SSE。Phase 2 仅 stub
 * {@code EchoExecutor}（mode=REACT_AGENT）让管线跑通，Phase 6/7/8 接入真实执行器。</p>
 *
 * @author reuben
 * @since 2026-06-24
 */
public interface ConversationExecutor {

    /** 该执行器处理的执行模式。 */
    ExecutionMode mode();

    /**
     * 执行一轮对话，逐块产出文本。
     * <p>执行器只负责产出文本块（含 thinking 事件可经 taskInfo.sink emit），
     * 不负责落库 / 收尾 —— 那是 orchestrator 的 {@code finalize} 职责。</p>
     */
    Flux<String> execute(ChatTaskInfo taskInfo);
}
