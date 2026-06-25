package com.reubenagent.chat.model.orchestrate;

import com.reubenagent.chat.enums.ChatMode;
import com.reubenagent.chat.enums.ExecutionMode;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * 单轮对话执行计划 —— 由 {@code ChatPreparationOrchestrator.prepare} 产出，
 * 指导 {@code ConversationExecutor} 如何执行。
 *
 * <p>对标 super-agent {@code ConversationExecutionPlan}，reuben 简化字段：
 * <ul>
 *   <li>只保留执行必需信息：执行模式、改写后问题、子问题、选定文档、澄清回复、导航决策、记忆上下文锚点；</li>
 *   <li>不再承载 retrievalQuestion / agentQuestion 等冗余字段（统一用 rewrittenQuery）；</li>
 *   <li>不携带可变执行态（answer / 引用），那些在 {@code ChatTaskInfo}。</li>
 * </ul></p>
 *
 * @author reuben
 * @since 2026-06-25
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ConversationExecutionPlan {

    /** 执行模式（决定 executor 分发） */
    private ExecutionMode executionMode;

    /** 对话模式 */
    private ChatMode chatMode;

    /** 原始用户问题 */
    private String originalQuestion;

    /** 改写后主问题 */
    private String rewrittenQuery;

    /** 改写拆分出的子问题 */
    @Builder.Default
    private List<String> subQuestions = new ArrayList<>();

    /** 改写结果元数据 */
    private ChatRewriteResult rewriteResult;

    /** 选定的目标文档ID（DOCUMENT / AUTO_DOCUMENT 模式） */
    private Long selectedDocumentId;

    /** 选定的目标文档名 */
    private String selectedDocumentName;

    /** 知识路由候选文档评分（top1 score），用于 trace 与澄清判断 */
    private Double routeTopScore;

    /** 知识路由候选文档评分（top2 score） */
    private Double routeSecondScore;

    /** 路由置信度（0~1） */
    private Double routeConfidence;

    /** 导航决策（DOCUMENT/AUTO_DOCUMENT 模式） */
    private DocumentNavigationDecision navigationDecision;

    /** 澄清回复文案（CLARIFICATION 模式） */
    private String clarificationReply;

    /** 澄清选项 */
    @Builder.Default
    private List<String> clarificationOptions = new ArrayList<>();

    /** 澄清原因（落 trace） */
    private String clarificationReason;

    /** 无证据命中时的拒答文案（Phase 6 RAG executor 使用） */
    private String noEvidenceReply;

    /** 长期摘要（注入 prompt 用） */
    private String longTermSummary;

    /** 历史 transcript（注入 prompt 用） */
    private String recentTranscript;

    public boolean isClarification() {
        return executionMode == ExecutionMode.CLARIFICATION;
    }
}
