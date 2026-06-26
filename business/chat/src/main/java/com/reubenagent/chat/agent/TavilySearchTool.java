package com.reubenagent.chat.agent;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.reubenagent.chat.config.ChatProperties;
import com.reubenagent.chat.support.ChatContextKeys;
import com.reubenagent.chat.support.ChatStreamEventWriter;
import com.reubenagent.chat.support.ChatTexts;
import com.reubenagent.chat.support.SinkEmitHelper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Tavily 联网搜索工具 —— ReAct Agent 唯一外置工具。
 *
 * <p>对标 super-agent {@code TavilySearchTool}，reuben 修正与简化：
 * <ul>
 *   <li>不直接抛业务异常给 agent loop，所有失败包装为 {@link TavilySearchToolResult}（{@code success=false}），
 *       让 LLM 自己根据"工具失败"事实决策下一步；</li>
 *   <li>调用前 emit thinking 事件、调用后把工具名写进 {@code ToolContext.chat.used.tools}；</li>
 *   <li>结果摘要按 800 字符裁剪，避免 token 暴涨；</li>
 *   <li>API key 缺失时直接走"未配置 Tavily，无法联网"的失败结果，不发 HTTP。</li>
 * </ul></p>
 *
 * <p><b>注意</b>：方法签名 {@code search(TavilySearchRequest, ToolContext)} 由
 * {@code FunctionToolCallback} 包装为 {@code ToolCallback}，方法名即工具入口；Spring AI
 * 会按 {@link TavilySearchRequest} 类型反序列化 LLM 输出的 JSON 参数。</p>
 *
 * <p><b>RAG 不作为工具</b>：本期 OPEN_CHAT 走 ReAct+Tavily，DOCUMENT/AUTO 走 RAG executor，
 * 两路隔离避免双路径混乱；未来如需 {@code rag_search} tool，在本类旁加同构 @Component。</p>
 *
 * @author reuben
 * @since 2026-06-25
 */
@Slf4j
@Component
public class TavilySearchTool {

    /** 单条结果摘要裁剪上限，防止 token 暴涨。 */
    private static final int SNIPPET_MAX_CHARS = 800;

    /** 工具调用 thinking 文案（可配，本期硬编码常量，下版进 ChatProperties）。 */
    private static final String THINKING_TEXT = "🔍 正在联网搜索最新资料。";

    private final RestClient tavilyRestClient;
    private final ChatProperties properties;
    private final ChatStreamEventWriter eventWriter;

    public TavilySearchTool(@Qualifier("tavilyRestClient") RestClient tavilyRestClient,
                            ChatProperties properties,
                            ChatStreamEventWriter eventWriter) {
        this.tavilyRestClient = tavilyRestClient;
        this.properties = properties;
        this.eventWriter = eventWriter;
    }

    /**
     * 工具入口：执行 Tavily /search。
     *
     * <p>由 {@code FunctionToolCallback} 反射调用；{@code ToolContext} 内含
     * {@link ChatContextKeys#STREAM_SINK}（{@code Sinks.Many<String>}）与
     * {@link ChatContextKeys#USED_TOOLS}（{@code Set<String>}）等运行态。</p>
     */
    public TavilySearchToolResult search(TavilySearchRequest request, ToolContext toolContext) {
        long start = System.currentTimeMillis();
        String query = request == null ? null : request.getQuery();
        emitThinking(toolContext);

        // 入参校验：query 空 → 直接失败结果，不抛异常
        if (query == null || query.isBlank()) {
            log.warn("Tavily 工具调用 query 为空 → fallback 失败结果");
            TavilySearchToolResult fail = TavilySearchToolResult.builder()
                    .success(false)
                    .error("query 不能为空")
                    .results(List.of())
                    .build();
            registerTrace(toolContext, request, fail, System.currentTimeMillis() - start, null);
            return fail;
        }

        // API key 缺失 → 跳过 HTTP，直接失败结果
        ChatProperties.Tavily cfg = properties.getTavily();
        if (cfg.getApiKey() == null || cfg.getApiKey().isBlank()) {
            log.warn("Tavily apiKey 未配置，跳过联网搜索 → query={}", query);
            TavilySearchToolResult fail = TavilySearchToolResult.builder()
                    .success(false)
                    .error("Tavily apiKey 未配置，联网搜索不可用")
                    .results(List.of())
                    .build();
            registerTrace(toolContext, request, fail, System.currentTimeMillis() - start, null);
            return fail;
        }

        try {
            Map<String, Object> body = buildRequestBody(query, request, cfg);
            String resp = tavilyRestClient.post()
                    .uri("/search")
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("Authorization", "Bearer " + cfg.getApiKey())
                    .body(JSON.toJSONString(body))
                    .retrieve()
                    .body(String.class);

            TavilySearchToolResult result = parseResponse(resp);
            registerTrace(toolContext, request, result, System.currentTimeMillis() - start, null);
            return result;
        } catch (Exception e) {
            log.warn("Tavily 搜索失败 → query={} err={}", query, e.getMessage());
            TavilySearchToolResult fail = TavilySearchToolResult.builder()
                    .success(false)
                    .error("Tavily 调用异常：" + e.getMessage())
                    .results(List.of())
                    .build();
            registerTrace(toolContext, request, fail, System.currentTimeMillis() - start, e);
            return fail;
        }
    }

    private Map<String, Object> buildRequestBody(String query, TavilySearchRequest req, ChatProperties.Tavily cfg) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("query", query);
        body.put("topic", req.getTopic() != null && !req.getTopic().isBlank() ? req.getTopic() : cfg.getTopic());
        body.put("search_depth",
                req.getSearchDepth() != null && !req.getSearchDepth().isBlank() ? req.getSearchDepth() : cfg.getSearchDepth());
        body.put("max_results",
                req.getMaxResults() != null && req.getMaxResults() > 0 ? req.getMaxResults() : cfg.getMaxResults());
        body.put("include_answer", true);
        body.put("include_raw_content", false);
        return body;
    }

    private TavilySearchToolResult parseResponse(String resp) {
        if (resp == null || resp.isBlank()) {
            return TavilySearchToolResult.builder()
                    .success(false)
                    .error("Tavily 返回空响应")
                    .results(List.of())
                    .build();
        }
        try {
            JSONObject json = JSON.parseObject(resp);
            String answer = json.getString("answer");
            JSONArray arr = json.getJSONArray("results");
            List<TavilySearchToolResult.Item> items = new ArrayList<>();
            if (arr != null) {
                for (int i = 0; i < arr.size(); i++) {
                    JSONObject item = arr.getJSONObject(i);
                    if (item == null) {
                        continue;
                    }
                    items.add(TavilySearchToolResult.Item.builder()
                            .title(ChatTexts.clip(item.getString("title"), 200))
                            .url(item.getString("url"))
                            .content(ChatTexts.clip(item.getString("content"), SNIPPET_MAX_CHARS))
                            .publishedDate(item.getString("published_date"))
                            .score(item.getDouble("score"))
                            .build());
                }
            }
            return TavilySearchToolResult.builder()
                    .success(true)
                    .answer(ChatTexts.clip(answer, 600))
                    .results(items)
                    .build();
        } catch (Exception e) {
            log.warn("Tavily 响应解析失败 → resp={} err={}", ChatTexts.clip(resp, 200), e.getMessage());
            return TavilySearchToolResult.builder()
                    .success(false)
                    .error("Tavily 响应解析失败：" + e.getMessage())
                    .results(List.of())
                    .build();
        }
    }

    // ======================== 副作用：thinking / trace / usedTools ========================

    @SuppressWarnings("unchecked")
    private void emitThinking(ToolContext toolContext) {
        if (toolContext == null || toolContext.getContext() == null) {
            return;
        }
        Object sink = toolContext.getContext().get(ChatContextKeys.STREAM_SINK);
        String conversationId = (String) toolContext.getContext().get(ChatContextKeys.CONVERSATION_ID);
        Object turnIdObj = toolContext.getContext().get(ChatContextKeys.TURN_ID);
        Long turnId = turnIdObj instanceof Number ? ((Number) turnIdObj).longValue() : null;
        if (sink instanceof reactor.core.publisher.Sinks.Many) {
            reactor.core.publisher.Sinks.Many<String> many = (reactor.core.publisher.Sinks.Many<String>) sink;
            SinkEmitHelper.emitNext(many, eventWriter.thinking(THINKING_TEXT, conversationId, turnId));
        }
    }

    @SuppressWarnings("unchecked")
    private void registerTrace(ToolContext toolContext, TavilySearchRequest request,
                               TavilySearchToolResult result, long durationMs, Throwable error) {
        // 标记 usedTools
        if (toolContext != null && toolContext.getContext() != null) {
            Object used = toolContext.getContext().get(ChatContextKeys.USED_TOOLS);
            if (used instanceof java.util.Set) {
                ((java.util.Set<String>) used).add("tavily_search");
            }
        }
        log.debug("Tavily 工具调用收尾 → query={} success={} duration={}ms",
                request == null ? null : request.getQuery(),
                result != null && result.isSuccess(), durationMs);
    }

    /** 暴露 thinking 文案与超时常量供测试断言。 */
    public String thinkingText() {
        return THINKING_TEXT;
    }

    public Duration timeout() {
        return Duration.ofMillis(properties.getTavily().getTimeoutMs());
    }
}
