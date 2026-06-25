package com.reubenagent.chat.agent;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Tavily 联网搜索工具入参。
 *
 * <p>LLM 调用工具时按 schema 填写 query；其余字段缺省由工具内根据 {@link com.reubenagent.chat.config.ChatProperties.Tavily}
 * 注入，避免模型乱填导致结果集不可控。</p>
 *
 * @author reuben
 * @since 2026-06-25
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TavilySearchRequest {

    /** 搜索查询语句（必填） */
    private String query;

    /** 搜索主题：general / news，缺省走配置 */
    private String topic;

    /** basic / advanced，缺省走配置 */
    private String searchDepth;

    /** 结果数上限，缺省走配置 */
    private Integer maxResults;
}
