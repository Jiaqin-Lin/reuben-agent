package com.reubenagent.chat.support;

import reactor.core.publisher.Sinks;

/**
 * Reactor 背压安全的 SSE 发射辅助。
 *
 * <p>{@link Sinks.Many#tryEmitNext(Object)} 要求串行 emit，多线程环境下用
 * {@code synchronized(sink)} 串行化。对 {@code FAIL_CANCELLED} /
 * {@code FAIL_TERMINATED} / {@code FAIL_ZERO_SUBSCRIBER} 静默忽略——这些都是
 * 正常的终止场景（客户端关闭、流已完成），不需要抛异常。</p>
 *
 * @author reuben
 * @since 2026-06-24
 */
public final class SinkEmitHelper {

    private SinkEmitHelper() {
    }

    public static void emitNext(Sinks.Many<String> sink, String payload) {
        if (sink == null || payload == null) {
            return;
        }
        synchronized (sink) {
            Sinks.EmitResult result = sink.tryEmitNext(payload);
            if (result == Sinks.EmitResult.FAIL_CANCELLED
                    || result == Sinks.EmitResult.FAIL_TERMINATED
                    || result == Sinks.EmitResult.FAIL_ZERO_SUBSCRIBER) {
                return;
            }
            if (result.isFailure()) {
                // 阶段：溢出 / 非串行等异常，落到调用方日志即可，不再向上抛
                return;
            }
        }
    }

    public static void emitComplete(Sinks.Many<String> sink) {
        if (sink == null) {
            return;
        }
        synchronized (sink) {
            Sinks.EmitResult result = sink.tryEmitComplete();
            if (result == Sinks.EmitResult.FAIL_CANCELLED
                    || result == Sinks.EmitResult.FAIL_TERMINATED
                    || result == Sinks.EmitResult.FAIL_ZERO_SUBSCRIBER) {
                return;
            }
            // 其余失败状态静默：流已不可发也无需再关
        }
    }
}
