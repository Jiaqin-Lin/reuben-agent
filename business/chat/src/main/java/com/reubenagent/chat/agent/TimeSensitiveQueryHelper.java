package com.reubenagent.chat.agent;

import com.reubenagent.chat.config.ChatProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;

/**
 * 时间敏感查询识别 —— 纯函数，无 Spring 依赖，无 IO。
 *
 * <p>对外置关键词列表（{@link ChatProperties.Tavily} 未直接持有关键词，本类内置常量集合
 * 与可配 {@code timeSensitiveKeywords}，未来若需 hot-reload 可升级到 properties）。
 * 命中任一关键词 → 视为时间敏感，需触发联网搜索并在 prompt 注入当前日期。</p>
 *
 * <p>对标 super-agent {@code TimeSensitiveQueryHelper}，reuben 修正：
 * <ul>
 *   <li>关键词列表外置为常量集合，不散落 if/else；</li>
 *   <li>{@code isTimeSensitive} 纯函数（输入字符串 + 当前日期 → bool），可单元测试；</li>
 *   <li>不在此处拼接 prompt 文本，仅返回 bool，prompt 拼装由调用方负责。</li>
 * </ul></p>
 *
 * @author reuben
 * @since 2026-06-25
 */
@Slf4j
@Component
public class TimeSensitiveQueryHelper {

    /** 时间敏感关键词集合（小写匹配）。 */
    private static final List<String> TIME_SENSITIVE_KEYWORDS = List.of(
            "今天", "今日", "昨天", "明日", "明天", "现在", "目前", "当前", "最新", "最近",
            "本周", "本周内", "本星期", "上周", "下周", "本月", "上个月", "下个月", "本月内",
            "今年", "去年", "明年", "近期", "刚刚", "刚刚发布", "刚刚发生", "实时",
            "today", "yesterday", "tomorrow", "now", "latest", "recent", "this week",
            "this month", "this year", "last week", "last month", "last year"
    );

    /**
     * 判断查询是否时间敏感。
     *
     * <p>纯函数：相同 query + 相同 currentDate 返回相同结果，不读外部状态。</p>
     *
     * @param query       用户原始问题（null 视为不敏感）
     * @param currentDate 当前日期（保留参数以备未来按日期范围扩展，当前未使用）
     */
    public boolean isTimeSensitive(String query, LocalDate currentDate) {
        if (query == null || query.isBlank()) {
            return false;
        }
        String lower = query.toLowerCase();
        for (String keyword : TIME_SENSITIVE_KEYWORDS) {
            if (lower.contains(keyword.toLowerCase())) {
                return true;
            }
        }
        return false;
    }

    /** 暴露关键词集合供调用方调试 / 测试断言。 */
    public List<String> keywords() {
        return TIME_SENSITIVE_KEYWORDS;
    }
}
