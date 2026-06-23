package com.reubenagent.chat.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 对话模块统一配置属性，绑定 {@code reuben.chat} 前缀。
 *
 * <p>聚合 Agent / Memory / Rag / Recommendation / Tavily / Executor / Trace / Pricing 八段配置，
 * 消除 super-agent 中散落的魔法常量。</p>
 *
 * @author reuben
 * @since 2026-06-23
 */
@Data
@ConfigurationProperties(prefix = "reuben.chat")
public class ChatProperties {

    private Agent agent = new Agent();
    private Memory memory = new Memory();
    private Rag rag = new Rag();
    private Recommendation recommendation = new Recommendation();
    private Tavily tavily = new Tavily();
    private Executor executor = new Executor();
    private Trace trace = new Trace();
    private Pricing pricing = new Pricing();

    // ============ Agent ============

    @Data
    public static class Agent {
        /** ReAct Agent 系统提示词，由 yaml 注入 */
        private String systemPrompt = "";
        /** 单轮执行内模型调用上限 */
        private Integer maxModelCallsPerRun = 8;
        /** 单线程内模型调用上限 */
        private Integer maxModelCallsPerThread = 40;
        /** 单轮执行内工具调用上限 */
        private Integer maxToolCallsPerRun = 6;
        /** 单线程内工具调用上限 */
        private Integer maxToolCallsPerThread = 30;
        /** 历史预览轮数 */
        private Integer historyPreviewTurns = 4;
    }

    // ============ Memory ============

    @Data
    public static class Memory {
        /** 最近窗口保留轮数 */
        private Integer keepRecentTurns = 4;
        /** 摘要压缩批量轮数 */
        private Integer compressionBatchTurns = 6;
        /** 近期 transcript 最大字符数 */
        private Integer recentTranscriptMaxChars = 2200;
        /** 单条 user prompt 最大长度（超长裁剪） */
        private Integer userPromptMaxChars = 8000;
        /** 单条 answer 最大长度（落库裁剪） */
        private Integer answerMaxChars = 16000;
        /** 摘要文本最大长度 */
        private Integer summaryMaxChars = 1200;
    }

    // ============ Rag ============

    @Data
    public static class Rag {
        /** 单子问题检索 topK */
        private Integer topK = 5;
        /** 证据分数阈值 */
        private Double minScore = 0.45;
        /** 证据字符预算 */
        private Integer charBudget = 3000;
        /** 证据数量上限 */
        private Integer maxEvidenceCount = 6;
        /** 是否启用 rerank */
        private Boolean rerankEnabled = false;
    }

    // ============ Recommendation ============

    @Data
    public static class Recommendation {
        /** 追问生成总开关 */
        private Boolean enabled = true;
        /** 超时毫秒 */
        private Integer timeoutMs = 3000;
        /** 最大追问数 */
        private Integer maxCount = 3;
        /** 追问 user prompt 模板，由 yaml 注入 */
        private String prompt = "";
    }

    // ============ Tavily ============

    @Data
    public static class Tavily {
        private String baseUrl = "https://api.tavily.com";
        private String apiKey = "";
        /** 搜索主题：general / news */
        private String topic = "general";
        /** basic / advanced */
        private String searchDepth = "basic";
        private Integer maxResults = 5;
        /** 超时毫秒 */
        private Integer timeoutMs = 8000;
    }

    // ============ Executor（线程池）============

    @Data
    public static class Executor {
        /** 检索线程池大小 */
        private Integer ragPoolSize = 4;
        /** 记忆压缩线程池大小 */
        private Integer memoryPoolSize = 2;
        /** 后处理（追问/落库）线程池大小 */
        private Integer postProcessPoolSize = 4;
    }

    // ============ Trace ============

    @Data
    public static class Trace {
        /** 全链路追踪持久化开关 */
        private Boolean persistenceEnabled = true;
        /** stage benchmark 滑窗样本数 */
        private Integer benchmarkWindowSize = 100;
    }

    // ============ Pricing（模型成本单价，未配置返回 null）============

    @Data
    public static class Pricing {
        /** key = model 名，value = 每千 token 美元 */
        private java.util.Map<String, Double> perKTokenCost = new java.util.HashMap<>();
    }
}
