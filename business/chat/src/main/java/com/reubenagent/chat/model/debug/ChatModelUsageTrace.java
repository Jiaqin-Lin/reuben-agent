package com.reubenagent.chat.model.debug;

import com.reubenagent.chat.enums.ChatModelCallStatus;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

/**
 * 单次模型调用的用量 / 成本 / 耗时追踪。
 *
 * <p>状态用 {@link ChatModelCallStatus} 枚举（替代 super-agent 的字符串字面量 "COMPLETED"/"FAILED"）。
 * 由 {@code ObservedChatModelService} 在 {@code callText} / {@code streamText} 的 {@code doFinally}
 * 中填充并落入 {@link ChatDebugTrace}。</p>
 *
 * @author reuben
 * @since 2026-06-24
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChatModelUsageTrace {

    /** 调用所在阶段名（REWRITE / ANSWER_GENERATE / RECOMMENDATION ...） */
    private String stageName;
    /** 厂商（DeepSeek / OpenAI ...），由注入的 ChatModel 实现类名解析 */
    private String provider;
    /** 实际模型名（取自 ChatResponseMetadata.model） */
    private String model;
    /** 提示 token */
    private Integer promptTokens;
    /** 生成 token */
    private Integer completionTokens;
    /** 总 token */
    private Integer totalTokens;
    /** 估算成本（美元），未配置单价时为 null */
    private Double estimatedCost;
    /** 调用耗时毫秒 */
    private Long durationMs;
    /** 调用状态 */
    private ChatModelCallStatus status;
    /** 失败时的错误信息 */
    private String errorMessage;
}
