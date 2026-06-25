package com.reubenagent.chat.model.orchestrate;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * 查询改写结果 —— 由 {@code ChatQueryRewriteService} 产出。
 *
 * <p>既包含改写后的主问题，也包含可能拆分出的子问题。{@code usedRewrite} 标记是否实际走过 LLM 改写，
 * 便于观测与回放。子问题为空时按单问题处理。</p>
 *
 * @author reuben
 * @since 2026-06-25
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChatRewriteResult {

    /** 改写后的主问题（独立可答） */
    private String rewrittenQuery;

    /** 拆分出的子问题列表，无拆分时为空集合 */
    @Builder.Default
    private List<String> subQuestions = new ArrayList<>();

    /** 是否实际经过 LLM 改写（false 表示走规则 fallback 或未触发改写） */
    private boolean usedRewrite;

    /** 是否需要拆分（仅在 LLM 输出 should_split=true 时为 true） */
    private boolean shouldSplit;

    public boolean hasSubQuestions() {
        return subQuestions != null && !subQuestions.isEmpty();
    }
}
