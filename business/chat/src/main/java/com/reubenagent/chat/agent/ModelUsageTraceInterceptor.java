package com.reubenagent.chat.agent;

import com.alibaba.cloud.ai.graph.agent.interceptor.ModelCallHandler;
import com.alibaba.cloud.ai.graph.agent.interceptor.ModelInterceptor;
import com.alibaba.cloud.ai.graph.agent.interceptor.ModelRequest;
import com.alibaba.cloud.ai.graph.agent.interceptor.ModelResponse;
import com.reubenagent.chat.config.ChatProperties;
import com.reubenagent.chat.enums.ChatModelCallStatus;
import com.reubenagent.chat.model.debug.ChatModelUsageTrace;
import com.reubenagent.chat.support.ChatContextKeys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.function.Consumer;

/**
 * ReAct Agent 模型调用追踪拦截器 —— hook Alibaba ReactAgent 内部 ChatModel 调用，
 * 把 token / 成本 / 耗时落进 per-turn {@link ChatModelUsageTrace}（修正 Phase 9.4 遗留项）。
 *
 * <p>super-agent 的 ReAct 路径 model 调用不经过 {@code ObservedChatModelService}，token 追踪丢失。
 * 本拦截器注册为 {@link ModelInterceptor}，在 {@code AgentLlmNode} 的模型调用链外层包裹：
 * <ul>
 *   <li>调用前记录起始时间 + 从 {@link ModelRequest#getContext()} 取 traceSink（由
 *       {@code ReactAgentExecutor.buildRunnableConfig} 写入 metadata，Alibaba 透传到 ModelRequest.context）；</li>
 *   <li>调用后从 {@link ModelResponse#getChatResponse()} 取 metadata.usage 估算成本，落 trace。</li>
 * </ul></p>
 *
 * <p>拦截器为无状态单例，per-turn 的 sink 通过 RunnableConfig.metadata → ModelRequest.context 传递，
 * 不在拦截器内持有任何会话态。流式场景 ChatResponse 为 null（flux 形态），仅记录耗时与状态，
 * token 在 Alibaba 流式聚合后无法在此处拿到 —— 登记为已知限制，非流式路径完整。</p>
 *
 * @author reuben
 * @since 2026-06-27
 */
@Slf4j
@Component
public class ModelUsageTraceInterceptor extends ModelInterceptor {

    private static final String STAGE_NAME = "REACT_AGENT";
    private static final String PROVIDER = "deepseek";

    private final ChatProperties properties;

    public ModelUsageTraceInterceptor(ChatProperties properties) {
        this.properties = properties;
    }

    @Override
    public String getName() {
        return "chat-model-usage-trace";
    }

    @Override
    @SuppressWarnings("unchecked")
    public ModelResponse interceptModel(ModelRequest request, ModelCallHandler handler) {
        long start = System.currentTimeMillis();
        Consumer<ChatModelUsageTrace> traceSink = resolveSink(request);
        try {
            ModelResponse response = handler.call(request);
            long duration = System.currentTimeMillis() - start;
            recordTrace(traceSink, response, duration, ChatModelCallStatus.COMPLETED, null);
            return response;
        } catch (RuntimeException e) {
            long duration = System.currentTimeMillis() - start;
            recordTrace(traceSink, null, duration, ChatModelCallStatus.FAILED, e.getMessage());
            throw e;
        }
    }

    @SuppressWarnings("unchecked")
    private Consumer<ChatModelUsageTrace> resolveSink(ModelRequest request) {
        if (request == null || request.getContext() == null) {
            return null;
        }
        Object sink = request.getContext().get(ChatContextKeys.MODEL_USAGE_TRACE_SINK);
        return sink instanceof Consumer ? (Consumer<ChatModelUsageTrace>) sink : null;
    }

    private void recordTrace(Consumer<ChatModelUsageTrace> traceSink, ModelResponse response,
                             long durationMs, ChatModelCallStatus status, String errorMessage) {
        if (traceSink == null) {
            return;
        }
        try {
            String model = null;
            Integer promptTokens = null;
            Integer completionTokens = null;
            Integer totalTokens = null;
            ChatResponse chatResponse = response == null ? null : response.getChatResponse();
            if (chatResponse != null) {
                ChatResponseMetadata metadata = chatResponse.getMetadata();
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
                    .stageName(STAGE_NAME)
                    .provider(PROVIDER)
                    .model(model)
                    .promptTokens(promptTokens)
                    .completionTokens(completionTokens)
                    .totalTokens(totalTokens)
                    .estimatedCost(estimateCost(model, totalTokens))
                    .durationMs(durationMs)
                    .status(status)
                    .errorMessage(errorMessage)
                    .build();
            traceSink.accept(trace);
        } catch (Exception e) {
            log.debug("ReAct model-usage trace 记录失败（可忽略） → err={}", e.getMessage());
        }
    }

    /** 按 model 名查单价估算成本，未配置返回 null（与 ObservedChatModelService 一致）。 */
    private Double estimateCost(String model, Integer totalTokens) {
        if (model == null || totalTokens == null || totalTokens <= 0) {
            return null;
        }
        Map<String, Double> perKTokenCost = properties.getPricing().getPerKTokenCost();
        if (perKTokenCost == null || perKTokenCost.isEmpty()) {
            return null;
        }
        Double unit = perKTokenCost.get(model);
        if (unit == null) {
            for (Map.Entry<String, Double> entry : perKTokenCost.entrySet()) {
                if (model.startsWith(entry.getKey())) {
                    unit = entry.getValue();
                    break;
                }
            }
        }
        return unit == null ? null : unit * totalTokens / 1000.0;
    }
}
