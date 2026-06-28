package com.reubenagent.chat.model.orchestrate;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * 检索计划 —— 导航决策中供证据检索使用的查询形态。
 *
 * <p>对标 super-agent {@code retrievalPlan}：包含改写后的检索主问题与可能拆分的子问题，
 * 供 {@link com.reubenagent.chat.orchestrate.ChatRagRetrievalAdapter} 按 plan 检索。</p>
 *
 * @author reuben
 * @since 2026-06-28
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ConversationRetrievalPlan {

    /** 改写后的检索主问题 */
    private String rewrittenQuery;

    /** 拆分的子问题列表，无拆分时为空集合 */
    @Builder.Default
    private List<String> subQuestions = new ArrayList<>();
}
