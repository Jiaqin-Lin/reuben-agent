package com.reubenagent.document.support;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 知识路由分词器 —— 正则分隔 + 中文 n-gram 扩展。
 *
 * <p>纯函数工具类，分隔符从配置读取，避免硬编码。</p>
 *
 * @author reuben
 * @since 2026-06-28
 */
public final class KnowledgeRouteTokenizer {

    private KnowledgeRouteTokenizer() {}

    /**
     * 对文本分词：正则分隔 → 中文 n-gram 扩展（4+ 字词拆 2-6 gram）→ 去重截断。
     *
     * @param text      原始文本
     * @param delimiter 分隔符正则
     * @param minLength 最小词长度
     * @param maxCount  最大词数量
     * @return 分词列表
     */
    public static List<String> tokenize(String text, String delimiter, int minLength, int maxCount) {
        if (text == null || text.isBlank()) return List.of();

        String[] parts = text.split(delimiter);
        LinkedHashSet<String> result = new LinkedHashSet<>();
        Pattern delimiterPattern = Pattern.compile(delimiter);
        Pattern chinesePattern = Pattern.compile("[\\u4e00-\\u9fff]+");

        for (String part : parts) {
            String trimmed = part.trim();
            if (trimmed.length() < minLength) continue;
            if (hasNonChineseNonLetter(trimmed)) continue;
            result.add(trimmed);

            // 中文 n-gram 扩展：4+ 字词拆 2-6 gram
            if (chinesePattern.matcher(trimmed).matches() && trimmed.length() >= 4) {
                for (int len = 2; len <= Math.min(6, trimmed.length()); len++) {
                    for (int i = 0; i + len <= trimmed.length(); i++) {
                        String gram = trimmed.substring(i, i + len);
                        if (gram.length() >= minLength) {
                            result.add(gram);
                        }
                    }
                }
            }
        }

        List<String> list = new ArrayList<>(result);
        if (list.size() > maxCount) {
            return list.subList(0, maxCount);
        }
        return list;
    }

    private static boolean hasNonChineseNonLetter(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isLetter(c) || c >= 0x4e00 && c <= 0x9fff) continue;
            return true;
        }
        return false;
    }
}
