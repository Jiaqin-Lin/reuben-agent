package com.reubenagent.chat.agent;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Tavily 联网搜索工具返回结果。
 *
 * <p>扁平化为给 LLM 看的"标题 + url + 摘要 + 发布日期"结构；raw JSON 不直接回传模型，
 * 由工具内裁剪到单条 800 字符以内避免 token 暴涨。</p>
 *
 * @author reuben
 * @since 2026-06-25
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TavilySearchToolResult {

    /** 是否成功（HTTP 2xx 且解析无异常） */
    private boolean success;

    /** 错误信息（失败时填） */
    private String error;

    /** 命中条目 */
    private List<Item> results;

    /** Tavily 原始 response.answer 字段（如有），可直接给模型作摘要参考 */
    private String answer;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Item {

        /** 标题 */
        private String title;

        /** URL */
        private String url;

        /** 摘要文本（已裁剪） */
        private String content;

        /** 发布日期（Tavily published_date 字段，可为空） */
        private String publishedDate;

        /** 综合分数（Tavily score 字段，0~1） */
        private Double score;
    }
}
