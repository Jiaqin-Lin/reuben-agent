package com.reubenagent.chat.service;

import com.reubenagent.chat.model.debug.ChatModelUsageTrace;
import org.springframework.ai.chat.prompt.ChatOptions;
import reactor.core.publisher.Flux;

/**
 * 带全链路观测的 LLM 调用封装 —— 统一 token / 成本 / 耗时追踪入口。
 *
 * <p>对标 super-agent {@code ObservedChatModelService}，修正：
 * <ul>
 *   <li>provider 不用字符串匹配类名，由注入的 ChatModel 实现类名一次解析并缓存；</li>
 *   <li>状态用 {@link com.reubenagent.chat.enums.ChatModelCallStatus} 枚举替代字符串字面量；</li>
 *   <li>{@code streamText} 在 {@code doFinally}（覆盖 cancel/complete/error）落 trace，修正 cancel 丢 trace；</li>
 *   <li>成本按 {@link com.reubenagent.chat.config.ChatProperties.Pricing} 查表，未配置返回 null，不硬编码厂商价格。</li>
 * </ul></p>
 *
 * @author reuben
 * @since 2026-06-24
 */
public interface ObservedChatModelService {

    /**
     * 阻塞式文本调用。
     *
     * @param stageName 调用阶段名（用于追踪归类）
     * @param prompt    已组装好的提示文本
     * @param options   可选模型参数（temperature / model 覆盖等），可为 null
     * @return 模型输出文本
     */
    String callText(String stageName, String prompt, ChatOptions options);

    /**
     * 流式文本调用，逐块产出文本。
     *
     * <p>trace 在 {@code doFinally} 中落库（覆盖 complete / error / cancel）。</p>
     */
    Flux<String> streamText(String stageName, String prompt, ChatOptions options);

    /** 追加一条模型调用追踪（供 Agent 多次调用累计）。 */
    void recordUsageTrace(ChatModelUsageTrace trace);
}
