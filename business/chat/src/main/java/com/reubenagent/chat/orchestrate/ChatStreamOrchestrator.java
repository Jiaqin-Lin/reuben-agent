package com.reubenagent.chat.orchestrate;

import com.reubenagent.chat.config.ChatProperties;
import com.reubenagent.chat.dto.ChatStreamDto;
import com.reubenagent.chat.enums.ChatErrorCode;
import com.reubenagent.chat.enums.ChatMode;
import com.reubenagent.chat.enums.ChatSessionStatus;
import com.reubenagent.chat.enums.ChatTraceStageCode;
import com.reubenagent.chat.enums.ChatTurnStatus;
import com.reubenagent.chat.enums.ExecutionMode;
import com.reubenagent.chat.exception.ChatException;
import com.reubenagent.chat.model.orchestrate.ConversationExecutionPlan;
import com.reubenagent.chat.session.ChatArchiveStore;
import com.reubenagent.chat.session.ConversationArchiveRecord;
import com.reubenagent.chat.session.TurnArchiveRecord;
import com.reubenagent.chat.support.ChatStreamEventWriter;
import com.reubenagent.chat.support.SinkEmitHelper;
import com.reubenagent.chat.trace.ChatTraceRecorder;
import com.reubenagent.chat.trace.ChatTraceStageStore;
import com.reubenagent.chat.vo.ChatStopVo;
import com.reubenagent.framework.uid.UidGenerator;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Schedulers;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * SSE 流式编排骨架 —— 负责一轮对话的完整生命周期：租约 → 建 turn → 注册任务 →
 * 订阅执行器产出 → finalize 落库。
 *
 * <p>拆分自 super-agent 的 god-class：只保留 SSE 生命周期 + 租约 + 编排 + 持久化，
 * 会话 CRUD 在 {@code ChatSessionService}，视图装配在 VO。</p>
 *
 * <p>核心修正：三个 80 行 try/catch/finally 收尾方法合并为单一 {@link #finalize}，
 * CAS {@code finalized} 幂等。</p>
 *
 * @author reuben
 * @since 2026-06-24
 */
@Slf4j
@Service
@AllArgsConstructor
public class ChatStreamOrchestrator {

    private static final ZoneId CHAT_ZONE = ZoneId.of("Asia/Shanghai");

    private final ChatArchiveStore archiveStore;
    private final ChatRuntimeRegistry runtimeRegistry;
    private final ChatLeaseService leaseService;
    private final ConversationExecutorRegistry executorRegistry;
    private final ChatStreamEventWriter eventWriter;
    private final ChatProperties properties;
    private final UidGenerator uidGenerator;
    private final ChatTraceStageStore traceStageStore;
    private final ChatPreparationOrchestrator preparationOrchestrator;
    private final com.reubenagent.chat.support.ChatJsonCodec jsonCodec;

    /**
     * 开启流式回答主流程。
     *
     * <p>租约失败 / 启动异常均返回错误事件 Flux（不抛到 Controller，保证 SSE 通道可建立）。</p>
     */
    public Flux<String> openStream(ChatStreamDto dto) {
        return Flux.defer(() -> {
            StreamLaunchPlan plan;
            ChatLeaseService.LeaseClaim lease;
            try {
                plan = buildLaunchPlan(dto);
                lease = leaseService.tryAcquire(plan.getConversationId());
            } catch (ChatException e) {
                log.warn("流式启动被拒 → conversationId={} code={} msg={}",
                        dto.getConversationId(), e.getChatCode(), e.getMessage());
                return errorFlux(e.getMessage(), dto.getConversationId());
            } catch (Exception e) {
                log.error("流式启动异常 → conversationId={}", dto.getConversationId(), e);
                return errorFlux("会话启动失败", dto.getConversationId());
            }
            try {
                ChatTaskInfo taskInfo = bootstrapConversation(plan, lease);
                return bindClientChannel(taskInfo);
            } catch (ChatException e) {
                leaseService.release(lease);
                log.warn("bootstrap 失败 → conversationId={} msg={}", plan.getConversationId(), e.getMessage());
                return errorFlux(e.getMessage(), plan.getConversationId());
            } catch (Exception e) {
                leaseService.release(lease);
                log.error("bootstrap 异常 → conversationId={}", plan.getConversationId(), e);
                return errorFlux("会话启动失败", plan.getConversationId());
            }
        });
    }

    /** 停止正在执行的会话。 */
    public ChatStopVo stopStream(String conversationId, String reason) {
        return runtimeRegistry.get(conversationId)
                .map(taskInfo -> doStop(taskInfo, reason))
                .orElseGet(() -> ChatStopVo.builder()
                        .conversationId(conversationId)
                        .stopped(false)
                        .message("没有找到正在执行的会话")
                        .build());
    }

    // ======================== 主流程 ========================

    private StreamLaunchPlan buildLaunchPlan(ChatStreamDto dto) {
        String question = dto.getQuestion().trim();
        ChatMode mode = ChatMode.getFromCode(dto.getChatMode());
        if (mode == null) {
            throw new ChatException(ChatErrorCode.PARAM_INVALID, "非法的对话模式: " + dto.getChatMode());
        }
        if (mode == ChatMode.DOCUMENT && dto.getSelectedDocumentId() == null) {
            throw new ChatException(ChatErrorCode.PARAM_INVALID, "DOCUMENT 模式必须指定文档");
        }
        String conversationId = (dto.getConversationId() == null || dto.getConversationId().isBlank())
                ? generateConversationId() : dto.getConversationId().trim();

        LocalDate today = LocalDate.now(CHAT_ZONE);
        return StreamLaunchPlan.builder()
                .question(question)
                .conversationId(conversationId)
                .chatMode(mode)
                .selectedDocumentId(dto.getSelectedDocumentId())
                .selectedDocumentName(dto.getSelectedDocumentName())
                .currentDate(today)
                .currentDateText(formatCurrentDate(today))
                .build();
    }

    private ChatTaskInfo bootstrapConversation(StreamLaunchPlan plan, ChatLeaseService.LeaseClaim lease) {
        // 阶段 1：会话不存在则按 conversationId 建会话（IDLE）
        ensureConversation(plan);

        // 阶段 2：建 turn 行（RUNNING）
        Long turnId = uidGenerator.getUid();
        TurnArchiveRecord turn = TurnArchiveRecord.builder()
                .id(turnId)
                .conversationId(plan.getConversationId())
                .userPrompt(plan.getQuestion())
                .turnStatus(ChatTurnStatus.RUNNING.getCode())
                .build();
        archiveStore.startTurn(turn);

        // 阶段 3：装配任务上下文
        Sinks.Many<String> sink = Sinks.many().unicast().onBackpressureBuffer();
        String traceId = UUID.randomUUID().toString().replace("-", "");
        ChatTraceRecorder recorder = new ChatTraceRecorder(traceStageStore,
                plan.getConversationId(), turnId, traceId);

        ChatTaskInfo taskInfo = ChatTaskInfo.builder()
                .conversationId(plan.getConversationId())
                .turnId(turnId)
                .question(plan.getQuestion())
                .chatMode(plan.getChatMode())
                .traceId(traceId)
                .selectedDocumentId(plan.getSelectedDocumentId())
                .selectedDocumentName(plan.getSelectedDocumentName())
                .currentDate(plan.getCurrentDate())
                .currentDateText(plan.getCurrentDateText())
                .sink(sink)
                .traceRecorder(recorder)
                .leaseKey(lease.key())
                .leaseOwnerToken(lease.ownerToken())
                .thinkingSteps(Collections.synchronizedList(new java.util.ArrayList<>()))
                .references(Collections.synchronizedList(new java.util.ArrayList<>()))
                .usedTools(java.util.concurrent.ConcurrentHashMap.newKeySet())
                .startTime(System.currentTimeMillis())
                .build();

        if (!runtimeRegistry.register(taskInfo)) {
            // 阶段：并发竞争失败，把刚建的 turn 标记 FAILED
            archiveStore.completeTurn(plan.getConversationId(), turnId, TurnArchiveRecord.builder()
                    .turnStatus(ChatTurnStatus.FAILED.getCode())
                    .finishNote("会话当前正在执行中")
                    .build());
            throw new ChatException(ChatErrorCode.SESSION_RUNNING, plan.getConversationId());
        }
        return taskInfo;
    }

    private void ensureConversation(StreamLaunchPlan plan) {
        if (archiveStore.getConversation(plan.getConversationId()) != null) {
            // 阶段：会话已存在，置为执行中
            archiveStore.saveConversation(ConversationArchiveRecord.builder()
                    .conversationId(plan.getConversationId())
                    .sessionStatus(ChatSessionStatus.RUNNING.getCode())
                    .chatMode(plan.getChatMode().getCode())
                    .selectedDocumentId(plan.getSelectedDocumentId())
                    .selectedDocumentName(plan.getSelectedDocumentName())
                    .build());
            return;
        }
        String title = defaultTitle(plan.getChatMode(), plan.getSelectedDocumentName());
        archiveStore.saveConversation(ConversationArchiveRecord.builder()
                .id(uidGenerator.getUid())
                .conversationId(plan.getConversationId())
                .sessionStatus(ChatSessionStatus.RUNNING.getCode())
                .chatMode(plan.getChatMode().getCode())
                .title(title)
                .selectedDocumentId(plan.getSelectedDocumentId())
                .selectedDocumentName(plan.getSelectedDocumentName())
                .build());
    }

    private Flux<String> bindClientChannel(ChatTaskInfo taskInfo) {
        return taskInfo.getSink().asFlux()
                .doOnSubscribe(ignored -> activateGeneration(taskInfo))
                .doOnCancel(() -> doStop(taskInfo, "客户端已取消请求"));
    }

    /** 订阅时激活生成：启动租约续期 + 订阅执行器产出。 */
    private void activateGeneration(ChatTaskInfo taskInfo) {
        if (taskInfo.getFinalized().get()) {
            return;
        }
        Disposable leaseRenewal = startLeaseRenewal(taskInfo);
        taskInfo.setLeaseRenewalDisposable(leaseRenewal);

        Disposable disposable = buildExecution(taskInfo).subscribe();
        taskInfo.setDisposable(disposable);
    }

    private Flux<String> buildExecution(ChatTaskInfo taskInfo) {
        return Flux.defer(() -> {
            emitThinking(taskInfo, "正在分析问题上下文。");
            ConversationExecutionPlan plan = preparationOrchestrator.prepare(
                    StreamLaunchPlan.builder()
                            .question(taskInfo.getQuestion())
                            .conversationId(taskInfo.getConversationId())
                            .chatMode(taskInfo.getChatMode())
                            .selectedDocumentId(taskInfo.getSelectedDocumentId())
                            .selectedDocumentName(taskInfo.getSelectedDocumentName())
                            .currentDate(taskInfo.getCurrentDate())
                            .currentDateText(taskInfo.getCurrentDateText())
                            .build(),
                    taskInfo.getTraceRecorder());
            taskInfo.setExecutionPlan(plan);
            if (plan.isClarification()) {
                emitThinking(taskInfo, plan.getClarificationReply() == null ? "需要澄清" : plan.getClarificationReply());
                return Flux.just(plan.getClarificationReply() == null ? "" : plan.getClarificationReply());
            }
            ConversationExecutor executor = executorRegistry.get(plan.getExecutionMode());
            return executor.execute(taskInfo);
        })
                .publishOn(Schedulers.boundedElastic())
                .doOnNext(chunk -> emitModelChunk(taskInfo, chunk))
                .doOnError(error -> finalize(taskInfo, ChatTurnStatus.FAILED, errorMessage(error)))
                .doOnComplete(() -> finalize(taskInfo, ChatTurnStatus.COMPLETED, null))
                .doOnCancel(() -> finalize(taskInfo, ChatTurnStatus.STOPPED, "客户端已取消请求"));
    }

    private void emitModelChunk(ChatTaskInfo taskInfo, String chunk) {
        taskInfo.appendAnswer(chunk);
        if (taskInfo.getFirstTokenLatencyMs().get() == 0L) {
            taskInfo.getFirstTokenLatencyMs()
                    .compareAndSet(0L, System.currentTimeMillis() - taskInfo.getStartTime());
        }
        emit(taskInfo, eventWriter.text(chunk, taskInfo.getConversationId(), taskInfo.getTurnId()));
    }

    private void emitThinking(ChatTaskInfo taskInfo, String text) {
        taskInfo.getThinkingSteps().add(text);
        emit(taskInfo, eventWriter.thinking(text, taskInfo.getConversationId(), taskInfo.getTurnId()));
    }

    private void emit(ChatTaskInfo taskInfo, String payload) {
        SinkEmitHelper.emitNext(taskInfo.getSink(), payload);
    }

    // ======================== 收尾（单一 finalize，CAS 幂等）========================

    /**
     * 收尾一轮：CAS 幂等 → 落 turn → 落 FINALIZE trace → 释放租约 → 移除 registry → emit done/complete。
     *
     * <p>异常 warn 但不吞（关键路径，落 trace error），保证资源一定释放。</p>
     */
    private void finalize(ChatTaskInfo taskInfo, ChatTurnStatus status, String errorMessage) {
        if (!taskInfo.markFinalized()) {
            return;
        }
        ChatTraceRecorder recorder = taskInfo.getTraceRecorder();
        ChatTraceRecorder.StageHandle finalizeStage = recorder == null ? null
                : recorder.startStage(ChatTraceStageCode.FINALIZE, status.name(), "收尾落库", null);

        // 阶段 1：emit 终止事件
        try {
            if (status == ChatTurnStatus.FAILED && errorMessage != null) {
                emit(taskInfo, eventWriter.error(errorMessage, taskInfo.getConversationId(), taskInfo.getTurnId()));
            }
            emit(taskInfo, eventWriter.done(taskInfo.getConversationId(), taskInfo.getTurnId()));
            SinkEmitHelper.emitComplete(taskInfo.getSink());
        } catch (Exception e) {
            log.warn("收尾 emit 失败 → conversationId={} turnId={}",
                    taskInfo.getConversationId(), taskInfo.getTurnId(), e);
        }

        // 阶段 2：落 turn 行
        try {
            long totalLatency = System.currentTimeMillis() - taskInfo.getStartTime();
            TurnArchiveRecord patch = TurnArchiveRecord.builder()
                    .replyContent(taskInfo.getAnswerBuffer().toString())
                    .turnStatus(status.getCode())
                    .executionMode(resolveFinalExecutionMode(taskInfo))
                    .finishNote(status == ChatTurnStatus.FAILED ? errorMessage : "")
                    .sourceSnapshotList(jsonCodec.toJson(taskInfo.getReferences()))
                    .toolTraceList(jsonCodec.toJson(taskInfo.getThinkingSteps()))
                    .firstTokenLatencyMs(toNullable(taskInfo.getFirstTokenLatencyMs().get()))
                    .totalLatencyMs(totalLatency)
                    .build();
            archiveStore.completeTurn(taskInfo.getConversationId(), taskInfo.getTurnId(), patch);

            // 阶段：会话置回 IDLE
            archiveStore.saveConversation(ConversationArchiveRecord.builder()
                    .conversationId(taskInfo.getConversationId())
                    .sessionStatus(ChatSessionStatus.IDLE.getCode())
                    .build());

            if (recorder != null) {
                recorder.completeStage(finalizeStage, "收尾完成", java.util.Map.of(
                        "finalStatus", status.name(),
                        "answerLength", taskInfo.getAnswerBuffer().length()));
            }
        } catch (Exception e) {
            log.error("收尾落库失败 → conversationId={} turnId={}",
                    taskInfo.getConversationId(), taskInfo.getTurnId(), e);
            if (recorder != null) {
                recorder.failStage(finalizeStage, "收尾落库失败", e.getMessage(), null);
            }
        }

        // 阶段 3：释放资源（一定执行）
        cleanup(taskInfo);
    }

    private ChatStopVo doStop(ChatTaskInfo taskInfo, String reason) {
        if (!taskInfo.markFinalized()) {
            return ChatStopVo.builder()
                    .conversationId(taskInfo.getConversationId())
                    .stopped(false)
                    .message("会话已经结束")
                    .build();
        }
        log.info("停止会话 → conversationId={} reason={}", taskInfo.getConversationId(), reason);

        // 阶段：中断执行器订阅
        Disposable disposable = taskInfo.getDisposable();
        if (disposable != null && !disposable.isDisposed()) {
            disposable.dispose();
        }

        ChatTraceRecorder recorder = taskInfo.getTraceRecorder();
        ChatTraceRecorder.StageHandle finalizeStage = recorder == null ? null
                : recorder.startStage(ChatTraceStageCode.FINALIZE, ChatTurnStatus.STOPPED.name(), "停止收尾", null);

        try {
            emit(taskInfo, eventWriter.status("⏹ " + reason, taskInfo.getConversationId(), taskInfo.getTurnId()));
            emit(taskInfo, eventWriter.done(taskInfo.getConversationId(), taskInfo.getTurnId()));
            SinkEmitHelper.emitComplete(taskInfo.getSink());
        } catch (Exception e) {
            log.warn("停止事件 emit 失败 → conversationId={}", taskInfo.getConversationId(), e);
        }

        try {
            long totalLatency = System.currentTimeMillis() - taskInfo.getStartTime();
            archiveStore.completeTurn(taskInfo.getConversationId(), taskInfo.getTurnId(), TurnArchiveRecord.builder()
                    .replyContent(taskInfo.getAnswerBuffer().toString())
                    .turnStatus(ChatTurnStatus.STOPPED.getCode())
                    .executionMode(resolveFinalExecutionMode(taskInfo))
                    .finishNote(reason)
                    .firstTokenLatencyMs(toNullable(taskInfo.getFirstTokenLatencyMs().get()))
                    .totalLatencyMs(totalLatency)
                    .build());
            archiveStore.saveConversation(ConversationArchiveRecord.builder()
                    .conversationId(taskInfo.getConversationId())
                    .sessionStatus(ChatSessionStatus.IDLE.getCode())
                    .build());
            if (recorder != null) {
                recorder.completeStage(finalizeStage, "停止收尾完成", java.util.Map.of(
                        "finalStatus", ChatTurnStatus.STOPPED.name(), "reason", reason));
            }
        } catch (Exception e) {
            log.error("停止落库失败 → conversationId={}", taskInfo.getConversationId(), e);
            if (recorder != null) {
                recorder.failStage(finalizeStage, "停止落库失败", e.getMessage(), null);
            }
        }

        cleanup(taskInfo);
        return ChatStopVo.builder()
                .conversationId(taskInfo.getConversationId())
                .stopped(true)
                .message("已停止会话生成")
                .build();
    }

    private void cleanup(ChatTaskInfo taskInfo) {
        Disposable leaseRenewal = taskInfo.getLeaseRenewalDisposable();
        if (leaseRenewal != null && !leaseRenewal.isDisposed()) {
            leaseRenewal.dispose();
        }
        Disposable disposable = taskInfo.getDisposable();
        if (disposable != null && !disposable.isDisposed()) {
            disposable.dispose();
        }
        leaseService.release(new ChatLeaseService.LeaseClaim(
                taskInfo.getLeaseKey(), taskInfo.getLeaseOwnerToken(), java.time.Duration.ZERO));
        runtimeRegistry.remove(taskInfo.getConversationId(), taskInfo);
    }

    private Disposable startLeaseRenewal(ChatTaskInfo taskInfo) {
        return Flux.interval(leaseService.renewInterval())
                .subscribe(ignored -> {
                    if (!leaseService.renew(new ChatLeaseService.LeaseClaim(
                            taskInfo.getLeaseKey(), taskInfo.getLeaseOwnerToken(), java.time.Duration.ZERO))) {
                        log.warn("租约续期失败，停止生成 → conversationId={}", taskInfo.getConversationId());
                        doStop(taskInfo, "会话租约已失效，已停止生成");
                    }
                }, error -> log.warn("租约续期任务异常 → conversationId={}",
                        taskInfo.getConversationId(), error));
    }

    // ======================== 工具 ========================

    /** 落 turn 时取 executionMode：优先 plan，缺失回退 REACT_AGENT（保留 stub 兼容）。 */
    private Integer resolveFinalExecutionMode(ChatTaskInfo taskInfo) {
        ConversationExecutionPlan plan = taskInfo.getExecutionPlan();
        if (plan != null && plan.getExecutionMode() != null) {
            return plan.getExecutionMode().getCode();
        }
        return ExecutionMode.REACT_AGENT.getCode();
    }

    private Flux<String> errorFlux(String message, String conversationId) {
        return Flux.just(eventWriter.error(message, conversationId, null),
                eventWriter.done(conversationId, null));
    }

    private String errorMessage(Throwable error) {
        Throwable current = error;
        while (current != null) {
            if (current instanceof ChatException chatException) {
                return chatException.getMessage();
            }
            current = current.getCause();
        }
        String msg = error.getMessage();
        return msg != null && !msg.isBlank() ? msg : error.getClass().getSimpleName();
    }

    private Long toNullable(long value) {
        return value > 0 ? value : null;
    }

    private String generateConversationId() {
        return Long.toHexString(uidGenerator.getUid());
    }

    private String defaultTitle(ChatMode mode, String documentName) {
        String modeName = mode.getMsg();
        if (documentName != null && !documentName.isBlank()) {
            return documentName + " · " + modeName;
        }
        return modeName + " 会话";
    }

    private String formatCurrentDate(LocalDate date) {
        return date + "（" + chineseWeekday(date.getDayOfWeek()) + "）";
    }

    private String chineseWeekday(DayOfWeek dayOfWeek) {
        return switch (dayOfWeek) {
            case MONDAY -> "星期一";
            case TUESDAY -> "星期二";
            case WEDNESDAY -> "星期三";
            case THURSDAY -> "星期四";
            case FRIDAY -> "星期五";
            case SATURDAY -> "星期六";
            case SUNDAY -> "星期日";
        };
    }
}
