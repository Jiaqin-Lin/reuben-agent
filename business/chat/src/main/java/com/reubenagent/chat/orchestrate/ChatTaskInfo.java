package com.reubenagent.chat.orchestrate;

import com.reubenagent.chat.enums.ChatMode;
import com.reubenagent.chat.trace.ChatTraceRecorder;
import lombok.Builder;
import lombok.Getter;
import reactor.core.Disposable;
import reactor.core.publisher.Sinks;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 一次对话请求的上下文袋子 —— 贯穿 orchestrator → executor → finalize 全生命周期。
 *
 * <p>不可变标识字段通过 {@link Builder} 一次性装配；可变执行态（executionPlan、disposable、
 * answerBuffer、finalized）在管线推进中赋值。{@code finalized} 用 CAS 保证收尾幂等，
 * 替代 super-agent 三个 80 行 try/catch/finally 梯度重复的 finalize 方法。</p>
 *
 * <p>Phase 2 阶段 {@code traceRecorder} 为可选（stub executor 不落追踪），后续 Phase 接入。</p>
 *
 * @author reuben
 * @since 2026-06-24
 */
@Getter
@Builder
public class ChatTaskInfo {

    private final String conversationId;
    private final Long turnId;
    private final String question;
    private final ChatMode chatMode;
    private final String traceId;
    private final Long selectedDocumentId;
    private final String selectedDocumentName;
    private final LocalDate currentDate;
    private final String currentDateText;

    /** SSE 事件发射器 */
    private final Sinks.Many<String> sink;

    /** 全链路追踪记录器（Phase 8 完善，Phase 2 可为 null） */
    private final ChatTraceRecorder traceRecorder;

    /** Redis 租约键 / 持有者令牌 */
    private final String leaseKey;
    private final String leaseOwnerToken;

    /** 累积的完整答案文本（用于最终持久化） */
    @Getter
    private final StringBuffer answerBuffer = new StringBuffer();

    /** 系统思考步骤（推送给前端的状态） */
    private final List<String> thinkingSteps;
    /** 引文来源 */
    private final List<Object> references;
    /** 使用过的工具名称集合 */
    private final Set<String> usedTools;

    private final long startTime;

    /** 首字延迟毫秒，0 表示尚未出字 */
    @Getter
    private final AtomicLong firstTokenLatencyMs = new AtomicLong(0L);

    /** 收尾幂等标志 */
    @Getter
    private final AtomicBoolean finalized = new AtomicBoolean(false);

    @Builder.Default
    private volatile Disposable disposable = null;
    @Builder.Default
    private volatile Disposable leaseRenewalDisposable = null;

    public void setDisposable(Disposable disposable) {
        this.disposable = disposable;
    }

    public void setLeaseRenewalDisposable(Disposable disposable) {
        this.leaseRenewalDisposable = disposable;
    }

    /** 标记是否已收尾。 */
    public boolean markFinalized() {
        return finalized.compareAndSet(false, true);
    }

    /** 累积并返回当前答案长度。 */
    public int appendAnswer(String chunk) {
        answerBuffer.append(chunk);
        return answerBuffer.length();
    }
}
