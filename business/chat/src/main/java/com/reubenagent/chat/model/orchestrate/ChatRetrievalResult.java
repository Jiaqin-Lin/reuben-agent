package com.reubenagent.chat.model.orchestrate;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * 单子问题检索聚合结果 —— 由 {@code ChatRagRetrievalAdapter} 产出。
 *
 * <p>每个子问题对应一次 {@code IRagRetrievalService.retrieve} 调用，{@link #results}
 * 是经过门控（分数阈值 + 数量 budget）后保留的命中文档块。{@link #allReferences}
 * 是已映射为 {@link com.reubenagent.chat.model.SearchReference} 的引用列表，
 * 供 {@code RagAnswerExecutor} 在生成完成后 emit reference 事件。</p>
 *
 * <p>该对象不持有任何执行态（disposable / sink），仅是 adapter → executor 的数据载体。</p>
 *
 * @author reuben
 * @since 2026-06-25
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChatRetrievalResult {

    /** 子问题序号（0 表示主问题 / 无拆分），用于落 trace 与引用编号映射 */
    @Builder.Default
    private Integer subQuestionIndex = 0;

    /** 子问题文本（与检索 query 一致，可为改写后主问题） */
    private String subQuestion;

    /** 检索 query（已带入 filterFields） */
    private String query;

    /** 该子问题检索总耗时（ms） */
    private long totalCostMs;

    /** 命中结果（已门控），按分数降序 */
    @Builder.Default
    private List<com.reubenagent.rag.model.RetrievalResult> results = new ArrayList<>();

    /** 已映射的引用列表（用于 SSE reference 事件） */
    @Builder.Default
    private List<com.reubenagent.chat.model.SearchReference> references = new ArrayList<>();

    /** 是否命中至少一条证据 */
    public boolean hasEvidence() {
        return results != null && !results.isEmpty();
    }
}
