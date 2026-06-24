package com.reubenagent.chat.support;

/**
 * 对话模块 Prompt 模板名常量 —— 与 {@code classpath:prompt/*.st} 一一对应。
 *
 * <p>集中管理模板名，避免散落的魔法字符串。</p>
 *
 * @author reuben
 * @since 2026-06-24
 */
public final class ChatPromptNames {

    public static final String CHAT_QUERY_REWRITE = "chat-query-rewrite";
    public static final String CONVERSATION_SUMMARY_SYSTEM = "conversation-summary-system";
    public static final String CONVERSATION_SUMMARY_MERGE = "conversation-summary-merge";
    public static final String RAG_ANSWER_SYSTEM = "rag-answer-system";
    public static final String RAG_ANSWER_USER = "rag-answer-user";
    public static final String AGENT_QUESTION = "agent-question";
    public static final String RECOMMENDATION_USER = "recommendation-user";
    public static final String CLARIFICATION = "clarification";

    private ChatPromptNames() {
    }
}
