package com.reubenagent.chat.model.debug;

import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

/**
 * Agent 调用限额统计 —— 防止 ReAct loop 失控，对齐前端 observability 展示。
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
    private int modelCallsUsed;
    /** 单轮模型调用上限 */
    private int modelCallsRunLimit;
    /** 线程级模型调用上限 */
    private int modelCallsThreadLimit;
    /** 本轮已发生工具调用数 */
    private int toolCallsUsed;
    /** 单轮工具调用上限 */
    private int toolCallsRunLimit;
    /** 线程级工具调用上限 */
    private int toolCallsThreadLimit;
    /** 是否触发了限制 */
    private boolean limitTriggered;
    /** 触发限制的原因 */
    private String limitReason;

    public boolean modelLimitExceeded() {
        return modelCallsRunLimit > 0 && modelCallsUsed >= modelCallsRunLimit;
    }

    public boolean toolLimitExceeded() {
        return toolCallsRunLimit > 0 && toolCallsUsed >= toolCallsRunLimit;
    }
}
