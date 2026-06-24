package com.reubenagent.chat.model.debug;

import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * 单轮对话的全量调试追踪聚合 —— 贯穿改写 / Agent / 检索 / 工具 / 模型调用 / 限额统计 / 历史。
 *
 * <p>Phase 3 起逐步填充：先由 {@code ObservedChatModelService} 写入 {@link ChatModelUsageTrace}，
 * 后续 Phase 4/5/6/7 补 rewriteQuestion / retrievalResults / toolTraces / historySummary。
 * 最终在 {@code ChatStreamOrchestrator.finalize} 序列化为 turn 行的 {@code debug_trace_json}。</p>
 *
 * @author reuben
 * @since 2026-06-24
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChatDebugTrace {

    /** 改写后的主问题（无改写则为原始问题） */
    private String rewriteQuestion;
    /** 透传给 Agent 的提问（含时效注入） */
    private String agentQuestion;
    /** 检索结果追踪（Phase 6 填充） */
    @Builder.Default
    private List<Object> retrievalResults = new ArrayList<>();
    /** 工具调用追踪（Phase 7 填充） */
    @Builder.Default
    private List<ChatToolTrace> toolTraces = new ArrayList<>();
    /** 模型调用追踪（token / 成本 / 耗时） */
    @Builder.Default
    private List<ChatModelUsageTrace> modelUsageTraces = new ArrayList<>();
    /** 调用限额统计（防止 Agent 失控） */
    private ChatLimitStats limitStats;
    /** 命中的历史摘要（Phase 4 填充） */
    private String historySummary;
    /** 检索备注 / 拒答原因等 */
    @Builder.Default
    private List<String> retrievalNotes = new ArrayList<>();
}
