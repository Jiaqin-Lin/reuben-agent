package com.reubenagent.chat.agent;

import com.alibaba.cloud.ai.graph.agent.interceptor.ToolCallHandler;
import com.alibaba.cloud.ai.graph.agent.interceptor.ToolCallRequest;
import com.alibaba.cloud.ai.graph.agent.interceptor.ToolCallResponse;
import com.alibaba.cloud.ai.graph.agent.interceptor.ToolInterceptor;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.reubenagent.chat.support.ChatContextKeys;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;

/**
 * Tavily 工具入参兜底拦截器 —— 保证 query 非空。
 *
 * <p>对标 super-agent 修正点：用 <b>Optional / 哨兵字符串</b> 而非 {@code null} 作为
 * 控制流信号。LLM 偶尔会输出空 query 或省略 query 字段，本拦截器在调真实工具前补回
 * {@link ChatContextKeys#QUESTION}（原始用户问题），让搜索至少有可执行入参。</p>
 *
 * <p>实现策略：
 * <ol>
 *   <li>解析 {@code request.getArguments()}（JSON）→ 取 {@code query}；</li>
 *   <li>若 query 为空字符串 / null / 仅空白 → 用 {@code context.get(QUESTION)} 替换；</li>
 *   <li>用哨兵字符串 {@link #SENTINEL_EMPTY} 表示"确实没找到 query"而非 null；</li>
 *   <li>重写 arguments JSON 后交下游 handler。</li>
 * </ol></p>
 *
 * @author reuben
 * @since 2026-06-25
 */
@Slf4j
public class TavilyToolInputFallbackInterceptor extends ToolInterceptor {

    /** 哨兵：标记"未找到 query"的非 null 占位，避免 null 控制流。 */
    public static final String SENTINEL_EMPTY = "__tavily_query_missing__";

    private static final String TOOL_NAME = "tavily_search";

    @Override
    public ToolCallResponse interceptToolCall(ToolCallRequest request, ToolCallHandler handler) {
        if (request == null || !TOOL_NAME.equals(request.getToolName())) {
            return handler.call(request);
        }
        String originalArgs = request.getArguments();
        String patchedArgs = patchQuery(originalArgs, request.getContext());
        if (!patchedArgs.equals(originalArgs)) {
            log.debug("Tavily 入参兜底 → original={} patched={}",
                    originalArgs, patchedArgs);
            ToolCallRequest patched = ToolCallRequest.builder(request)
                    .arguments(patchedArgs)
                    .build();
            return handler.call(patched);
        }
        return handler.call(request);
    }

    private String patchQuery(String arguments, Map<String, Object> context) {
        String query = extractQuery(arguments);
        if (query != null && !query.isBlank()) {
            return arguments;
        }
        // 哨兵优先：从 context 取原始问题，取不到用哨兵字符串而非 null
        String fallback = SENTINEL_EMPTY;
        if (context != null) {
            Object q = context.get(ChatContextKeys.QUESTION);
            if (q instanceof String && !((String) q).isBlank()) {
                fallback = (String) q;
            }
        }
        return writeQuery(arguments, fallback);
    }

    private String extractQuery(String arguments) {
        if (arguments == null || arguments.isBlank()) {
            return null;
        }
        try {
            JSONObject json = JSON.parseObject(arguments);
            return json == null ? null : json.getString("query");
        } catch (Exception e) {
            log.warn("Tavily 入参解析失败，走兜底 → args={} err={}", arguments, e.getMessage());
            return null;
        }
    }

    private String writeQuery(String arguments, String query) {
        JSONObject json;
        try {
            json = arguments == null || arguments.isBlank()
                    ? new JSONObject() : JSON.parseObject(arguments);
            if (json == null) {
                json = new JSONObject();
            }
        } catch (Exception e) {
            json = new JSONObject();
        }
        json.put("query", query);
        return json.toJSONString();
    }

    @Override
    public String getName() {
        return "tavily-input-fallback";
    }
}
