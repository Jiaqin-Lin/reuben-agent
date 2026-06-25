package com.reubenagent.chat.model.memory;

import com.alibaba.fastjson.annotation.JSONField;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * 会话长期记忆结构化摘要 —— 由 LLM 合并历史摘要 + 新增批次产生。
 *
 * <p>字段语义对齐 super-agent，落 {@code reuben_agent_chat_memory_summary.summary_json}。
 * {@link JSONField} 把 Java 驼峰映射为下划线，与 prompt 模板
 * {@code conversation-summary-merge.st} 输出结构一致，便于 LLM 输出直接反序列化。</p>
 *
 * @author reuben
 * @since 2026-06-25
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChatSummaryPayload {

    /** 一段中文摘要（180~260 字） */
    private String summary;

    /** 一句话描述用户长期目标 */
    @JSONField(name = "conversation_goal")
    private String conversationGoal;

    /** 已确认的业务事实 / 术语 / 系统名 */
    @Builder.Default
    @JSONField(name = "stable_facts")
    private List<String> stableFacts = new ArrayList<>();

    /** 用户偏好 */
    @Builder.Default
    @JSONField(name = "user_preferences")
    private List<String> userPreferences = new ArrayList<>();

    /** 已解决结论 */
    @Builder.Default
    @JSONField(name = "resolved_points")
    private List<String> resolvedPoints = new ArrayList<>();

    /** 仍待跟进的问题 */
    @Builder.Default
    @JSONField(name = "pending_questions")
    private List<String> pendingQuestions = new ArrayList<>();

    /** 后续检索关键词 hint */
    @Builder.Default
    @JSONField(name = "retrieval_hints")
    private List<String> retrievalHints = new ArrayList<>();
}
