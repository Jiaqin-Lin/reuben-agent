package com.reubenagent.chat.service.impl;

import com.reubenagent.chat.config.ChatProperties;
import com.reubenagent.chat.enums.ChatErrorCode;
import com.reubenagent.chat.enums.ChatModelCallStatus;
import com.reubenagent.chat.exception.ChatException;
import com.reubenagent.chat.model.debug.ChatModelUsageTrace;
import com.reubenagent.chat.service.ObservedChatModelService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.Map;
import java.util.function.Consumer;

/**
 * {@link ObservedChatModelService} 默认实现 —— 包装 DeepSeek {@link ChatModel}，统一追踪每次调用。
 *
 * <p>provider 解析：主 ChatModel 一次性取实现类简名缓存；成本查 {@link ChatProperties.Pricing}，
 * 未配置返回 null。</p>
 *
 * <p><b>Phase 8 model-usage 作用域修正</b>：删除 service 级共享 {@code usageTraces} list，
 * 改用 {@code Consumer<ChatModelUsageTrace>} 回调参数，由 {@code ChatTraceRecorder.traceSink()}
 * 提供 per-turn 隔离的 sink。旧 {@code recordUsageTrace} 方法保留为 no-op 兼容签名。</p>
 *
 * @author reuben
 * @since 2026-06-24
 */
@Slf4j
@Service
public class ObservedChatModelServiceImpl implements ObservedChatModelService {

    private final ChatModel primaryModel;
    private final ObjectProvider<ChatModel> alternateModels;
    private final ChatProperties properties;

    /** 主模型厂商名，一次解析缓存（如 DeepSeekChatModel → deepseek） */
    private final String primaryProvider;
    /** 主模型默认 model 名（取自 defaultOptions，可能为 null） */
    private final String primaryDefaultModel;

    public ObservedChatModelServiceImpl(@Qualifier("deepSeekChatModel") ChatModel primaryModel,
                                        ObjectProvider<ChatModel> alternateModels,
                                        ChatProperties properties) {
        this.primaryModel = primaryModel;
        this.alternateModels = alternateModels;
        this.properties = properties;
        this.primaryProvider = resolveProvider(primaryModel);
        this.primaryDefaultModel = resolveDefaultModel(primaryModel);
        log.info("ObservedChatModelService 初始化 → provider={} defaultModel={}",
                primaryProvider, primaryDefaultModel);
    }

    @Override
    public String callText(String stageName, String prompt, ChatOptions options) {
        return callText(stageName, prompt, options, null);
    }

    @Override
    public String callText(String stageName, String prompt, ChatOptions options,
                           Consumer<ChatModelUsageTrace> traceSink) {
        long start = System.currentTimeMillis();
        Prompt aiPrompt = buildPrompt(prompt, options);
        try {
            ChatResponse response = primaryModel.call(aiPrompt);
            String text = extractText(response);
            recordTrace(stageName, response, System.currentTimeMillis() - start,
                    ChatModelCallStatus.COMPLETED, null, traceSink);
            return text;
        } catch (ChatException e) {
            recordTrace(stageName, null, System.currentTimeMillis() - start,
                    ChatModelCallStatus.FAILED, e.getMessage(), traceSink);
            throw e;
        } catch (Exception e) {
            long duration = System.currentTimeMillis() - start;
            recordTrace(stageName, null, duration, ChatModelCallStatus.FAILED, e.getMessage(), traceSink);
            log.warn("模型阻塞调用失败 → stage={} duration={}ms err={}", stageName, duration, e.getMessage());
            throw new ChatException(ChatErrorCode.MODEL_CALL_FAILED, stageName, e);
        }
    }

    @Override
    public Flux<String> streamText(String stageName, String prompt, ChatOptions options) {
        return streamText(stageName, prompt, options, null);
    }

    @Override
    public Flux<String> streamText(String stageName, String prompt, ChatOptions options,
                                   Consumer<ChatModelUsageTrace> traceSink) {
        long start = System.currentTimeMillis();
        Prompt aiPrompt = buildPrompt(prompt, options);
        // 阶段：用 volatile 标志保证 complete/error/cancel 三种终态只落一次 trace
        java.util.concurrent.atomic.AtomicBoolean traced = new java.util.concurrent.atomic.AtomicBoolean(false);
        return primaryModel.stream(aiPrompt)
                .map(this::extractText)
                .doOnError(error -> {
                    if (traced.compareAndSet(false, true)) {
                        recordTrace(stageName, null, System.currentTimeMillis() - start,
                                ChatModelCallStatus.FAILED, error.getMessage(), traceSink);
                    }
                    log.warn("模型流式调用失败 → stage={} err={}", stageName, error.getMessage());
                })
                .doOnComplete(() -> {
                    if (traced.compareAndSet(false, true)) {
                        recordTrace(stageName, null, System.currentTimeMillis() - start,
                                ChatModelCallStatus.COMPLETED, null, traceSink);
                    }
                })
                .doFinally(signal -> {
                    // cancel 信号兜底：complete/error 已记录，cancel 单独落 FAILED
                    if (signal == reactor.core.publisher.SignalType.CANCEL
                            && traced.compareAndSet(false, true)) {
                        recordTrace(stageName, null, System.currentTimeMillis() - start,
                                ChatModelCallStatus.FAILED, "客户端取消流式调用", traceSink);
                    }
                })
                .onErrorResume(error -> Flux.error(
                        new ChatException(ChatErrorCode.MODEL_CALL_FAILED, stageName, error)));
    }

    // ======================== 内部方法 ========================

    private Prompt buildPrompt(String prompt, ChatOptions options) {
        if (options != null) {
            return new Prompt(new UserMessage(prompt), options);
        }
        return new Prompt(new UserMessage(prompt));
    }

    private String extractText(ChatResponse response) {
        if (response == null || response.getResult() == null
                || response.getResult().getOutput() == null) {
            return "";
        }
        String text = response.getResult().getOutput().getText();
        return text != null ? text : "";
    }

    /** 落一条模型调用追踪。response 可为 null（失败场景）。traceSink 可为 null（不回调）。 */
    private void recordTrace(String stageName, ChatResponse response, long durationMs,
                             ChatModelCallStatus status, String errorMessage,
                             Consumer<ChatModelUsageTrace> traceSink) {
        try {
            String model = primaryDefaultModel;
            Integer promptTokens = null;
            Integer completionTokens = null;
            Integer totalTokens = null;
            if (response != null) {
                ChatResponseMetadata metadata = response.getMetadata();
                if (metadata != null) {
                    if (metadata.getModel() != null) {
                        model = metadata.getModel();
                    }
                    Usage usage = metadata.getUsage();
                    if (usage != null) {
                        promptTokens = usage.getPromptTokens();
                        completionTokens = usage.getCompletionTokens();
                        totalTokens = usage.getTotalTokens();
                    }
                }
            }
            ChatModelUsageTrace trace = ChatModelUsageTrace.builder()
                    .stageName(stageName)
                    .provider(primaryProvider)
                    .model(model)
                    .promptTokens(promptTokens)
                    .completionTokens(completionTokens)
                    .totalTokens(totalTokens)
                    .estimatedCost(estimateCost(model, totalTokens))
                    .durationMs(durationMs)
                    .status(status)
                    .errorMessage(errorMessage)
                    .build();
            if (traceSink != null) {
                traceSink.accept(trace);
            }
        } catch (Exception e) {
            // 阶段：追踪落库失败不中断主流程
            log.warn("模型调用追踪记录失败 → stage={} err={}", stageName, e.getMessage());
        }
    }

    /** 按 model 名查单价估算成本，未配置返回 null（不硬编码厂商价格）。 */
    private Double estimateCost(String model, Integer totalTokens) {
        if (model == null || totalTokens == null || totalTokens <= 0) {
            return null;
        }
        Map<String, Double> perKTokenCost = properties.getPricing().getPerKTokenCost();
        if (perKTokenCost == null || perKTokenCost.isEmpty()) {
            return null;
        }
        // 阶段：精确匹配优先，否则按前缀（如 deepseek-v4-flash 匹配 deepseek）
        Double unit = perKTokenCost.get(model);
        if (unit == null) {
            for (Map.Entry<String, Double> entry : perKTokenCost.entrySet()) {
                if (model.startsWith(entry.getKey())) {
                    unit = entry.getValue();
                    break;
                }
            }
        }
        if (unit == null) {
            return null;
        }
        return unit * totalTokens / 1000.0;
    }

    /** 解析 provider 名：实现类简名小写去 ChatModel/ChatModel 后缀。 */
    private static String resolveProvider(ChatModel model) {
        if (model == null) {
            return "unknown";
        }
        String name = model.getClass().getSimpleName();
        name = name.replaceFirst("ChatModel$", "").replaceFirst("Model$", "");
        return name.isEmpty() ? "unknown" : name.toLowerCase();
    }

    /** 从默认 options 取 model 名。 */
    private static String resolveDefaultModel(ChatModel model) {
        if (model == null) {
            return null;
        }
        try {
            ChatOptions defaultOptions = model.getDefaultOptions();
            return defaultOptions == null ? null : defaultOptions.getModel();
        } catch (Exception e) {
            return null;
        }
    }
}
