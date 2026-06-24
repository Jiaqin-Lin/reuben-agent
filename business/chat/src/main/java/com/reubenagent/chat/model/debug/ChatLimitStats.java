package com.reubenagent.chat.model.debug;

import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

/**
 * Agent 调用限额统计 —— 防止 ReAct loop 失控。
 *
 * <p>阈值来自 {@link com.reubenagent.chat.config.ChatProperties.Agent}，单轮 / 单线程各自计数。</p>
 *
 * @author reuben
 * @since 2026-06-24
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChatLimitStats {

    /** 本轮已发生模型调用数 */
    private int modelCalls;
    /** 本轮模型调用上限 */
    private int modelCallsLimit;
    /** 本轮已发生工具调用数 */
    private int toolCalls;
    /** 本轮工具调用上限 */
    private int toolCallsLimit;

    public boolean modelLimitExceeded() {
        return modelCallsLimit > 0 && modelCalls >= modelCallsLimit;
    }

    public boolean toolLimitExceeded() {
        return toolCallsLimit > 0 && toolCalls >= toolCallsLimit;
    }
}
