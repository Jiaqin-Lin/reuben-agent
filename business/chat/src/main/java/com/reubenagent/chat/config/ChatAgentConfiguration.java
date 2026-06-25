package com.reubenagent.chat.config;

import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.agent.hook.modelcalllimit.ModelCallLimitHook;
import com.alibaba.cloud.ai.graph.agent.hook.toolcalllimit.ToolCallLimitHook;
import com.alibaba.cloud.ai.graph.agent.interceptor.toolerror.ToolErrorInterceptor;
import com.alibaba.cloud.ai.graph.agent.interceptor.toolretry.ToolRetryInterceptor;
import com.alibaba.cloud.ai.graph.checkpoint.savers.mysql.CreateOption;
import com.alibaba.cloud.ai.graph.checkpoint.savers.mysql.MysqlSaver;
import com.reubenagent.chat.agent.TavilySearchRequest;
import com.reubenagent.chat.agent.TavilySearchTool;
import com.reubenagent.chat.agent.TavilySearchToolResult;
import com.reubenagent.chat.agent.TavilyToolInputFallbackInterceptor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.function.FunctionToolCallback;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import javax.sql.DataSource;
import java.util.Set;

/**
 * ReAct Agent 装配 —— Tavily 工具 / 拦截器 / ReactAgent bean。
 *
 * <p>对标 super-agent {@code ChatAgentConfiguration}，reuben 修正：
 * <ul>
 *   <li><b>移除 DashScopeCompatibilityInterceptor</b>（555 行、base-url 字符串识别、INFO 全量日志）。
 *       DeepSeek 为主模型，本期不需要厂商兼容层；后续接多厂商按 provider 选拦截器，日志 debug。</li>
 *   <li>Tavily 工具用 {@link FunctionToolCallback} 显式装配（name {@code tavily_search}），
 *       不依赖 {@code @Tool} 注解扫描，便于控制 schema 与结果转换；</li>
 *   <li>3 个拦截器：{@link TavilyToolInputFallbackInterceptor}（query 兜底，哨兵非 null）/
 *       {@link ToolRetryInterceptor}（2 次重试 + jitter 退避）/
 *       {@link ToolErrorInterceptor}（异常包装为 ToolCallResponse）；</li>
 *   <li>{@link MysqlSaver} 复用主 DataSource，{@code CreateOption.CREATE_IF_NOT_EXISTS} 自动建表；</li>
 *   <li>{@link ModelCallLimitHook} / {@link ToolCallLimitHook} 阈值来自 {@link ChatProperties.Agent}。</li>
 * </ul></p>
 *
 * @author reuben
 * @since 2026-06-25
 */
@Slf4j
@Configuration
public class ChatAgentConfiguration {

    public static final String TAVILY_TOOL_NAME = "tavily_search";
    public static final String TAVILY_REST_CLIENT = "tavilyRestClient";

    // ======================== RestClient ========================

    /** Tavily REST 客户端 —— 由 {@link ChatProperties.Tavily} 构造，超时 / baseUrl 走配置。 */
    @Bean(TAVILY_REST_CLIENT)
    public RestClient tavilyRestClient(ChatProperties properties) {
        ChatProperties.Tavily cfg = properties.getTavily();
        ClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        ((SimpleClientHttpRequestFactory) factory).setConnectTimeout(cfg.getTimeoutMs());
        ((SimpleClientHttpRequestFactory) factory).setReadTimeout(cfg.getTimeoutMs());
        String baseUrl = cfg.getBaseUrl() == null || cfg.getBaseUrl().isBlank()
                ? "https://api.tavily.com" : cfg.getBaseUrl();
        log.info("Tavily RestClient 已装配 → baseUrl={} timeoutMs={}", baseUrl, cfg.getTimeoutMs());
        return RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(factory)
                .build();
    }

    // ======================== Tavily ToolCallback ========================

    /**
     * Tavily 工具回调 —— name {@code tavily_search}，inputType {@link TavilySearchRequest}，
     * 返回 {@link TavilySearchToolResult}（Spring AI 自动序列化为 JSON 给 LLM）。
     */
    @Bean(TAVILY_TOOL_NAME)
    public FunctionToolCallback<TavilySearchRequest, TavilySearchToolResult> tavilySearchToolCallback(
            TavilySearchTool tavilySearchTool) {
        return FunctionToolCallback
                .<TavilySearchRequest, TavilySearchToolResult>builder(
                        TAVILY_TOOL_NAME,
                        (req, ctx) -> tavilySearchTool.search(req, ctx))
                .description("Search the web for real-time information using Tavily. "
                        + "Useful for time-sensitive queries (news, latest events, current data). "
                        + "Input: {\"query\": string, \"topic\": \"general\"|\"news\" (optional), "
                        + "\"searchDepth\": \"basic\"|\"advanced\" (optional), \"maxResults\": int (optional)}.")
                .inputType(TavilySearchRequest.class)
                .build();
    }

    // ======================== 拦截器 ========================

    /** Tavily 入参兜底拦截器 —— query 空时用原始问题回填，用哨兵字符串而非 null 控制流。 */
    @Bean
    public TavilyToolInputFallbackInterceptor tavilyToolInputFallbackInterceptor() {
        return new TavilyToolInputFallbackInterceptor();
    }

    /** 工具重试拦截器 —— 2 次重试 + 指数退避 + jitter，覆盖 Tavily 偶发网络抖动。 */
    @Bean
    public ToolRetryInterceptor toolRetryInterceptor() {
        return ToolRetryInterceptor.builder()
                .maxRetries(2)
                .toolName(TAVILY_TOOL_NAME)
                .onFailure(ToolRetryInterceptor.OnFailureBehavior.RETURN_MESSAGE)
                .backoffFactor(2.0)
                .initialDelay(500)
                .maxDelay(4000)
                .jitter(true)
                .build();
    }

    /** 工具异常包装拦截器 —— 兜底捕获任意异常，转为 ToolCallResponse.error。 */
    @Bean
    public ToolErrorInterceptor toolErrorInterceptor() {
        return ToolErrorInterceptor.builder().build();
    }

    // ======================== MysqlSaver ========================

    /**
     * ReAct Agent 检查点 saver —— 复用主 DataSource，{@code CREATE_IF_NOT_EXISTS} 自动建
     * {@code GRAPH_THREAD} / {@code GRAPH_CHECKPOINT} 表。
     *
     * <p>与 {@link com.reubenagent.chat.session.ChatCheckpointManager}（自建轻量表）并存：
     * 后者用于 thread 清理与跨模块查询，{@link MysqlSaver} 仅供 Alibaba ReactAgent 内部 checkpoint。</p>
     */
    @Bean
    public MysqlSaver chatMysqlSaver(DataSource dataSource) {
        log.info("ReAct Agent MysqlSaver 已装配 → dataSource={}", dataSource.getClass().getSimpleName());
        return MysqlSaver.builder()
                .dataSource(dataSource)
                .createOption(CreateOption.CREATE_IF_NOT_EXISTS)
                .build();
    }

    // ======================== ReactAgent ========================

    /**
     * 业务对话 ReAct Agent —— 唯一 Bean，供 {@code ReactAgentExecutor} 注入使用。
     *
     * <p>装配点：
     * <ul>
     *   <li>name {@code business_chat_agent}（与 super-agent 一致）；</li>
     *   <li>instruction 由 {@link ChatProperties.Agent#getSystemPrompt()} 注入；</li>
     *   <li>model 用 {@code deepSeekChatModel}（{@link ChatModel} Bean）；</li>
     *   <li>tools 注册 tavily；</li>
     *   <li>saver 用 {@link #chatMysqlSaver}；</li>
     *   <li>hooks：{@link ModelCallLimitHook} + {@link ToolCallLimitHook}（阈值来自配置）；</li>
     *   <li>interceptors：3 个工具拦截器（顺序：fallback → retry → error）；</li>
     *   <li>{@code parallelToolExecution(true)} + {@code maxParallelTools(4)}。</li>
     * </ul></p>
     */
    @Bean
    public ReactAgent reactAgent(@Qualifier("deepSeekChatModel") ChatModel chatModel,
                                 @Qualifier(TAVILY_TOOL_NAME) FunctionToolCallback<TavilySearchRequest, TavilySearchToolResult> tavilyTool,
                                 MysqlSaver chatMysqlSaver,
                                 TavilyToolInputFallbackInterceptor tavilyFallback,
                                 ToolRetryInterceptor toolRetryInterceptor,
                                 ToolErrorInterceptor toolErrorInterceptor,
                                 ChatProperties properties) {
        ChatProperties.Agent cfg = properties.getAgent();
        ModelCallLimitHook modelCallLimitHook = ModelCallLimitHook.builder()
                .runLimit(cfg.getMaxModelCallsPerRun())
                .threadLimit(cfg.getMaxModelCallsPerThread())
                .exitBehavior(ModelCallLimitHook.ExitBehavior.END)
                .build();
        ToolCallLimitHook toolCallLimitHook = ToolCallLimitHook.builder()
                .runLimit(cfg.getMaxToolCallsPerRun())
                .threadLimit(cfg.getMaxToolCallsPerThread())
                .exitBehavior(ToolCallLimitHook.ExitBehavior.END)
                .build();
        log.info("ReactAgent 装配 → modelCallsPerRun={} toolCallsPerRun={} parallelTools={} maxParallel={}",
                cfg.getMaxModelCallsPerRun(), cfg.getMaxToolCallsPerRun(), true, 4);
        return ReactAgent.builder()
                .name("business_chat_agent")
                .model(chatModel)
                .instruction(cfg.getSystemPrompt())
                .tools(tavilyTool)
                .saver(chatMysqlSaver)
                .hooks(modelCallLimitHook, toolCallLimitHook)
                .interceptors(tavilyFallback, toolRetryInterceptor, toolErrorInterceptor)
                .parallelToolExecution(true)
                .maxParallelTools(4)
                .build();
    }
}
