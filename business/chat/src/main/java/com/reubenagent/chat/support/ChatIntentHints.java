package com.reubenagent.chat.support;

import java.util.Set;

/**
 * 对话意图关键词集合 —— 用于 {@code ChatPreparationOrchestrator} 在 OPEN_CHAT / 能力问 / 闲聊 之间快速分流。
 *
 * <p>外置到常量类而非散落方法体内，便于检索 / 替换。</p>
 *
 * @author reuben
 * @since 2026-06-25
 */
public final class ChatIntentHints {

    /** 能力问 / 闲聊关键词：命中时倾向走 ReAct Agent（不强制走文档检索） */
    public static final Set<String> OPEN_CHAT_HINTS = Set.of(
            "你是谁", "你能做什么", "帮你做什么", "功能", "介绍你自己",
            "hello", "hi", "你好", "谢谢", "感谢", "再见", "bye",
            "闲聊", "聊聊天", "讲个笑话"
    );

    /** 能力问关键词：命中时倾向 ReAct Agent + 联网 */
    public static final Set<String> CAPABILITY_HINTS = Set.of(
            "最新", "今天", "昨天", "近期", "现在", "目前",
            "新闻", "热点", "天气", "汇率", "股价", "实时"
    );

    /** 文档导向关键词：命中时倾向走 RAG 检索 */
    public static final Set<String> DOCUMENT_HINTS = Set.of(
            "文档", "手册", "章节", "第几节", "在哪一页", "根据文档",
            "说明", "规范", "条例"
    );

    private ChatIntentHints() {
    }

    public static boolean matchesOpenChat(String question) {
        if (question == null || question.isBlank()) {
            return false;
        }
        String lower = question.toLowerCase();
        return OPEN_CHAT_HINTS.stream().anyMatch(lower::contains)
                || CAPABILITY_HINTS.stream().anyMatch(lower::contains);
    }

    public static boolean matchesDocument(String question) {
        if (question == null || question.isBlank()) {
            return false;
        }
        return DOCUMENT_HINTS.stream().anyMatch(question::contains);
    }
}
