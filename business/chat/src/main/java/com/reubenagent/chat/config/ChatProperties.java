package com.reubenagent.chat.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

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
    private Lease lease = new Lease();
    private Rewrite rewrite = new Rewrite();
    private Orchestration orchestration = new Orchestration();
    private Navigation navigation = new Navigation();

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
        /** 证据 RRF 分数阈值（rag 模块返回 RRF 融合分 ~0.016，非 cosine 相似度，故阈值取小值） */
        private Double minScore = 0.0;
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

    // ============ Lease（会话执行租约）============

    @Data
    public static class Lease {
        /** 租约 TTL 秒 */
        private Integer ttlSeconds = 60;
    }

    // ============ Rewrite（查询改写）============

    @Data
    public static class Rewrite {
        /** 改写总开关，关闭则直接走规则 fallback */
        private Boolean enabled = true;
        /** 无历史时，问题字数低于此阈值则不改写 */
        private Integer needsRewriteNoHistoryChars = 8;
        /** 有历史时，问题字数低于此阈值则不改写 */
        private Integer needsRewriteWithHistoryChars = 18;
        /** 单轮最大子问题数 */
        private Integer maxSubQuestions = 4;
        /** 改写 LLM temperature */
        private Double temperature = 0.2;
        /** 改写 LLM topP */
        private Double topP = 0.8;
        /** 改写模型 thinking 开关（DeepSeek reasoner 系列用） */
        private Boolean thinkingEnabled = false;
    }

    // ============ Orchestration（编排决策）============

    @Data
    public static class Orchestration {
        /** AUTO_DOCUMENT 模式下，知识路由置信度低于此值 → CLARIFICATION */
        private Double clarifyConfidenceThreshold = 0.45;
        /** 候选文档 top1 与 top2 评分差 ≤ 此值视为模糊 → CLARIFICATION（RRF 分量级 ~0.001） */
        private Double clarifyTopScoreDiff = 0.001;
        /** 检索 topK 用于候选文档评分 */
        private Integer routeCandidateTopK = 5;
    }

    // ============ Navigation（结构导航 / 图查询）============

    @Data
    public static class Navigation {
        /** GRAPH_ONLY 大文档压缩摘要的节点数阈值，超过则调用 LLM 压缩 */
        private Integer structureSummaryNodeThreshold = 50;
        /** GRAPH_ONLY 结构摘要最大展示节点数 */
        private Integer structureSummaryMaxNodes = 50;
        /** 章节定位本地评分阈值，低于此分视为未定位 */
        private Double sectionMatchThreshold = 45.0;
        /** 章节定位时 sectionPath 命中加分 */
        private Double sectionPathScore = 100.0;
        /** 章节定位时 title 命中加分 */
        private Double titleScore = 90.0;
        /** 章节定位时 anchorText 命中加分 */
        private Double anchorTextScore = 80.0;
        /** 章节定位时 contentText 命中加分 */
        private Double contentTextScore = 45.0;
        /** 邻接查询关键词（上一节/下一节/属于哪个章节 等） */
        private List<String> adjacencyHints = new ArrayList<>(List.of(
                "上一节", "下一节", "前一节", "后一节", "上一章", "下一章",
                "属于哪个章节", "章节位置", "前一个", "后一个", "上一个", "下一个"));
        /** 大纲查询关键词（包含哪些章节/目录 等） */
        private List<String> outlineHints = new ArrayList<>(List.of(
                "包含哪些章节", "有哪些章节", "有哪些小节", "章节列表", "目录",
                "子章节", "子小节", "下级章节", "展开目录", "列出目录"));
        /** 条目查询关键词（哪一步/第几步 等） */
        private List<String> itemHints = new ArrayList<>(List.of(
                "哪一步", "哪一项", "第几步", "第几项", "具体步骤", "步骤中的"));
        /** 结构对象关键词（章节/小节/这章 等） */
        private List<String> structureObjectHints = new ArrayList<>(List.of(
                "章节", "小节", "这章", "这节", "这部分", "标题", "目录", "模块", "节点"));
        /** 大纲显式关键词（子章节/下级章节/展开目录 等）—— 比 outlineHints 更强，命中即 CHILD_SECTION_DESCEND */
        private List<String> outlineExplicitHints = new ArrayList<>(List.of(
                "子章节", "子小节", "下级章节", "展开目录", "列出目录"));
        /** 大纲动作关键词（下面/下级/子章节/展开/包含哪些/列出/组成）—— 配合结构对象触发下钻 */
        private List<String> outlineActionHints = new ArrayList<>(List.of(
                "下面", "下级", "子章节", "子小节", "展开", "包含哪些", "列出", "组成"));
        /** 强分析关键词（为什么/原因/影响/区别/对比 等）—— 命中不走 GRAPH_ONLY，倾向证据检索 */
        private List<String> analyticStrongHints = new ArrayList<>(List.of(
                "为什么", "原因", "影响", "区别", "对比", "比较", "如何理解", "分析", "解释"));
        /** 结构关系关键词（前后关系/相邻关系/上下级关系/父子关系/属于哪个章节） */
        private List<String> structuralRelationHints = new ArrayList<>(List.of(
                "前后关系", "相邻关系", "上下级关系", "父子关系", "属于哪个章节"));
        /** GRAPH_ONLY 显式邻接关键词（前一个/后一个/上一个/下一个/相邻/前后/位置） */
        private List<String> graphOnlyExplicitAdjacencyHints = new ArrayList<>(List.of(
                "前一个", "后一个", "上一个", "下一个", "前一", "后一", "相邻", "前后", "位置"));
        /** GRAPH_ONLY 结构对象关键词（章节/小节/这章/这节/这部分/标题/目录/模块/节点） */
        private List<String> graphOnlyStructureObjectHints = new ArrayList<>(List.of(
                "章节", "小节", "这章", "这节", "这部分", "标题", "目录", "模块", "节点"));
        /** LLM 图意图判定置信度阈值，低于此值不采信 LLM 结果，回退规则 */
        private Double llmIntentConfidenceThreshold = 0.75;
        /** LLM 图意图判定 temperature（temperature=0.0 保证确定性输出） */
        private Double llmIntentTemperature = 0.0;
        /** LLM 图意图 prompt 模板名（classpath:prompt/ 下，不含 .st 后缀） */
        private String llmIntentTemplate = "document-graph-only-intent";
    }
}
