package com.reubenagent.chat.support;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 对话模块文本工具 —— 统一 clipText / safeText / toInt 等散落 5+ 类的字符串处理。
 *
 * <p>修正 super-agent 问题 15：消除 {@code clipText}/{@code safeText}/{@code toInt} 在多个类重复。</p>
 *
 * @author reuben
 * @since 2026-06-25
 */
@Slf4j
@Component
public final class ChatTexts {

    private ChatTexts() {
    }

    /**
     * 按字符上限裁剪文本，超出追加省略号。
     *
     * @param text     原文，null 返回空串
     * @param maxChars 最大字符数，<=0 视为不裁剪直接返回原文
     */
    public static String clip(String text, int maxChars) {
        if (text == null) {
            return "";
        }
        if (maxChars <= 0 || text.length() <= maxChars) {
            return text;
        }
        return text.substring(0, maxChars) + "…";
    }

    /**
     * 安全取文本：null 返回空串。
     */
    public static String safe(String text) {
        return text == null ? "" : text;
    }

    /**
     * 安全转整数：null / 非数字 / 空串返回 {@code defaultValue}。
     */
    public static int toInt(String value, int defaultValue) {
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    /**
     * 安全转 long：null / 非数字返回 {@code defaultValue}。
     */
    public static long toLong(String value, long defaultValue) {
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    /** 折叠多余空白为单空格。 */
    public static String collapseWhitespace(String text) {
        if (text == null) {
            return "";
        }
        return text.replaceAll("\\s+", " ").trim();
    }
}
