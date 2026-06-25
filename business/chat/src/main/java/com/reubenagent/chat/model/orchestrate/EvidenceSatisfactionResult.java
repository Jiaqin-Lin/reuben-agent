package com.reubenagent.chat.model.orchestrate;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 证据满足度评估结果 —— Phase 6 RAG executor 检索后判断证据是否充分回答问题。
 *
 * <p>本期 Phase 5 仅占位定义，{@code RagAnswerExecutor} 在 Phase 6 接入填充。</p>
 *
 * @author reuben
 * @since 2026-06-25
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EvidenceSatisfactionResult {

    /** 证据是否充分 */
    private boolean satisfied;

    /** 命中证据数量 */
    private int hitCount;

    /** 最高证据分数 */
    private double topScore;

    /** 不满足时的拒答理由（落 trace） */
    private String rejectReason;
}
