# Reuben-Agent 架构与完整链路文档

> 本文档详细描述 reuben-agent 企业 AI Agent 平台的系统架构、模块划分、以及每一条核心链路的完整执行流程（含决策分支、阈值判断、降级策略）。

---

## 一、系统全景架构

```
┌──────────────────────────────────────────────────────────────────────────────────────┐
│                                      UI Layer                                         │
│                              React + TypeScript (ui/)                                 │
│                    ChatPage.tsx ── SSE EventSource ── 流式渲染                         │
└─────────────────────────────────────┬────────────────────────────────────────────────┘
                                      │ HTTP + SSE
                                      ▼
┌──────────────────────────────────────────────────────────────────────────────────────┐
│                               Launcher (启动入口)                                      │
│                    @SpringBootApplication scanBasePackages="com.reubenagent"           │
└─────────────────────────────────────┬────────────────────────────────────────────────┘
                                      │
          ┌───────────────────────────┼───────────────────────────┐
          │                           │                           │
          ▼                           ▼                           ▼
┌──────────────────┐    ┌──────────────────────┐    ┌──────────────────────────┐
│   Common 共享层   │    │  Framework 基础设施   │    │   Business 业务模块聚合    │
│                  │    │                      │    │                          │
│ • ApiResponse    │    │ • UidGenerator       │    │ ┌──────────────────────┐ │
│ • BaseEnum       │    │   (雪花 ID 64-bit)    │    │ │  document 文档管理    │ │
│ • BaseTableData  │    │ • BitsAllocator      │    │ │  (上传→解析→策略→索引) │ │
│ • 异常体系        │    │ • WorkerIdAssigner   │    │ └──────────────────────┘ │
│ • GlobalExHandler│    │   (Redis 分布式)       │    │ ┌──────────────────────┐ │
│ • PageVo         │    │                      │    │ │  rag 检索引擎         │ │
└──────────────────┘    └──────────────────────┘    │ │  (改写→双通道→RRF→Rerank)│ │
                                                    │ └──────────────────────┘ │
                                                    │ ┌──────────────────────┐ │
                                                    │ │  chat 对话模块        │ │
                                                    │ │  (编排→Agent→记忆)    │ │
                                                    │ └──────────────────────┘ │
                                                    │ ┌──────────────────────┐ │
                                                    │ │  auth stub            │ │
                                                    │ └──────────────────────┘ │
                                                    └──────────────────────────┘

┌──────────────────────────────────────────────────────────────────────────────────────┐
│                                   Middleware Layer                                    │
│                                                                                      │
│  ┌──────────┐  ┌──────────┐  ┌──────────┐  ┌──────────┐  ┌──────────┐  ┌──────────┐ │
│  │  MySQL   │  │PGVector  │  │  Redis   │  │  MinIO   │  │   ES     │  │  Neo4j   │ │
│  │  8.0     │  │ pgvector │  │   7      │  │  (S3)    │  │  8.15    │  │   5      │ │
│  │ (主存储)  │  │ (向量库)  │  │ (租约/锁) │  │ (文件存储) │  │ (关键词索引)│  │ (图遍历)  │ │
│  └──────────┘  └──────────┘  └──────────┘  └──────────┘  └──────────┘  └──────────┘ │
│                                                                                      │
│  ┌──────────────────────────────────────────────────────────────────────────────┐    │
│  │                          Kafka 3.8 (KRaft)                                    │    │
│  │  ┌─────────────────────────────┐  ┌─────────────────────────────────────┐    │    │
│  │  │ reuben-agent-document-      │  │ reuben-agent-document-              │    │    │
│  │  │   parse-route               │  │   index-build                       │    │    │
│  │  │ (文档上传 → 异步解析)         │  │ (策略确认 → 异步切块+向量化)          │    │    │
│  │  └─────────────────────────────┘  └─────────────────────────────────────┘    │    │
│  └──────────────────────────────────────────────────────────────────────────────┘    │
└──────────────────────────────────────────────────────────────────────────────────────┘
```

### 依赖方向（严格单向）

```
launcher ──► business/* ──► common
launcher ──► business/* ──► framework
business/* 之间互不依赖（chat 通过 ObjectProvider 容错引用 document 的 KnowledgeRouteService）
```

---

## 二、模块职责速览

| 模块 | 核心职责 |
|------|---------|
| **common** | `ApiResponse<T>` 统一响应、`BaseEnum`/`EnumUtils` 枚举工具、`BusinessException`/`DocumentException`/`ValidationException` 异常体系、`BaseTableData` 基础实体、`PageVo` 分页 |
| **framework** | `UidGenerator` 雪花 ID 生成器（28bit 时间戳 + 22bit WorkerId + 13bit 序列），通过 Redis 分配 WorkerId |
| **document** | 文档全生命周期：上传→MinIO 存储→Tika 文本提取→4 阶段结构解析→策略推荐→Kafka 异步→4 阶段索引构建（切块+向量化+关键词索引） |
| **rag** | 检索增强生成：查询改写→双通道并行检索（PGVector 向量 + ES 关键词）→证据门控→RRF 融合→父块提升→Rerank 重排序 |
| **chat** | 对话全流程：SSE 流式响应、Redis 分布式租约、7 级规则引擎意图路由、4 种 Executor 执行模式、两级记忆系统、Agent 工具调用、全链路可观测追踪 |

---

## 三、链路一：对话流（Chat Flow）

### 3.1 总体时序

```
用户输入问题
    │
    ▼
┌──────────────────────────────────────────────────────────────────────┐
│ POST /api/chat/stream                                                │
│   Body: { question, conversationId, chatMode, selectedDocumentId }   │
│   Response: text/event-stream (SSE)                                  │
└──────────────────────────────────┬───────────────────────────────────┘
                                   │
                                   ▼
                        ChatStreamOrchestrator.openStream()
                                   │
                    ┌──────────────┴──────────────┐
                    │  1. buildLaunchPlan()       │
                    │     解析 DTO → StreamLaunchPlan │
                    │     (强类型 ChatMode 枚举)     │
                    └──────────────┬──────────────┘
                                   │
                    ┌──────────────▼──────────────┐
                    │  2. tryAcquire()             │
                    │     Redis SET NX PX          │
                    │     同一会话串行执行           │
                    │     ├─ 成功 → 继续            │
                    │     └─ 失败 → return error Flux│
                    └──────────────┬──────────────┘
                                   │
                    ┌──────────────▼──────────────┐
                    │  3. bootstrapConversation()   │
                    │     • ensureConversation()    │
                    │     • 创建 TurnArchiveRecord  │
                    │     • 构建 Sinks.Many (SSE)   │
                    │     • 创建 ChatTraceRecorder  │
                    │     • 注册到 RuntimeRegistry  │
                    └──────────────┬──────────────┘
                                   │
                    ┌──────────────▼──────────────┐
                    │  4. bindClientChannel()      │
                    │     返回 Flux<String> (SSE)  │
                    │     doOnSubscribe →           │
                    │       activateGeneration()    │
                    └──────────────┬──────────────┘
                                   │
                    ┌──────────────▼──────────────┐
                    │  5. activateGeneration()      │
                    │     启动两个 Disposable:      │
                    │     • startLeaseRenewal()     │
                    │       (每 TTL/3 秒续约)       │
                    │     • buildExecution()        │
                    └──────────────┬──────────────┘
                                   │
                                   ▼
                    ChatPreparationOrchestrator.prepare()
```

### 3.2 prepare() 准备阶段详解

```
prepare(ChatStreamDto, StreamLaunchPlan, ChatTraceRecorder)
│
├─ 1. loadMemory()
│      ├─ 加载 ChatMemoryContext:
│      │   ├─ 短期记忆: 最近 keepRecentTurns=4 轮对话原文
│      │   └─ 长期记忆: LLM 压缩的对话摘要 (summaryMaxChars=1200)
│      └─ trace: MEMORY stage (耗时记录)
│
├─ 2. 意图检测 (looksLikeOpenChat)
│      ├─ 条件 1: ChatMode == OPEN_CHAT → 跳过改写
│      ├─ 条件 2: 问题匹配 ChatIntentHints 关键词 → 跳过改写
│      └─ 不满足 → 进入改写
│
├─ 3. ChatQueryRewriteService.rewrite()
│      ├─ normalizeQuestion() → 规范化问题
│      │
│      ├─ needsRewrite() 判断:
│      │   ├─ 无历史: 问题长度 < needsRewriteNoHistoryChars=8 → 需改写
│      │   └─ 有历史: 问题长度 < needsRewriteWithHistoryChars=18 → 需改写
│      │
│      ├─ looksLikeExplicitMultiQuestion() 多问题检测:
│      │   ├─ >= 2 个问号 "?"
│      │   ├─ 中文/英文分号 ";；"
│      │   ├─ 多行结构
│      │   ├─ 编号模式 (1. 2. 3.)
│      │   └─ 关键词 "分别"
│      │
│      ├─ [需要改写] → LLM 改写:
│      │   ├─ 渲染 chat-query-rewrite.st 模板
│      │   │   (注入对话历史 + 当前问题)
│      │   ├─ 调用 ChatModel (temperature=0.2, topP=0.8)
│      │   ├─ 解析 JSON: { rewrite, should_split, sub_questions[] }
│      │   ├─ maxSubQuestions=4 上限截断
│      │   └─ LLM 失败 → ruleFallback()
│      │       └─ 按 [?？；;\n]+ 正则简单拆分
│      │
│      └─ [单问题/不需要改写] → noRewrite()
│           └─ 原问题作为唯一 sub_question
│
├─ 4. modeBranch() 模式分支（核心决策树）
│   │
│   ├── case OPEN_CHAT:
│   │   └─ executionMode = REACT_AGENT
│   │
│   ├── case DOCUMENT (指定文档):
│   │   ├─ 记录 shadow knowledge route trace
│   │   └─ DocumentQuestionRouter.route() → 导航决策
│   │       └─ decision.toExecutionMode() → GRAPH_ONLY / GRAPH_THEN_EVIDENCE / RETRIEVAL
│   │
│   └── case AUTO_DOCUMENT (自动路由):
│       └─ routeKnowledge() 两级路由:
│           │
│           ├─ [一级] KnowledgeRouteService.route()
│           │   ├─ 三级层级路由: Scope → Topic → Document
│           │   │   (详见第五章: 知识路由链路)
│           │   │
│           │   ├─ 返回 KnowledgeRouteDecision:
│           │   │   ├─ topDocument() 获取最高分文档
│           │   │   ├─ confidence 置信度
│           │   │   └─ status: SUCCESS / LOW_CONFIDENCE / FAILED
│           │   │
│           │   └─ 判断:
│           │       ├─ status == SUCCESS && confidence >= 0.45
│           │       │   └─ 选中该文档 → DocumentQuestionRouter.route()
│           │       ├─ status == LOW_CONFIDENCE
│           │       │   └─ 多个候选接近 (topScoreDiff < 0.001)
│           │       │       └─ executionMode = CLARIFICATION
│           │       └─ status == FAILED
│           │           └─ 进入 RAG 兜底 ↓
│           │
│           └─ [二级兜底] routeKnowledgeByRag()
│               ├─ 无文档过滤的 RAG 检索 (topK=5)
│               ├─ 按 documentId 聚合分数
│               ├─ 取最高分文档
│               ├─ 如果 confidence < clarifyConfidenceThreshold (0.45)
│               │   └─ executionMode = CLARIFICATION
│               └─ 否则 → DocumentQuestionRouter.route()
│
└─ 5. buildPlan()
     └─ 组装 ConversationExecutionPlan
        { executionMode, rewrittenQuery, subQuestions,
          navigationDecision, selectedDocumentId, ... }
```

### 3.3 导航决策引擎 (DocumentQuestionRouter)

```
DocumentQuestionRouter.route(question, documentId, turnId, traceRecorder)
│
├─ 预处理: 提取 sub_questions, 检测 analytic keywords
│
├─ 阶段 1: 规则引擎 (detectGraphOnlyIntentByRules)
│   │  逐级匹配，命中即返回
│   │
│   ├─ Priority 1: 邻接关键词命中
│   │   keywords: "上一节/下一节/前一节/后一节/前面章节/后面章节"
│   │   → SECTION_ADJACENCY_LOOKUP, confidence=1.0
│   │
│   ├─ Priority 2: 章节编码 + 方向词
│   │   regex: (\\d+(?:\\.\\d+)+) + "上面/下面/之前/之后/前面/后面"
│   │   → SECTION_ADJACENCY_LOOKUP, confidence=0.92
│   │
│   ├─ Priority 3: 引号标题 + 方向词
│   │   regex: [""]([^""]{2,40})[""] + 方向词
│   │   → SECTION_ADJACENCY_LOOKUP, confidence=0.90
│   │
│   ├─ Priority 4: 结构对象 + 显式邻接词
│   │   object: "章节/部分/段落/小节" + "前一个/后一个/下一个/上一个"
│   │   → SECTION_ADJACENCY_LOOKUP, confidence=0.86
│   │
│   ├─ Priority 5: 大纲显式关键词
│   │   keywords: "第几章/有哪些章节/目录/大纲/结构/章节列表"
│   │   → CHILD_SECTION_DESCEND, confidence=1.0
│   │
│   ├─ Priority 6: 结构对象 + 大纲动作
│   │   object: "章节/部分" + action: "列出/有哪些/包括/包含"
│   │   → CHILD_SECTION_DESCEND, confidence=0.86
│   │
│   └─ Priority 7: 条目关键词 + 非强分析
│   │   keywords: "第几步/步骤/条目/第几条"
│   │   → ITEM_REFERENCE, confidence=0.80
│   │
│   └─ 全不命中 → rulesResult = null
│
├─ 阶段 2: LLM 回退 (classifyQuestionIntentWithModel)
│   │  触发条件: 单子问题 + 非强分析 + 有结构线索
│   │
│   ├─ 渲染 document-graph-only-intent.st 模板
│   ├─ 调用 ChatModel (temperature=0.0)
│   ├─ 解析 JSON → DocumentNavigationAction
│   ├─ 如果 confidence >= llmIntentConfidenceThreshold (0.75)
│   │   └─ 采用 LLM 结果
│   └─ 否则 → DIRECT_RETRIEVAL (纯检索，不走图)
│
├─ 阶段 3: 章节解析 (resolveSection)
│   │  四级回退:
│   │
│   ├─ Level 1: 按章节编码 (1.2.3, 第三章)
│   │   └─ graphService.findSectionByCode()
│   │
│   ├─ Level 2: ES 导航索引搜索
│   │   └─ navigationIndexService.searchSections()
│   │       (四维: topic + facet + informationNeed + question)
│   │
│   ├─ Level 3: 本地图结构评分
│   │   └─ graphService.findBestSection()
│   │       (sectionPath 100分 > title 90分 > anchor 80分 > content 45分)
│   │
│   └─ Level 4: null (放弃定位)
│
├─ 阶段 4: 条目索引解析 (resolveItemIndex)
│   │  识别 "第N步" / "第N条" 模式
│   │  中文数字 → 整数转换 (一→1, 二十→20)
│   │
│   └─ graphService.findItemByIndex(sectionNodeId, itemIndex)
│
└─ buildDecision() → DocumentNavigationDecision
    ├─ action → toExecutionMode()
    │   ├─ GRAPH_ONLY / CHILD_SECTION_DESCEND / SECTION_ADJACENCY_LOOKUP → GRAPH_ONLY
    │   ├─ ITEM_REFERENCE → GRAPH_THEN_EVIDENCE
    │   └─ 其他 → RETRIEVAL
    ├─ scopeMode: NONE / WHOLE_DOCUMENT / SECTION_SCOPE / SOFT / HARD_SECTION ...
    └─ anchors: structureAnchor + itemAnchor
```

### 3.4 四种 Executor 执行流程

```
ConversationExecutorRegistry.get(executionMode)
│
├── GRAPH_ONLY → GraphOnlyExecutor
│   │
│   ├─ resolveAnchorSectionId()
│   │   ├─ 优先: navigationDecision.structureAnchor.structureNodeId
│   │   └─ 回退: graphService.findBestSection(question)
│   │
│   └─ renderGraphOnly()
│       ├─ 判断 1: asksAdjacency() ───→ renderAdjacency()
│       │   "当前章节：X，父章节：Y，上一节：A，下一节：B"
│       │
│       ├─ 判断 2: asksChildren() ─────→ renderChildren()
│       │   "章节「X」包含以下子章节：1. A  2. B  3. C"
│       │
│       └─ 默认: 完整结构摘要
│           ├─ 章节数 ≤ 50 (structureSummaryNodeThreshold)
│           │   └─ 直接拼接所有章节 title + anchor
│           └─ 章节数 > 50
│               └─ compressWithLlm() (temperature=0.2)
│
├── GRAPH_THEN_EVIDENCE → GraphThenEvidenceExecutor
│   │
│   ├─ locateSection()
│   │   ├─ 优先: itemAnchor.structureNodeId
│   │   ├─ 其次: structureAnchor.structureNodeId
│   │   └─ 回退: graphService.findBestSection()
│   │
│   ├─ retrievalAdapter.retrieveWithNodeFilter(sectionNodeId)
│   │   └─ RAG 检索 + structureNodeId 过滤
│   │
│   └─ ragAnswerExecutor.afterRetrieval()
│       └─ 复用 RAG 提示词组装 + 流式生成
│
├── RETRIEVAL → RagAnswerExecutor
│   │
│   ├─ emit thinking event ("检索中...")
│   │
│   ├─ retrievalAdapter.retrieve()  [boundedElastic]
│   │   │
│   │   ├─ 取 sub_questions (优先改写结果，否则原始问题)
│   │   ├─ 构建 filterFields: { documentId + 可选 structureNodeId }
│   │   │
│   │   ├─ 对每个 sub_question:
│   │   │   └─ ragRetrievalService.retrieve(
│   │   │         query, topK=5, filterFields
│   │   │       )
│   │   │       └─ 详见第四章 RAG 检索链路
│   │   │
│   │   ├─ 证据门控:
│   │   │   ├─ minScore 过滤 (default 0.0)
│   │   │   └─ maxEvidenceCount 截断 (default 6)
│   │   │
│   │   └─ 映射 RetrievalResult → SearchReference
│   │
│   └─ afterRetrieval()
│       ├─ emit reference SSE event (引用源列表)
│       ├─ ChatRagPromptAssemblyService.assemble()
│       │   ├─ 渲染 rag-answer-system + rag-answer-user 模板
│       │   ├─ charBudget=3000 证据预算
│       │   ├─ 同块复用标记 "[N] (同上)"
│       │   └─ 每证据块截断 800 字符
│       └─ ObservedChatModelService.streamText()
│           └─ 逐 token 输出 → SSE text event
│
└── REACT_AGENT → ReactAgentExecutor
    │
    ├─ assembleAgentQuestion()
    │   └─ 渲染 agent-question.st 模板
    │       (date + time-sensitivity hints + history summary + question)
    │
    ├─ buildRunnableConfig()
    │   └─ 注入 ToolContext metadata:
    │       (sink, conversationId, turnId, usedTools,
    │        thinkingSteps, question, date, usageSink)
    │
    └─ reactAgent.stream()
        │
        ├─ 过滤器: AGENT_MODEL_STREAMING output type
        ├─ 提取 AssistantMessage.getText()
        │
        ├─ [Tool Call 时]
        │   ├─ Tavily 搜索工具
        │   ├─ ToolRetryInterceptor (2 次重试, 指数退避+jitter)
        │   ├─ ToolErrorInterceptor (异常包装)
        │   └─ ModelUsageTraceInterceptor (用量追踪)
        │
        ├─ [限流保护]
        │   ├─ ModelCallLimitHook (run=8, thread=40)
        │   └─ ToolCallLimitHook (run=6, thread=30)
        │
        └─ 异常: GraphRunnerException → ChatException(MODEL_CALL_FAILED)
```

### 3.5 finalize() 收尾阶段

```
finalize()  [CAS 幂等, 只执行一次]
│
├─ 1. ChatRecommendationServiceImpl.recommend()
│   ├─ enabled=true 且 turn=COMPLETED 才执行
│   ├─ 构建 prompt (含最近引用源上下文)
│   ├─ 同步 LLM 调用 (timeout=3000ms)
│   ├─ 解析 JSON 数组 → 去重 → 截断 maxCount=3
│   └─ 失败 → warn + 空列表 (不阻塞)
│
├─ 2. archiveStore.completeTurn()
│   ├─ 持久化 reply content + status + executionMode
│   ├─ references JSON + thinkingSteps JSON
│   ├─ debug trace JSON
│   └─ latency metrics (各阶段耗时)
│
├─ 3. emit SSE events
│   ├─ recommend event (追问列表)
│   └─ done event (流结束)
│
└─ 4. cleanup()
    ├─ dispose subscriptions
    ├─ release Redis lease
    └─ remove from RuntimeRegistry
```

### 3.6 SSE 事件协议

| Event Type | 触发时机 | Payload |
|-----------|---------|---------|
| `thinking` | 系统状态变化 | `{ message: "检索中..." }` |
| `text` | LLM 逐 token 输出 | `{ content: "文", turnId: "..." }` |
| `status` | 状态变更 | `{ status: "..." }` |
| `error` | 异常 | `{ message: "...", code: ... }` |
| `reference` | 检索完成后 | `[{ title, url, snippet, score }]` |
| `recommend` | 追问生成后 | `["追问1", "追问2", "追问3"]` |
| `done` | 流结束 | `{ turnId: "..." }` |

> TurnId 使用雪花 ID，序列化为 String 以避免 JS Number 精度丢失。

---

## 四、链路二：RAG 检索引擎

### 4.1 总体架构

```
RagRetrievalServiceImpl.retrieve(RagRetrieveRequest)
│   request: { query, topK, filterFields }
│
├─ Stage 1: 参数校验
│   └─ query 非空 → 否则抛异常
│
├─ Stage 2: Query Rewrite (RAG 级改写)
│   │  QueryRewriteService.rewrite(query)
│   │
│   ├─ 规则判断: query.length < minQueryLength=6 → 跳过 LLM
│   ├─ LLM 改写: rag-query-rewrite.st 模板
│   ├─ 清理响应 (去引号/前缀标记)
│   └─ LLM 失败 → 返回原问题 (不阻塞)
│
├─ Stage 3: 双通道并行检索
│   │  CompletableFuture.supplyAsync() × 2
│   │  orTimeout(channelTimeoutMs=5000) 每通道
│   │
│   ├─ Channel A: VectorRetrievalChannel.retrieve()
│   │   ├─ EmbeddingModel.embed(query) → float[] vector
│   │   ├─ SQL:
│   │   │   SELECT chunk_id, chunk_text, metadata_json,
│   │   │          1 - (embedding <=> CAST(? AS vector)) AS similarity
│   │   │   FROM reuben_agent_document_embedding
│   │   │   WHERE metadata_json->>'documentId' = ?
│   │   │   ORDER BY embedding <=> CAST(? AS vector)
│   │   │   LIMIT vectorTopK=8
│   │   │
│   │   ├─ source = "vector"
│   │   ├─ 无 EmbeddingModel → 空列表
│   │   └─ 超时/异常 → 空列表 (graceful degradation)
│   │
│   └─ Channel B: KeywordRetrievalChannel.retrieve()
│       ├─ ES BM25 match query on chunk_text field
│       ├─ term queries on metadata filter fields
│       ├─ size = keywordTopK=8
│       ├─ source = "keyword"
│       ├─ 无 ES client → 空列表
│       └─ 超时/异常 → 空列表
│
├─ Stage 4: 等待双通道完成
│   └─ CompletableFuture.allOf().join()
│
├─ Stage 5: 证据门控 (applyEvidenceGates)
│   │
│   ├─ Vector 门控: similarity >= minVectorSimilarity=0.45
│   │   (绝对余弦相似度阈值)
│   │
│   └─ Keyword 门控: score >= max(channel_score) * keywordRelativeScoreFloor=0.35
│       (相对分数底线)
│
├─ Stage 6: RRF 融合 (RrfFusionService.fuse)
│   │
│   ├─ 公式: RRF_score(d) = Σ 1 / (k + rank_c(d))
│   │   k = rrfK = 60
│   │   rank 从 1 开始
│   │
│   ├─ 单次遍历 LinkedHashMap 按 chunkId 合并
│   ├─ 同一 chunk 出现在双通道 → source = "hybrid"
│   └─ 按 RRF 分数降序排列
│
├─ Stage 7: 父块提升 (ParentBlockElevationService.elevate)
│   │
│   ├─ 收集所有 parentBlockId
│   ├─ 批量 MySQL 查询:
│   │   SELECT id, parent_text
│   │   FROM reuben_agent_document_parent_block
│   │   WHERE id IN (...) AND is_deleted = 0
│   │
│   ├─ 替换: chunkText → parentText
│   ├─ source 追加 "+parent"
│   └─ 按 parentBlockId 去重 (保留最高分)
│
├─ Stage 8: Rerank 重排序 (RerankService.rerank)
│   │  [默认禁用, rerank.enabled=false]
│   │
│   ├─ 如果启用:
│   │   ├─ HTTP POST → 外部 cross-encoder API
│   │   │   e.g. SiliconFlow BAAI/bge-reranker-v2-m3
│   │   ├─ request: { model, query, documents, top_n }
│   │   ├─ response: { results: [{ index, relevance_score }] }
│   │   ├─ 更新 score = relevance_score
│   │   ├─ 原分存入 rerankScore 字段
│   │   └─ source 追加 "+rerank"
│   └─ 异常 → 返回原列表 (不降级)
│
└─ Stage 9: 截断
    └─ limit finalTopK=5
```

### 4.2 降级策略汇总

| 阶段 | 失败场景 | 降级行为 |
|------|---------|---------|
| 改写 | LLM 调用失败 | 返回原问题，不阻塞 |
| 向量检索 | 超时/EmbeddingModel 缺失 | 空列表，继续执行 |
| 关键词检索 | 超时/ES 不可用 | 空列表，继续执行 |
| Rerank | HTTP 超时/API 错误 | 跳过，保持原排序 |
| 父块提升 | DB 查询失败 | 跳过，使用 chunk 原文 |

---

## 五、链路三：知识路由 (Knowledge Route)

### 5.1 三级路由架构

```
KnowledgeRouteServiceImpl.route(question, rewriteQuestion)
│
├─ 0. 前置检查
│   └─ config.enabled == false → return FAILED
│
├─ 1. buildQueryContext(question, rewriteQuestion)
│   ├─ routingText = question + " " + rewriteQuestion (拼接)
│   ├─ KnowledgeRouteTokenizer.tokenize(routingText)
│   │   ├─ 正则分词 (按分隔符)
│   │   ├─ 中文 n-gram 扩展 (4字以上词片段的 2-6 gram)
│   │   ├─ 去重
│   │   └─ 截断 maxCount=40
│   └─ ObjectProvider<EmbeddingModel>.embed(routingText) → queryEmbedding
│       └─ 无 EmbeddingModel → null (语义通道不参与)
│
├─ 2. rankScopes(ctx)   ←── 第一层: 领域路由
│   │
│   ├─ 加载 KnowledgeScopeNode 列表
│   │   └─ scope 表为空 → deriveScopesFromDocuments() 从 Document 表聚合
│   │
│   └─ rankByEntityType(scopes, ctx, "scope")
│       │
│       ├─ 批量嵌入所有 scope 的 name+description
│       ├─ ES 词法搜索: 搜 scope 的 name/description/code
│       ├─ 逐 scope 评分:
│       │   │
│       │   │  总分 = semanticScore + lexicalScore + keywordHitScore
│       │   │
│       │   ├─ semanticScore:
│       │   │   max(0, (cosineSimilarity - semanticFloor) * semanticWeight)
│       │   │   semanticFloor = 0.20, semanticWeight = 50.0
│       │   │   (低于 floor 的语义分归零)
│       │   │
│       │   ├─ lexicalScore:
│       │   │   min(lexicalCap, esScore * lexicalWeight)
│       │   │   lexicalCap = 10.0, lexicalWeight = 1.6
│       │   │   (ES 分数封顶)
│       │   │
│       │   └─ keywordHitScore:
│       │       matchedTerms * entityHitScore
│       │       entityHitScore = 6.0
│       │       (查询词与实体字段的直接命中)
│       │
│       └─ 排序截断: maxScopeCandidates=5
│
├─ 3. rankTopics(ctx, topScopes)  ←── 第二层: 主题路由
│   │
│   ├─ 加载 KnowledgeTopicNode (可按 scope 过滤)
│   │   └─ topic 表为空 → deriveTopicsFromProfiles() 从 DocumentProfile 提取
│   │
│   ├─ 同 rankByEntityType 评分引擎
│   │
│   ├─ Scope Boost: 如果 topic 的 scope 匹配 top scope
│   │   └─ score += scopeBoostTopic = 8.0
│   │
│   └─ 排序截断: maxTopicCandidates=8
│
├─ 4. rankDocuments(ctx, topScopes, topTopics)  ←── 第三层: 文档路由
│   │
│   ├─ 加载 Document 实体列表
│   │
│   ├─ 同 rankByEntityType 评分引擎
│   │
│   ├─ Scope Boost: 文档匹配 top scope
│   │   └─ score += scopeBoostDocument = 15.0
│   │
│   ├─ Topic Boost: 文档匹配 top topic
│   │   └─ score += scopeBoostDocument = 15.0
│   │
│   ├─ Relation Weight: 加载 TopicDocumentRelation 表
│   │   └─ score *= (1 + relationScoreWeight)  → relationScoreWeight=20.0
│   │
│   └─ 排序截断: maxDocumentCandidates=5
│
├─ 5. resolveConfidence()
│   │
│   │  公式: topScore / (topScore + secondScore + normalizerOffset)
│   │  其中 normalizerOffset = max(1, normalizerBase / max(1, topScore))
│   │  normalizerBase = 10.0
│   │
│   │  效果: 当 top 与 second 差距大时，置信度接近 1.0
│   │        差距小时置信度降低
│   │
│   └─ determineStatus():
│       ├─ confidence >= lowConfidenceThreshold (0.55) → SUCCESS
│       └─ confidence < 0.55 → LOW_CONFIDENCE
│
└─ 6. 返回 KnowledgeRouteDecision
    { scopes, topics, documents, confidence, status, reason }
```

### 5.2 评分公式总览

```
对于候选实体 e，查询上下文 ctx:

semanticScore(e) = max(0, (cosine(e.embedding, ctx.embedding) - 0.20) × 50.0)
lexicalScore(e)  = min(10.0, esBM25Score(e, ctx.routingText) × 1.6)
keywordHitScore(e) = count(e.fields ∩ ctx.queryTerms) × 6.0

score(e) = semanticScore + lexicalScore + keywordHitScore
         + scopeMatchBoost(8.0 or 15.0)
         + relationWeight(× 20.0)
```

### 5.3 两种路由模式

| 模式 | 枚举值 | 行为 | 使用场景 |
|------|--------|------|---------|
| SHADOW | `KnowledgeRouteMode.SHADOW` | 评估路由结果，记录 trace，但不干预文档选择 | DOCUMENT 模式（用户已手动选文档） |
| AUTO | `KnowledgeRouteMode.AUTO` | 路由结果自动注入 selectedDocumentId | AUTO_DOCUMENT 模式 |

---

## 六、链路四：文档处理管线 (Document Pipeline)

### 6.1 上传与解析 (Stages 1-4, 同步 + 异步)

```
POST /api/document/upload  (multipart/form-data)
│
├─ 1. 文件校验
│   ├─ 非空检查
│   └─ 类型检查: PDF/DOC/DOCX/TXT/MD/HTML
│
├─ 2. MinIO 存储
│   └─ documentStorageService.upload(originalFile)
│       → bucket: reuben-agent-document
│       → prefix: rag/document/{documentId}/original
│
├─ 3. 事务写入 (TransactionTemplate)
│   ├─ Document 实体 (雪花 ID)
│   ├─ DocumentTask { type=PARSE_ROUTE, status=NEW }
│   └─ DocumentTaskLog { stage=PARSE_ROUTE, message="任务已创建" }
│
└─ 4. Kafka 投递
    └─ DocumentKafkaProducer.sendParseRoute(
         DocumentParseRouteMessage { documentId, taskId }
       )
       → Topic: reuben-agent-document-parse-route
```

#### 异步解析管线 (Kafka Consumer → handleParseStrategyRoute)

```
DocumentKafkaConsumer.consumeParseRoute()
│   └─ 反序列化 JSON → 调用 asyncProcessService.handleParseStrategyRoute()
│
├─ Stage 1: CONTENT_PARSE — 内容解析
│   │
│   ├─ 1a. 从 MinIO 下载原文件字节
│   │
│   ├─ 1b. DocumentParseResultServiceImpl.parse()
│   │   ├─ TXT/MD: 直接 UTF-8 解码
│   │   ├─ PDF/DOC/DOCX/HTML: Apache Tika 提取
│   │   │   └─ Tika 失败 → UTF-8 兜底 (text/* 类型)
│   │   ├─ cleanupText()
│   │   │   ├─ 统一换行符
│   │   │   ├─ 移除 null 字符
│   │   │   └─ 压缩冗余空白/空行
│   │   └─ DocumentStructureNodeExtractor.extract(title, cleanedText)
│   │       │
│   │       ├─ Stage 1: SignalExtractor — 信号提取
│   │       │   ├─ 按逻辑行分割 (处理压缩多行)
│   │       │   ├─ 16级优先级逐行分类:
│   │       │   │   优先级从高到低:
│   │       │   │   HEADING (Markdown # / 章节序号 / 编号+空格)
│   │       │   │   → HEADING_CANDIDATE (疑似标题)
│   │       │   │   → LIST_ITEM (bullet/checkbox/ordered)
│   │       │   │   → TABLE_ROW / QUOTE / STEP_ITEM
│   │       │   │   → BODY (正文)
│   │       │   │   → BLANK / NOISE (重复页眉页脚/版权/版本号)
│   │       │   ├─ O(n) 双遍上下文计算 (前后非空行)
│   │       │   └─ 输出: DocumentStructureNodeSignalBatch
│   │       │
│   │       ├─ Stage 2: AmbiguityResolver — 歧义消解 [可选, LLM]
│   │       │   │  条件: llmDisambiguationEnabled=true
│   │       │   │
│   │       │   ├─ 筛选 HEADING_CANDIDATE 信号
│   │       │   │   (confidence ∈ [0.45, 0.80])
│   │       │   ├─ 批量 LLM 判定 (maxAmbiguousSignalsPerCall=8)
│   │       │   │   上下文窗口 contextWindowLines=4
│   │       │   └─ 重分类: HEADING / LIST_ITEM / BODY
│   │       │
│   │       ├─ Stage 3: HierarchyResolver — 层级解析
│   │       │   ├─ 信号 → 树形草稿节点
│   │       │   └─ 确定父子关系、深度
│   │       │
│   │       └─ Stage 4: TreeValidator — 树验证修复
│   │           ├─ 六步修复验证
│   │           ├─ 重建兄弟链接
│   │           ├─ 规范化路径
│   │           └─ 合并重复标题
│   │
│   ├─ 1c. 上传解析后纯文本到 MinIO
│   │
│   ├─ 1d. 质量评估:
│   │   ├─ headingCount (CHAPTER 类型 + depth>0)
│   │   ├─ paragraphCount (按双换行分割)
│   │   ├─ tokenEstimate (中文1字1token, 英文1词1token)
│   │   ├─ structureLevel: >=5标题→高(3), >=2→中(2), >=3段→低(1), else→未知(0)
│   │   └─ contentQuality: U+FFFD占>2%→低(1), >0.5%→中(3), else→高(5)
│   │
│   └─ 更新 task stage = CONTENT_PARSE (完成)
│
├─ Stage 2: Structure Node Persistence
│   ├─ structureNodeService.saveNodes() → 持久化树节点
│   └─ 后台 CompletableFuture (不阻塞):
│       ├─ Neo4j 图投影 (可选, neo4j.enabled=true)
│       └─ ES 导航索引重建 (可选)
│
├─ Stage 3: Document Profile
│   └─ documentProfileService.generateProfile()
│       └─ LLM 生成文档画像 (coreTopics, summary, domain...)
│
└─ Stage 4: Strategy Recommendation
    └─ strategyService.recommendStrategy()
        │
        ├─ 决策: 用哪种 Parent Pipeline?
        │   ├─ shouldUseStructure (structured + structureLevel≥2 或 headingCount≥2)
        │   │   └─ → STRUCTURE (按章节划分)
        │   └─ 否则
        │       └─ → RECURSIVE (递归固定长度)
        │
        ├─ 决策: 用哪种 Child Pipeline? (1-2步)
        │   ├─ shouldUseLlm (低质量文档 + contentQuality≥1 + 文本够长)
        │   │   └─ → LLM + RECURSIVE (兜底)
        │   ├─ shouldUseSemantic (≥1000字 + ≥3段 + contentQuality≥3)
        │   │   └─ → SEMANTIC + RECURSIVE (兜底)
        │   └─ 否则
        │       └─ → RECURSIVE only
        │
        ├─ 角色分配 (按 pipeline 位置):
        │   ├─ 第一步 = PRIMARY (替换结果)
        │   ├─ 中间步 = OPTIMIZE (合并/拆分优化)
        │   └─ 最后步 = FALLBACK (仅在前序都空时执行)
        │
        └─ 持久化: DocumentStrategyPlan (status=WAIT_CONFIRM)
```

### 6.2 策略确认与索引构建 (Stages 5-8)

```
用户确认策略
│   POST /api/document/strategy/confirm
│   { planId, parentSteps[], childSteps[] }
│
├─ 校验: plan.status == WAIT_CONFIRM
├─ 如果用户调整了步骤:
│   └─ 签名比对 → 新版 plan (source=USER_ADJUST)
│
├─ 创建 BUILD_INDEX task + taskLog
├─ plan.status = CONFIRMED
│
└─ Kafka 投递
    └─ DocumentKafkaProducer.sendIndexBuild(
         DocumentIndexBuildMessage { documentId, taskId, planId }
       )
       → Topic: reuben-agent-document-index-build
```

#### 异步索引构建管线

```
DocumentKafkaConsumer.consumeIndexBuild()
│   └─ asyncProcessService.handleIndexBuild()
│
├─ Stage 5: CHUNK_EXECUTE — 切块执行
│   │
│   ├─ 从 MinIO 下载解析文本
│   ├─ 加载策略步骤
│   │
│   └─ strategyService.buildParentBlocks()
│       │
│       ├─ 加载 DocumentStructureNode 列表
│       │
│       ├─ Parent Pipeline (父管道: 产生大纲块):
│       │   │
│       │   ├─ [STRUCTURE 策略]
│       │   │   └─ applyStructureChunking()
│       │   │       收集 CHAPTER 子树文本 → ParentBlockCandidate
│       │   │
│       │   └─ [RECURSIVE 策略]
│       │       └─ applyRecursiveChunking()
│       │           3级分块: paragraph → sentence → fixedWindow+overlap(200)
│       │           maxChars=3000
│       │
│       ├─ 对每个 Parent 种子, 执行 Child Pipeline:
│       │   │
│       │   ├─ [LLM 策略]
│       │   │   └─ applyLlmChunking()
│       │   │       ├─ 发送带索引的句子视图给 LLM
│       │   │       ├─ 解析断点索引
│       │   │       └─ LLM 失败 → 回退 RECURSIVE
│       │   │
│       │   ├─ [SEMANTIC 策略]
│       │   │   └─ applySemanticChunking()
│       │   │       句子 Jaccard 相似度 (1-2 gram)
│       │   │       阈值: 0.7
│       │   │
│       │   └─ [RECURSIVE 策略]
│       │       └─ 同上 3 级分块
│       │
│       ├─ 继承父元数据: sectionPath, structureNodeId
│       └─ 去重
│
├─ Stage 6: CHUNK_POST_PROCESS — 切块后处理
│   │
│   ├─ 校验候选 (isValidParentBlock)
│   ├─ 构建实体:
│   │   ├─ DocumentParentBlock (雪花 ID)
│   │   └─ DocumentChunk[] (雪花 ID)
│   └─ 批量 INSERT MySQL
│
├─ Stage 7: VECTORIZE — 向量化入库
│   │
│   ├─ vectorGateway.vectorize(chunks)
│   │   ├─ 按 batchSize=10 分批
│   │   ├─ EmbeddingModel.embed(batch) → vectors
│   │   ├─ JDBC batch UPSERT → PGVector
│   │   │   ON CONFLICT (id) DO UPDATE
│   │   └─ 更新每 chunk vectorStatus: SUCCESS/FAILED
│   │
│   └─ keywordSearchGateway.indexChunks(successChunks)
│       └─ ES bulk index → reuben_document_chunk
│           (analyzer: ik_max_word)
│
└─ Stage 8: STORE_COMPLETE — 完成收尾
    ├─ plan.status = EXECUTED
    ├─ planSteps 全部 → SUCCESS
    ├─ document.indexStatus = BUILD_SUCCESS
    └─ task.status = SUCCESS (记录 timing stats)
```

### 6.3 四种切块策略对比

```
                     STRUCTURE          RECURSIVE           SEMANTIC            LLM
──────────────────────────────────────────────────────────────────────────────────────
  原理             按章节层级切       固定窗口+重叠      句子相似度聚合      LLM 识别语义边界
  输入             结构树节点         纯文本              纯文本             带索引的句子视图
  粒度             章/节级别          3000字窗口          语义段落            LLM 判定
  适用场景         结构化文档         无结构文档          长文本高质量文档    低质量需理解文档
  重叠             无                 200字              无                 无
  兜底             无                 自身                自身               RECURSIVE
  父/子管道        仅父管道           均可                仅子管道           仅子管道
  角色             通常 PRIMARY       通常 FALLBACK       通常 PRIMARY       通常 PRIMARY
```

---

## 链路五：图导航系统 (Graph Navigation)

### 7.1 复合图服务架构

```
CompositeDocumentStructureGraphService (@Primary)
│
├─ route(documentId)  ←── 每文档路由决策:
│   │
│   ├─ Step 1: neo4jGraphServiceProvider.getIfAvailable()
│   │   └─ null (Neo4j 未启用) → 直接使用 MySQL
│   │
│   ├─ Step 2: CompositeGraphAvailabilityCache.get(documentId)
│   │   ├─ FALSE → 使用 MySQL (已知不可用)
│   │   └─ null (未知) → 调用 neo4j.isGraphAvailable()
│   │       ├─ 返回 true → 缓存 TRUE → 使用 Neo4j
│   │       └─ 返回 false → 缓存 FALSE → 使用 MySQL
│   │
│   ├─ Step 3: 尝试 Neo4j
│   │   └─ DocumentException → 回退 MySQL
│   │
│   └─ Step 4: 执行查询 (Neo4j Cypher 或 MySQL in-memory)
│
├─ Neo4jDocumentStructureGraphService (条件 Bean)
│   ├─ 标签: Section (ROOT/CHAPTER), Item (STEP/LIST_ITEM)
│   ├─ 关系: HAS_CHILD, HAS_ITEM, BELONGS_TO_DOCUMENT, NEXT_SIBLING, PREV_SIBLING
│   ├─ 8 个索引 (@PostConstruct 创建)
│   └─ section 评分: path(100) > title(90) > anchor(80) > content(45) > facet(1-5)
│
└─ MysqlDocumentStructureGraphService (始终可用)
    ├─ ConcurrentHashMap<documentId, List<Node>> 缓存
    ├─ 节点数 > cacheNodeThreshold(2000) → 每次 DB 直查
    └─ 同评分公式
```

### 7.2 图查询 API

| 方法 | 用途 | 查询方式 |
|------|------|---------|
| `findSectionByCode` | 按章节编码查 (1.2.3) | Neo4j: MATCH WHERE nodeCode = $code / MySQL: stream filter |
| `findSectionByTitle` | 按标题查 | Neo4j: MATCH WHERE title CONTAINS / MySQL: stream filter |
| `findBestSection` | 综合评分找最佳匹配 | 加载全部节点 → 内存多维度评分排序 |
| `listChildren` | 列出子章节 | Neo4j: HAS_CHILD 关系 / MySQL: parentNodeId 匹配 |
| `previousSibling / nextSibling` | 前后兄弟章节 | 使用 prevSiblingNodeId/nextSiblingNodeId 字段 (不依赖关系方向) |
| `findItemByIndex` | 按条目序号查 | Neo4j: MATCH WHERE itemIndex = $index / MySQL: stream filter |
| `searchItemsInSection` | 章节内条目搜索 | 递归收集 → 文本匹配 |

---

## 链路六：可观测性追踪

### 8.1 追踪架构

```
ChatTraceRecorder (每轮一个实例)
│
├─ 阶段追踪:
│   ├─ startStage(ChatTraceStageCode) → 记录开始时间
│   ├─ completeStage(code, detail) → 记录耗时+详情
│   └─ failStage(code, error) → 记录异常
│
├─ Model Usage 追踪:
│   └─ traceSink() → Consumer<ChatModelUsageTrace>
│       每次 LLM 调用自动记录:
│       { stageName, provider, model, inputTokens,
│         outputTokens, cost, durationMs, status }
│
├─ 持久化:
│   └─ ChatTraceStageStore (MyBatis 实现)
│       全部阶段 + usage → chat_trace_stage 表
│
└─ 聚合查询:
    └─ ChatStageBenchmarkService
        P50 / P90 / P99 延迟统计 (按 stageCode 分组)
```

### 8.2 10 个追踪阶段

```
 1. MEMORY           → 记忆加载耗时
 2. INTENT           → 意图识别耗时
 3. REWRITE          → 查询改写耗时
 4. ROUTE            → 模式路由耗时 (含 Knowledge Route)
 5. RAG_RETRIEVE     → RAG 检索耗时 (含双通道+RRF+Rerank)
 6. EVIDENCE_BUDGET  → 证据门控耗时
 7. ANSWER_GENERATE  → LLM 生成回答耗时
 8. REACT_AGENT      → ReAct Agent 执行耗时
 9. RECOMMENDATION   → 追问生成耗时
10. FINALIZE         → 收尾落库耗时
```

---

## 七：可观测性 API

| 端点 | 用途 |
|------|------|
| `GET /api/chat/exchange/detail?turnId=` | 轮次完整追踪 (阶段+usage+debug) |
| `GET /api/chat/exchange/retrieval/results?turnId=` | 检索结果详情 (每结果 score+source) |
| `GET /api/chat/exchange/channel/executions?turnId=` | 通道执行详情 (vector/keyword 各自耗时+结果数) |
| `GET /api/chat/stage/benchmarks` | 各阶段 P50/P90/P99 延迟 |

---

## 八：关键技术决策总览

| 决策 | 方案 | 原因 |
|------|------|------|
| ID 生成 | 雪花算法 (28+22+13 bit) | 分布式唯一，无需 DB 自增 |
| 异步解耦 | Kafka (KRaft 模式) | 文档处理耗时，异步不阻塞用户 |
| 分布式锁 | Redis SET NX PX + Lua | 同一会话串行，避免并发写冲突 |
| 图存储 | Neo4j + MySQL 双模 | Neo4j 遍历性能好但非必需；MySQL 免额外依赖 |
| 向量检索引擎 | PGVector (pgvector 扩展) | 与业务 DB 统一，减少中间件 |
| 关键词检索引擎 | Elasticsearch + ik_max_word | 中文分词 + BM25 相关性 |
| MCP/Agent | Spring AI Alibaba ReactAgent | 开箱即用的 ReAct 循环 + 工具调用 |
| SSE 协议 | 自定义 JSON 事件流 | 支持 typewriter 效果 + 结构化元数据 |
| 追踪系统 | MyBatis 持久化 + 内存计算 | 每轮可追溯，支持聚合统计 |
| 配置管理 | @ConfigurationProperties 嵌套类 | 强类型，IDE 提示，按模块分组 |

---

## 九：系统拓扑 (部署视角)

```
                    ┌──────────────┐
                    │   Browser    │
                    │  React SPA   │
                    └──────┬───────┘
                           │ HTTP + SSE
                    ┌──────▼───────┐
                    │   Launcher   │
                    │ Spring Boot  │
                    │  :8080       │
                    └──────┬───────┘
                           │
        ┌──────────────────┼──────────────────┐
        │                  │                  │
   ┌────▼────┐      ┌──────▼──────┐    ┌──────▼──────┐
   │  MySQL  │      │  PGVector   │    │    Redis    │
   │  :3307  │      │  :5432      │    │    :6379    │
   └─────────┘      └─────────────┘    └─────────────┘
        │
   ┌────▼────┐      ┌─────────────┐    ┌─────────────┐
   │  MinIO  │      │ Elasticsearch│    │    Neo4j    │
   │ :9000   │      │   :9200     │    │  :7687      │
   └─────────┘      └─────────────┘    └─────────────┘
                           │
                    ┌──────▼──────┐
                    │    Kafka    │
                    │   :9092     │
                    │ (KRaft 模式) │
                    └─────────────┘
```

所有中间件通过 `docker-compose.yml` 一键启动。MySQL 使用端口 3307 以避免与其他本地项目冲突。
