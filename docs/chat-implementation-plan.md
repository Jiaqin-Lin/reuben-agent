# 对话模块（chat / agent / memory）实现计划

> 进度勾选约定：每个任务条目前 `[ ]` 完成后改 `[x]`。Phase 级勾选在 Phase 标题旁。
>
> 来源对标：`/Users/reuben/Desktop/super-agent/super-agent-business/super-agent-business-chat`（`org.javaup.ai.chatagent` + `org.javaup.ai.manage` 的知识路由部分）。
>
> ⚠️ 禁止做阉割版本。super-agent 有的能力都要对齐：多执行模式、ReAct Agent、RAG 检索回答、联网搜索工具、记忆压缩、全链路追踪可观测、推荐追问、停止/重置会话。

---

## 背景与约束

### 项目背景

reuben-agent 已完成 **document（上传→解析→结构→策略→索引）** 与 **rag（改写→双通道→RRF→父块提升→Rerank）** 两大核心管线。数据已落 PGVector + ES，`POST /api/rag/retrieve` 可用。下一步是对话：把 `business/chat`（当前只有 `.gitkeep` 空壳，13 个包目录已建好）补成完整模块。

目标：**功能上对齐 super-agent 的对话能力**，**风格按 reuben-agent 走**，同时修正 super-agent 一批命名 / 过度防御性编程 / 业务处理问题。

### super-agent 对话域全景（移植来源）

```
super-agent-business-chat / org.javaup.ai.chatagent
├── controller/BusinessChatController        /api/chat: stream(SSE) + session CRUD + exchange 查询 + retrieval/trace 观测
├── service/
│   ├── BusinessChatService (1382行, god-class)  SSE 生命周期 + 租约 + 编排 + 持久化 + 视图装配
│   ├── ConversationArchiveStore / Mybatis...   会话/轮次持久化 (dialogue + exchange)
│   ├── ChatRuntimeRegistry                     内存态在途任务表
│   ├── ChatCheckpointManager                   包装 Alibaba MysqlSaver (ReAct 工作记忆)
│   ├── ObservedChatModelService                ChatModel 包装 + token/成本/耗时追踪
│   ├── ConversationMemoryService / Persistent...  摘要压缩记忆 (recent window + long-term summary)
│   ├── ConversationTraceRecorder + Stage/RetrievalObserve Store   全链路追踪持久化
│   ├── StageBenchmarkService                   P50/P90/P99 滑窗
│   └── RecommendationService                   LLM 生成追问
├── rag/
│   ├── model/ExecutionMode                     GRAPH_ONLY / GRAPH_THEN_EVIDENCE / RETRIEVAL / REACT_AGENT / CLARIFICATION
│   ├── executor/ConversationExecutor + Registry + 5个Executor   策略分发
│   ├── service/ChatPreparationOrchestrator (806行)  决策大脑: 记忆→改写→模式路由→导航
│   ├── service/ChatQueryRewriteService         LLM 改写 + 子问题拆分
│   ├── service/RagRetrievalEngine              逐子问题双通道检索+RRF+rerank
│   ├── service/RagPromptAssemblyService        组装最终 prompt
│   └── service/StructureGraphQueryEngine 等     结构图查询/渲染/文档路由
├── tool/TavilySearchTool                       唯一工具: 联网搜索
├── support/                                    SSE 事件序列化 / DashScope 兼容拦截器 / 时间敏感查询启发式
├── data/ + mapper/                             9 张表实体
└── prompt/                                     18 个 .st 模板
```


### reuben-agent 已有基础（可直接复用 / 对接）

- **RAG 检索入口** `IRagRetrievalService.retrieve(RagRetrieveRequest) → RagRetrieveResponse`（`List<RetrievalResult>`，含 chunkText 父块提升、score、rerankScore、documentId、sectionPath）。chat 模块按类型注入该 Bean，**不声明 Maven 依赖**（business 模块间禁止编译期依赖，靠 launcher 聚合）。
- **LLM 抽象** Spring AI `ChatModel`（`deepSeekChatModel` 为主），`ChatClient` 自动配置已被排除。注入方式：必选用 `@Qualifier("deepSeekChatModel")`，可选用 `ObjectProvider<ChatModel>`。
- **Prompt 模板** document 模块有 `PromptTemplateService`（`.st` 文件 + `<placeholder>`），chat 模块自带一份。
- **基础设施** `ApiResponse` / `GlobalExceptionHandler` / `BaseEnum`+`EnumUtils` / `BaseTableData` / `UidGenerator` / MyBatis-Plus / FastJSON / Kafka / Redis / 通用 `PageVo`。
- **chat 包骨架** `business/chat/src/main/java/com/reubenagent/chat/{agent,config,controller,dto,entity,enums,mapper,model,orchestrate,service/impl,session,vo}` 已建好空目录。

### 代码风格约束（来自 CLAUDE.md + memory，不可妥协）

1. 业务层禁止 `throw new RuntimeException("字符串")`，必须抛 `BusinessException` 子类（chat 模块建 `ChatException` + `ChatErrorCode`）。
2. 所有 code/msg 枚举 `implements BaseEnum`，`getFromCode` 委托 `EnumUtils`。
3. Controller 统一返回 `ApiResponse<T>`，异常交 `GlobalExceptionHandler`，Controller 内禁止 catch+error。
4. 实体继承 `BaseTableData`，`@TableName("reuben_agent_chat_*")`，`@TableId(type=IdType.INPUT)`，雪花 ID，`@Builder`。
5. 注入用 `@AllArgsConstructor` 构造器注入，禁止 `@Autowired` 字段注入，Service 禁止 `@RequiredArgsConstructor`。
6. 连续 3+ `setXxx()` 用 `@Builder`。
7. 注释简洁：`// 阶段 N：做什么`，不要大段分隔线 / 教材式注释。
8. 热路径 `String.matches()` 预编译为 `static final Pattern`。
9. `@Slf4j` 声明就要在关键路径（状态变更/异常/外部调用）用日志。

### super-agent 问题 → reuben-agent 优化方向（移植时一并修正）

| # | super-agent 问题 | reuben-agent 优化方向 |
|---|-----------------|---------------------|
| 1 | `BusinessChatService` 1382 行 god-class，混 HTTP/租约/SSE/持久化/视图装配 | 拆为 `ChatStreamOrchestrator`(SSE+租约) + `ChatSessionService`(会话CRUD) + `ChatViewAssembler`(视图) |
| 2 | DTO 全用 `String`（chatMode/exchangeId/pageNo…），Service 内 `parsePositiveInt`/`parseRequiredLong` 防御性解析 | DTO 用强类型（`Integer`/`Long`/枚举）+ `@Valid` `@NotNull`，删掉所有 parse 辅助方法 |
| 3 | 硬编码中文错误串返回客户端（"该会话当前正在执行中"） | 用 `ChatErrorCode` 枚举 + `ChatException`，message 由 Handler 统一拼装 |
| 4 | `finishSuccessfully`/`finishWithFailure`/`stopTask` 三个 80 行 try/catch/finally 梯度重复 | 抽 `finalize(taskInfo, status, errorMessage)` 单方法 + CAS 幂等 |
| 5 | 大量 `catch(RuntimeException)` 吞异常继续（releaseLeaseQuietly / safeRefresh…） | 关键路径异常不吞，落到 `ChatErrorCode`；可降级路径 warn 后走 fallback，但必须打日志 |
| 6 | `ObservedChatModelService` 用字符串匹配类名识别 provider；状态串 "COMPLETED"/"FAILED" 不是枚举 | provider 由配置注入；状态用 `ChatModelCallStatus` 枚举 |
| 7 | `DashScopeCompatibilityInterceptor` 555 行，按 base-url 字符串识别厂商，INFO 打全量 message | DeepSeek 为主，移除 DashScope 兼容；如保留多厂商兼容，provider 走配置，日志降级到 debug |
| 8 | 类名 `SuperAgentChat*` 带品牌前缀 | 统一 `Chat*` 无前缀（`ChatDialogue` / `ChatExchange` …） |
| 9 | DB 列名 `dialogue_code`/`dialogue_stage` 与 Java 名语义错位 | 直接用语义列名 `conversation_id`/`session_status`，消除错位映射 |
| 10 | `@Deprecated RAG_CHAT` 执行模式为历史数据保留 | 不移植废弃值 |
| 11 | `ChatRuntimeRegistry` 单实例内存态，无法跨实例停止 | 本期保留内存态（单实例够用），但 `remove` 一律用 2-arg 版本，单 arg 版不实现 |
| 12 | `StageBenchmarkService` 非原子 read-modify-write 并发覆盖 | 用 Redis 原子结构（list + ltrim）或单线程聚合；本期可降级为定时聚合 |
| 13 | 魔法常量散落（0.55 / 3 / 8 / 256 / 2200…） | 全部进 `ChatProperties` 嵌套配置，带默认值 |
| 14 | `JSON_OBJECT_PATTERN = "\\{.*}"` 贪婪提取 LLM 输出 JSON | 用 FastJSON `JSON.parseObject` + 容错提取首个平衡花括号 |
| 15 | `clipText`/`safeText`/`toInt` 在 5+ 个类重复 | 抽到 `chat.support.ChatTexts` 工具类 |
| 16 | `RecommendationService` 双层 catch 吞异常返回空 list，失败对用户不可见 | 失败时记 warn + 追踪落 `ChatDebugTrace`，前端可显示"追问生成失败"占位 |
| 17 | entity 名 `SuperAgentChatDialogue`/`Exchange` 混 `dialogue`/`exchange` 两种叫法 | 统一为 `ChatConversation`（会话）+ `ChatTurn`（一轮问答）；SQL 表 `reuben_agent_chat_conversation` / `reuben_agent_chat_turn` |
| 18 | `ChatQueryMode` 枚举 code 顺序与声明顺序不一致易误读 | code 严格按声明顺序 1..n |

### 模块边界与对接约定

- chat 模块**只依赖** common + framework（Maven）。rag / document 的能力通过 Spring Bean 按类型注入（`IRagRetrievalService`），靠 launcher 聚合到同一 classpath。
- chat 自身需要的中间件配置（LLM、Tavily、记忆线程池、检索调参）放 `ChatProperties`（prefix `reuben.chat`）。
- 追踪/记忆用到的 JSON 列统一存 FastJSON 字符串，实体字段为 `String`，读写由对应 Store 做 (de)serialize（容错降级，不抛 IllegalStateException 中断列表查询）。

---

## Phase 总览

| Phase | 主题 | 产出 | 依赖 |
|-------|------|------|------|
| 0 | 地基：异常/枚举/配置/SQL/实体/Mapper | 可编译的空骨架 + 建表脚本 | — |
| 1 | 会话与轮次持久化（session） | `ChatSessionService` + Archive Store | Phase 0 |
| 2 | SSE 流式地基 + 运行时任务表 | `ChatStreamOrchestrator` 骨架 + `ChatRuntimeRegistry` + `TaskInfo` + 事件序列化 | Phase 1 |
| 3 | 可观测 ChatModel + Prompt 模板 | `ObservedChatModelService` + `ChatPromptTemplateService` + `.st` | Phase 0 |
| 4 | 对话记忆（memory） | `ChatMemoryService`（recent window + 摘要压缩）+ checkpoint | Phase 1, 3 |
| 5 | 查询改写 + 执行模式决策（orchestrate） | `ChatQueryRewriteService` + `ChatPreparationOrchestrator` + `ExecutionMode` | Phase 3, 4 |
| 6 | RAG 检索回答通道（rag-at-chat） | `RagAnswerExecutor` + prompt 组装 + 引用映射 | Phase 3, 5 |
| 7 | ReAct Agent + 联网工具（agent） | `ReactAgentExecutor` + `TavilySearchTool` + 拦截器 | Phase 2, 3 |
| 8 | 多模式执行分发 + 全链路追踪 | `ConversationExecutorRegistry` + 5 执行器 + Trace Recorder/Store | Phase 5–7 |
| 9 | 推荐追问 + 停止/重置/重建摘要 + 观测查询 API | `RecommendationService` + 会话控制 + retrieval/trace 查询 | Phase 8 |
| 10 | Controller + 配置装配 + 集成测试 | `/api/chat` 全量接口 + yaml + Docker 集成测试 | Phase 9 |

---

## Phase 0 — 地基：异常 / 枚举 / 配置 / SQL / 实体 / Mapper  `[x]`

**产出**：chat 模块可编译的空骨架 + 完整建表脚本 + 全部实体与 Mapper。

- [x] **0.1 异常体系**
  - [x] `ChatErrorCode implements BaseEnum`（code 段 `30001–30099`，覆盖：会话不存在/轮次不存在/会话执行中/租约获取失败/生成失败/改写失败/检索失败/记忆压缩失败/追问生成失败/工具调用失败/参数校验失败 等）
  - [x] `ChatException extends BusinessException`，构造绑定 `ChatErrorCode`，message 取 `msg —— detail`，参考 `DocumentException`

- [x] **0.2 枚举**（全部 `implements BaseEnum` + `EnumUtils` 委托）
  - [x] `ChatMode`：DOCUMENT(1) / OPEN_CHAT(2) / AUTO_DOCUMENT(3) —— code 严格按声明顺序
  - [x] `ChatTurnStatus`：RUNNING(1) / COMPLETED(2) / FAILED(3) / STOPPED(4)
  - [x] `ChatSessionStatus`：IDLE(1) / RUNNING(2)
  - [x] `ExecutionMode`：GRAPH_ONLY / GRAPH_THEN_EVIDENCE / RETRIEVAL / REACT_AGENT / CLARIFICATION（不移植 `RAG_CHAT`）
  - [x] `ChatTraceStageCode`：MEMORY / INTENT / REWRITE / ROUTE / RAG_RETRIEVE / EVIDENCE_BUDGET / ANSWER_GENERATE / REACT_AGENT / RECOMMENDATION / FINALIZE（带 order）
  - [x] `ChatTraceStageState`：RUNNING / COMPLETED / FAILED / SKIPPED
  - [x] `ChatModelCallStatus`：RUNNING / COMPLETED / FAILED（替代 super-agent 的字符串字面量）
  - [x] `ChatToolStatus`：CALLING / SUCCESS / FAILED

- [x] **0.3 配置**
  - [x] `ChatProperties`（`@ConfigurationProperties(prefix="reuben.chat")`），嵌套：`Agent`（systemPrompt / maxModelCallsPerRun=8 / maxModelCallsPerThread=40 / maxToolCallsPerRun=6 / maxToolCallsPerThread=30 / historyPreviewTurns=4）、`Memory`（keepRecentTurns=4 / compressionBatchTurns=6 / recentTranscriptMaxChars=2200 / 各字段长度上限）、`Rag`（topK / 阈值 / char budget / rerank 开关）、`Recommendation`（enabled / timeoutMs=3000 / maxCount=3）、`Tavily`（baseUrl / apiKey / topic / searchDepth / maxResults / timeout）、`Executor`（三个线程池 size，默认值带注释）、`Trace`（持久化开关）
  - [x] `ChatConfiguration`（`@EnableConfigurationProperties`）
  - [x] 三个线程池 Bean（`chatRagExecutor` / `chatMemoryExecutor` / `chatPostProcessExecutor`），`CallerRunsPolicy`，daemon，size 来自配置
  - [x] `application.yml` 增加 `reuben.chat.*` 段（systemPrompt / recommendationPrompt 默认空，由 yaml 注入；DeepSeek key 复用全局）

- [x] **0.4 SQL 建表**（追加到 `sql/reuben_agent_mysql.sql`，`reuben_agent_chat_*` 前缀，列名语义化，`update_time` 而非 `edit_time`，`is_deleted`）
  - [x] `reuben_agent_chat_conversation`（会话：id / conversation_id(varchar64, 唯一索引) / session_status / chat_mode / selected_document_id / selected_document_name / create_time / update_time / is_deleted）
  - [x] `reuben_agent_chat_turn`（轮次：id / conversation_id / user_prompt / reply_content / reasoning_note_list(json) / source_snapshot_list(json) / followup_suggestion_list(json) / tool_trace_list(json) / debug_trace_json(json) / turn_status / finish_note / first_token_latency_ms / total_latency_ms / create_time / update_time / is_deleted）
  - [x] `reuben_agent_chat_memory_summary`（id / conversation_id(唯一) / covered_turn_id / covered_turn_count / compression_count / summary_version / summary_text / summary_json / last_source_edit_time / create_time / update_time / is_deleted）
  - [x] `reuben_agent_chat_trace_stage`（id / conversation_id / turn_id / trace_id / stage_code / stage_name / stage_order / stage_level / parent_stage_id / execution_mode / stage_state / start_time / end_time / duration_ms / summary_text / error_message / snapshot_json）
  - [x] `reuben_agent_chat_retrieval_result`（id / conversation_id / turn_id / trace_id / sub_question_index / channel_type / 各 rank 与 score / gate_passed / is_elevated / is_selected / selection_reason / doc/chunk/parent 引用 / chunk_text_preview）
  - [x] `reuben_agent_chat_channel_execution`（id / conversation_id / turn_id / trace_id / sub_question_index / channel_type / execution_state / start_time / end_time / duration_ms / recalled_count / accepted_count / final_selected_count / score 统计 / config_snapshot）
  - [x] `reuben_agent_chat_stage_benchmark`（stage_code + execution_mode 唯一 / p50 / p90 / p99 / avg / max / min / sample_count / recent_durations(json)）
  - [x] `reuben_agent_chat_checkpoint` + `reuben_agent_chat_thread`（ReAct 工作记忆；若用 Alibaba MysqlSaver 则沿用其表名 GRAPH_*，否则自建语义表——本计划采用自建轻量 checkpoint 表以脱离 Alibaba 依赖，见 Phase 4 决策）

- [x] **0.5 实体**（`@Data @Builder @NoArgsConstructor @AllArgsConstructor @EqualsAndHashCode(callSuper=true) extends BaseTableData`）
  - [x] `ChatConversation` / `ChatTurn` / `ChatMemorySummary` / `ChatTraceStage` / `ChatRetrievalResult` / `ChatChannelExecution` / `ChatStageBenchmark` / `ChatCheckpoint` / `ChatThread`
  - [x] JSON 列统一 `String` 字段

- [x] **0.6 Mapper**（`I*Mapper extends BaseMapper`，`@Mapper`，无自定义方法除非必要）
  - [x] `IChatConversationMapper` / `IChatTurnMapper` / `IChatMemorySummaryMapper` / `IChatTraceStageMapper` / `IChatRetrievalResultMapper` / `IChatChannelExecutionMapper` / `IChatStageBenchmarkMapper` / `IChatCheckpointMapper` / `IChatThreadMapper`

- [x] **0.7 pom 调整**：`business/chat/pom.xml` 把 `spring-ai-starter-model-openai` 换成 `spring-ai-starter-model-deepseek`（与 document/rag 一致）；新增 `spring-webflux`（SSE Flux）+ `spring-ai-alibaba-graph`（ReAct）按需，确认 parent BOM 已管理版本

- [x] **0.8 编译验证**：`mvn compile -pl business/chat -am` 通过


---

## Phase 1 — 会话与轮次持久化（session）  `[x]`

**产出**：会话/轮次的 CRUD 服务，后续编排与记忆都建立在它之上。对标 super-agent `ConversationArchiveStore` / `MybatisConversationArchiveStore`。

- [x] **1.1 Store 接口与记录**
  - [x] `ChatArchiveStore` 接口：`startConversation` / `completeTurn` / `getConversation` / `listConversations(分页)` / `listRecentTurns(conversationId, limit)` / `deleteConversation` / `countTurns`
  - [x] 记录类型：`ConversationArchiveRecord` / `TurnArchiveRecord` / `ConversationRemovalResult` / `ConversationArchivePage`（用 record 或 `@Builder` DTO）
- [x] **1.2 MyBatis 实现** `ChatArchiveStoreImpl`（`@Repository @AllArgsConstructor`）
  - [x] 会话 upsert：按 `conversation_id` 唯一约束，先 insert，冲突走 update `session_status`（用 `LambdaQueryWrapper` + 单事务，避免 super-agent 的 select-then-insert 竞态——依赖唯一索引 + 捕获 `DuplicateKeyException` 重试一次）
  - [x] 轮次完成：`updateById` 只更非 null 字段（builder 精准设置），不重写整个 JSON 列
  - [x] 列表分页：复用通用 `PageVo`，过滤条件 `keyword`/`chatMode`/`turnStatus` 用 `LambdaQueryWrapper`，**不拼裸 SQL 片段**（修正 super-agent 问题）
  - [x] JSON 列读写：抽 `ChatJsonCodec`（FastJSON），解析失败 **warn + 返回空集合**，不抛 `IllegalStateException`（修正 super-agent 问题 5）
- [x] **1.3 `ChatSessionService`**（业务编排层，`IChatSessionService` + impl）
  - [x] `createConversation(ChatMode, selectedDocumentId)` → 生成 conversationId（雪花或 UUID），落库返回
  - [x] `getConversationDetail(conversationId)` → 装配 `ConversationView`（最新一轮、轮次数、状态、记忆摘要引用）
  - [x] `listConversations(ChatSessionListDto)` → 分页（DTO 强类型：`keyword String` / `chatMode Integer` / `turnStatus Integer` / `pageNo Integer` / `pageSize Integer` + `@Min` 校验）
  - [x] `deleteConversation(conversationId)` → 软删 conversation + 关联 turn（`is_deleted`），单事务
  - [x] `renameConversation` —— **新增能力**（super-agent 没有，补齐：LLM 自动生成标题 + 手动改名接口）
- [x] **1.4 文档选项查询**：`listDocumentOptions()` → 复用 document 模块（按类型注入 `IDocumentManageService` 或新增轻量查询），返回 `KnowledgeDocumentOptionVo` 列表


---

## Phase 2 — SSE 流式地基 + 运行时任务表  `[x]`

**产出**：流式回答的骨架管线（不含具体执行策略），对标 super-agent `BusinessChatService` 的 SSE 生命周期部分（拆分后）。**先让"对话能跑通空回答"**。

- [x] **2.1 事件模型与序列化**
  - [x] `ChatStreamEvent`（record）：`type`(text/thinking/status/error/reference/recommend/done) / `content` / `conversationId` / `turnId` / `timestamp`
  - [x] `ChatStreamEventWriter`（`@Component`）：FastJSON 序列化 SSE event，**序列化失败降级为 status error 事件而非抛异常中断整个会话**（修正 super-agent 问题）
  - [x] `SinkEmitHelper`：`synchronized(sink)` 下 emitNext/emitComplete，`FAIL_CANCELLED/TERMINATED` 静默（这是合理的，文档化说明）
  - [x] `ChatContextKeys`：`RunnableConfig.context()` 的 string 常量（QUESTION / REFERENCES / TRACE_RECORDER / TASK_INFO / STREAM_SINK）

- [x] **2.2 运行时任务模型**
  - [x] `ChatTaskInfo`（context bag，不可变字段 + `@Builder`）：`conversationId` / `turnId` / `Sinks.Many<String> sink` / `RunnableConfig` / `ChatTraceRecorder traceRecorder` / `StringBuffer answerBuffer` / `AtomicBoolean finalized` / `Disposable` 列表
  - [x] `ChatRuntimeRegistry`：`ConcurrentHashMap<String, ChatTaskInfo>`，只提供 `register` / `get` / `remove(conversationId, expected)`（**不实现单 arg remove**，修正 super-agent 问题 11）+ `interrupt(conversationId)`
  - [x] `ChatLeaseService`（Redis 租约）：`tryAcquire(conversationId)` / `renew` / `release`，TTL 来自配置；获取失败抛 `ChatException(SESSION_RUNNING)`

- [x] **2.3 流式编排骨架** `ChatStreamOrchestrator`（`@Service @AllArgsConstructor @Slf4j`）
  - [x] `openStream(ChatStreamRequest) → Flux<String>` 主流程：
    1. 解析 `StreamLaunchPlan`（强类型：question/conversationId/chatMode/selectedDocumentId/leaseToken）
    2. `leaseService.tryAcquire` —— 失败直接返回错误事件 Flux（不抛到 Controller）
    3. `bootstrapConversation`：建 turn 行（RUNNING）+ 建 `ChatTaskInfo` + 注册 registry + 绑定 sink
    4. `activateGeneration`：租约续期定时器 + 订阅 executor 产出
    5. 订阅 `executor.execute(taskInfo)`：`emitModelChunk` → 累积 answerBuffer；完成走 `finalize`
  - [x] **`finalize(ChatTaskInfo, ChatTurnStatus, errorMessage)` 单方法**（修正 super-agent 问题 4）：CAS `finalized` 幂等 → 落 turn 行（reply_content / status / latency / debug_trace）→ trace 落 FINALIZE → release lease → registry.remove(conversationId, taskInfo) → sink.emitComplete。异常 **warn 但不吞**（关键路径，落 trace error）
  - [x] `stopStream(conversationId)`：registry.get → interrupt executor → finalize(STOPPED)
  - [x] 首字延迟统计：第一个 text 事件记 `firstTokenLatencyMs`，完成记 `totalLatencyMs`
  - [x] **此阶段 executor 只接一个 stub `EchoExecutor`**（直接回显问题），让管线可端到端跑通

- [x] **2.4 验证**：本地起服务，`POST /api/chat/stream` 能收到 SSE 事件流（even if echo），停止接口能中断。


---

## Phase 3 — 可观测 ChatModel + Prompt 模板  `[x]`

**产出**：带 token/成本/耗时追踪的 LLM 调用封装 + 模板服务。对标 super-agent `ObservedChatModelService` + `PromptTemplateService`。

- [x] **3.1 `ChatPromptTemplateService`**
  - [x] 加载 `business/chat/src/main/resources/prompt/*.st`，`ConcurrentHashMap` 缓存，`<placeholder>` 渲染（用 Spring AI `StTemplateRenderer`）
  - [x] `render(name, vars)`，加载失败抛 `ChatException(PROMPT_LOAD_FAILED)`（不抛 `IllegalStateException`）
  - [x] `ChatPromptNames` 常量类

- [x] **3.2 追踪模型**（`model/debug/`）
  - [x] `ChatDebugTrace`：rewriteQuestion / agentQuestion / retrievalResults / toolTraces / modelUsageTraces / limitStats / historySummary
  - [x] `ChatModelUsageTrace`：stageName / provider / model / promptTokens / completionTokens / totalTokens / estimatedCost / durationMs / **`ChatModelCallStatus` 枚举**
  - [x] `ChatToolTrace`：toolName / args / result / status(`ChatToolStatus`) / durationMs / error
  - [x] `ChatLimitStats`：modelCalls / toolCalls / 阈值

- [x] **3.3 `ObservedChatModelService`**（`@Service @AllArgsConstructor`）
  - [x] 注入 `@Qualifier("deepSeekChatModel") ChatModel`（主），`ObjectProvider<ChatModel>` 备选厂商
  - [x] `callText(stageName, prompt, options) → String`（阻塞）+ `streamText(stageName, prompt, options) → Flux<String>`
  - [x] 每次调用建 `ChatModelUsageTrace`，从 `ChatResponse.metadata.usage()` 取 token（修正 super-agent 字符串识别 provider——provider 由注入的 ChatModel 实现类名一次解析并缓存，不每次匹配）
  - [x] `estimateCost`：按配置 `ChatProperties.Pricing`（model → 单价）查表，未配置返回 null（**不硬编码 qwen-plus/deepseek 价格**）
  - [x] `streamText` 在 `doFinally`（覆盖 cancel/complete/error）落 trace，修正 super-agent cancel 丢 trace 问题
  - [x] 失败：trace 状态 FAILED + 抛 `ChatException(MODEL_CALL_FAILED, stageName, cause)`

- [x] **3.4 Prompt 模板文件**（先建 Phase 3/4/5/6/9 要用的）
  - [x] `chat-query-rewrite.st` / `conversation-summary-merge.st` / `conversation-summary-system.st` / `rag-answer-system.st` / `rag-answer-user.st` / `agent-question.st` / `recommendation-user.st` / `clarification.st`


---

## Phase 4 — 对话记忆（memory）  `[x]`

**产出**：短期 recent window + 长期摘要压缩记忆。对标 super-agent `PersistentConversationMemoryService`。

- [x] **4.1 记忆模型**（`model/memory/`）
  - [x] `ChatMemoryContext`：assembledHistory / longTermSummary / recentTranscript / answerRecentTranscript / summaryPayload / coverage / compressionCount
  - [x] `ChatSummaryPayload`：summary / conversationGoal / stableFacts / userPreferences / resolvedPoints / pendingQuestions / retrievalHints（`@JSONField` 映射下划线，对齐 prompt 输出结构）

- [x] **4.2 `IChatMemoryService` + `ChatMemoryServiceImpl`**
  - [x] `loadMemoryContext(conversationId, ChatTraceRecorder)`：同步加载 recent window + summary，必要时触发摘要刷新
  - [x] `getConversationSummary(conversationId)` / `rebuildConversationSummary(conversationId)` / `deleteConversationSummary(conversationId)`
  - [x] `refreshConversationSummaryAsync(conversationId)`：`chatMemoryExecutor` 异步，`ConcurrentHashMap.newKeySet()` 去重并发刷新
  - [x] **recent window**：取最近 `keepRecentTurns`(4) 轮 COMPLETED/STOPPED 且 question/answer 非空，渲染为 transcript，裁剪到 `recentTranscriptMaxChars`
  - [x] **摘要压缩**：稳定轮次超 `keepRecentTurns` 时，溢出部分按 `compressionBatchTurns`(6) 批量调 LLM 合并入 `ChatSummaryPayload`，存 `reuben_agent_chat_memory_summary`（一 conversation 一行，唯一约束）
  - [x] **JSON 解析**：用 `ChatJsonCodec` 容错提取首个平衡花括号（修正 super-agent 贪婪正则问题 14），失败 warn + 返回上一版 summary
  - [x] **fallback**：LLM 合并失败走规则拼接 + 关键词提取，**记 warn + 落 trace**（不静默）
  - [x] 魔法常量全部进 `ChatProperties.Memory`（修正问题 13）
  - [x] `clipText`/`safeText` 统一用 `ChatTexts`（修正问题 15）

- [x] **4.3 ReAct 工作记忆（checkpoint）—— 决策点**
  - [x] super-agent 用 Alibaba `MysqlSaver`（`GRAPH_CHECKPOINT`/`GRAPH_THREAD`）。**本期决策**：若 Phase 7 用 Alibaba `ReactAgent`，则沿用其 saver（自建 `ChatCheckpointManager` 包装）；若自实现 ReAct loop，则用自建轻量 `reuben_agent_chat_checkpoint` 表存 message 列表。
  - [x] `ChatCheckpointManager`：`get(runnableConfig)` / `list(threadId)` / `clearThread(threadId)`，字段语义对齐（`thread_name` 存 conversationId）
  - [x] 清理：删会话时级联清 checkpoint（`ChatSessionServiceImpl.deleteConversation` 调 `ChatCheckpointManager.clearThread` + `IChatMemoryService.deleteConversationSummary`）


---

## Phase 5 — 查询改写 + 执行模式决策（orchestrate）  `[x]`

**产出**：决定"这一轮怎么回答"的大脑。对标 super-agent `ChatQueryRewriteService` + `ChatPreparationOrchestrator`。

> **本地无 Docker 待测项**（已编译通过，端到端验证留待有中间件环境的机器）：
> - `ChatQueryRewriteService` 的 LLM 改写路径（需 DeepSeek API key 与网络）
> - `ChatPreparationOrchestrator.resolveAutoDocument` 的知识路由（需 RAG 向量索引 + MySQL/Redis）
> - 落库 trace stage（需 `reuben_agent_chat_trace_stage` 表与 MySQL）
> 建议晚上回去在有 Docker 的电脑上跑一次端到端 SSE 调用，验证 MEMORY/INTENT/REWRITE/ROUTE 四个 stage 落库。

- [x] **5.1 `ChatQueryRewriteService`**
  - [x] `rewrite(question, ChatMemoryContext) → ChatRewriteResult`（rewrittenQuery + subQuestions + usedRewrite 标志）
  - [x] LLM 改写 + 子问题拆分；规则 fallback（多问号/分号拆分）
  - [x] `needsRewrite` / `looksLikeMultiQuestion` 的 char 阈值进配置（修正问题 13），用预编译 `Pattern`
  - [x] 失败 warn + 走规则，落 trace（REWRITE stage）

- [x] **5.2 导航决策模型**（`model/orchestrate/`）
  - [x] `DocumentNavigationDecision` / `DocumentNavigationAction` / `NavigationScopeMode` / `EvidenceSatisfactionResult`
  - [x] `ConversationExecutionPlan`：executionMode / rewrittenQuery / subQuestions / selectedDocumentId / clarificationReply / anchors

- [x] **5.3 `ChatPreparationOrchestrator`**（拆得比 super-agent 小，<400 行）
  - [x] 五步：loadMemory → rewriteQuery → modeBranch → navigationDecision → emitPlan
  - [x] **modeBranch**：
    - OPEN_CHAT → REACT_AGENT
    - DOCUMENT → RETRIEVAL（定向该文档检索）
    - AUTO_DOCUMENT → 知识路由选文档，候选不明确且低置信 → CLARIFICATION，否则 RETRIEVAL
  - [x] **navigationDecision**（仅 DOCUMENT/AUTO 用）：判断 GRAPH_ONLY（结构定位类问题）/ GRAPH_THEN_EVIDENCE（先定位章节再检索证据）/ RETRIEVAL（直接证据检索）
  - [x] `shouldAskClarification` 的 `0.55`/`3` 阈值进配置
  - [x] **知识路由**：调用 document 模块（按类型注入知识路由 Bean 或复用 `IRagRetrievalService` 带 filter）做候选文档评分；**不复制 super-agent 的手搓 n-gram 评分**（问题：与 KnowledgeRouteService 重复），直接委托 document 侧
  - [x] 关键词 hint 集合（CAPABILITY/OPEN_CHAT/CHITCHAT）外置到配置或常量类，不散落方法体
  - [x] 每步落 trace stage（MEMORY/INTENT/REWRITE/ROUTE）


---

## Phase 6 — RAG 检索回答通道（rag-at-chat）  `[ ]`

**产出**：把问题检索文档证据 → 组装 prompt → 流式生成带引用的回答。对标 super-agent `RagChatExecutor` + `RagRetrievalEngine` + `RagPromptAssemblyService`。**这是与 reuben-agent 已有 rag 模块对接的核心**。

> **本地无 Docker 待测项**：6.1 的检索链路依赖 rag 模块（向量库 / 关键词库需 ES + PGVector），本地无 Docker 环境无法端到端跑通。Phase 6 代码已编译通过（`mvn -pl business/chat -am clean compile`），运行期联调待用户在带中间件的机器验证。

- [x] **6.1 检索适配层**
  - [x] `ChatRagRetrievalAdapter`（`@Service`）：按类型注入 `IRagRetrievalService`，把 `ChatRewriteResult.subQuestions` 逐个转 `RagRetrieveRequest`（query + topK + filterFields=documentId），聚合 `RagRetrieveResponse`
  - [x] **复用 reuben-agent rag 的全部能力**（改写/双通道/RRF/父块提升/rerank 已在 rag 模块），chat 侧不再实现检索引擎（修正 super-agent 在 chat 模块重造 RagRetrievalEngine 的问题）
  - [x] 检索结果映射为 `ChatRetrievalResult`（含 source=vector/keyword/hybrid、score、documentId、sectionPath、chunkText 预览）
  - [x] 证据门控：score 阈值 + 数量 budget，来自 `ChatProperties.Rag`
  - [x] 检索过程落 `reuben_agent_chat_retrieval_result` + `reuben_agent_chat_channel_execution`（via `ChatRetrievalObserveStore`）—— Phase 6 提供 noop 占位 + probe 接口，Phase 8 接入 MyBatis 落库

- [x] **6.2 Prompt 组装** `ChatRagPromptAssemblyService`
  - [x] 组装 system（rag-answer-system.st：角色 + 引用规范 + 拒答约束）+ user（rag-answer-user.st：context 证据块 + recent transcript + question）
  - [x] 证据块裁剪到 char budget，每块带 `[1] documentName / sectionPath` 标记供引用映射
  - [x] 历史 transcript 用 `ChatMemoryContext.answerRecentTranscript`（只取答案摘要，省 token）—— 从 `ConversationExecutionPlan.recentTranscript` 读，组装为承接上下文块注入 user prompt

- [x] **6.3 `RagAnswerExecutor implements ConversationExecutor`**
  - [x] `mode() = RETRIEVAL`（同时被 GRAPH_THEN_EVIDENCE 复用，先做结构定位缩小 filter 再走本 executor）
  - [x] `execute(ChatTaskInfo) → Flux<String>`：adapter 检索 → 组装 prompt → `observedChatModelService.streamText` → emit text chunk → 完成时把引用 `SearchReference` 列表 emit 为 reference 事件 + 落 turn.source_snapshot_list
  - [x] 无证据命中：emit 拒答文案（来自模板），落 trace evidence_budget=0
  - [x] 超时/失败：emit error 事件 + 抛 `ChatException(RETRIEVE_FAILED)` 让 orchestrator finalize

- [x] **6.4 引用映射** `SearchReferenceMapper`：把 RetrievalResult → `SearchReference`（统一引用模型，含 documentName/sectionPath/score/sourceType，**移除 super-agent 里硬编码的 `sourceType="WEB"`/`toolName="tavily_search"`**——区分 document/web 由字段决定而非构造器硬塞）


---

## Phase 7 — ReAct Agent + 联网工具（agent）  `[x]`

**产出**：开放式对话的 Agent loop（推理→工具调用→观察→…）+ 联网搜索工具。对标 super-agent `ReactAgentExecutor` + `TavilySearchTool` + 拦截器。

> ⚠️ **待测项**（本机无 Docker / 无 Tavily key，端到端运行验证留待夜间用自己电脑跑）：
> - Tavily 真实联网调用（需配 `chat.tavily.api-key`）
> - ReAct Agent checkpoint 落 `GRAPH_THREAD`/`GRAPH_CHECKPOINT` 表（需 MySQL 实例）
> - 多轮工具调用 + `ModelCallLimitHook`/`ToolCallLimitHook` 阈值触发 `END`
> - SSE 透传 thinking 事件（"🔍 正在联网搜索"）+ 工具 trace 落 `taskInfo.toolTraces`
> - 时间敏感 query 触发 `freshSearchHint` 注入

- [x] **7.1 工具定义**
  - [x] `TavilySearchTool`（`@Component`）：`search(TavilySearchRequest, ToolContext) → TavilySearchToolResult`，调 Tavily REST `/search`
  - [x] `RestClient` 由 `ChatProperties.Tavily` 构造（`@Bean`，可刷新配置走 `@RefreshScope` 或重建——本期静态足够）
  - [x] 时间敏感查询增强：`TimeSensitiveQueryHelper`（纯函数，关键词列表外置配置），决定是否注入当前日期/触发联网
  - [x] 工具执行：push thinking 事件（"🔍 正在联网搜索"——文案可配，不硬编码）、注册 `ChatToolTrace`、标记 usedTool
  - [x] **RAG 检索是否作为 Agent 工具**：super-agent 是把 RAG 做成独立 executor 不给 agent 调。**本期对齐 super-agent 行为**（OPEN_CHAT 走 ReAct+联网，DOCUMENT/AUTO 走 RAG executor），不把 RAG 注册成 tool，避免双路径混乱。留 TODO 注释未来可加 `rag_search` tool。

- [x] **7.2 工具注册与拦截器**
  - [x] `ChatAgentConfiguration`：注册 tavily `FunctionToolCallback`（name `tavily_search`）
  - [x] `TavilyToolInputFallbackInterceptor`：保证 query 非空，fallback 取 `ChatContextKeys.QUESTION`；**用 Optional/哨兵而非 null 控制信号**（修正问题：null 当控制流）
  - [x] `ToolRetryInterceptor`（2 次 + jitter backoff）/ `ToolErrorInterceptor`
  - [x] **DashScope 兼容拦截器**：DeepSeek 为主，**本期移除** super-agent 的 `DashScopeCompatibilityInterceptor`（555 行、base-url 字符串识别、INFO 全量日志）。若后续接入多厂商，按配置 provider 选拦截器，日志 debug。

- [x] **7.3 ReAct loop**
  - [x] **决策点**：用 Alibaba `spring-ai-alibaba-graph` 的 `ReactAgent.builder()`（与 super-agent 一致，省去手写 loop），还是自实现轻量 ReAct。**本期采用 Alibaba ReactAgent**（功能对齐、减少自实现风险），checkpoint 用其 `MysqlSaver`（Phase 4.3 已包装）。
  - [x] `ChatAgentConfiguration.reactAgent` Bean：name `business_chat_agent` / instruction=`ChatProperties.Agent.systemPrompt` / tavily tool / mysqlSaver / `parallelToolExecution(true)` maxParallelTools(4) / `ModelCallLimitHook` + `ToolCallLimitHook`（阈值来自配置）
  - [x] `ReactAgentExecutor implements ConversationExecutor`：`mode()=REACT_AGENT`，`reactAgent.stream(agentQuestion, runnableConfig)` → map `NodeOutput`/`StreamingOutput` → emit text；`publishOn(boundedElastic)`；`GraphRunnerException` → `Flux.error(ChatException)`
  - [x] agent question 由 `agent-question.st` 用 memory context + 原始 question 组装
  - [x] 工具调用过程中 thinking 事件透传到 SSE


---

## Phase 8 — 多模式执行分发 + 全链路追踪  `[x]`

**产出**：把 Phase 6/7 的 executor 接入分发，补齐其余 executor，并把每轮执行全链路落库可观测。对标 super-agent `ConversationExecutorRegistry` + 5 executor + Trace Recorder/Store + `StageBenchmarkService`。

> ### ⚠️ Phase 5/6/7 端到端联调遗留项（2026-06-26 验证后登记，Phase 8 一并处理）
>
> **已修（本次联调）**：
> - `ChatPreparationOrchestrator.modeBranch` NPE —— `Map.of(...)` 不允许 null value，`selectedDocumentId` 在 OPEN_CHAT/AUTO 无文档时为 null → 改 `HashMap`。
> - `ChatProperties.Rag.minScore` 0.45→0.0 —— rag 模块返回 RRF 融合分（~0.016）非 cosine，0.45 把所有证据挡掉。
> - `ChatProperties.Orchestration.clarifyTopScoreDiff` 3.0→0.001、`clarifyConfidenceThreshold` 0.55→0.45 —— RRF 分量级 vs super-agent n-gram 分量级错位，原值让 AUTO_DOCUMENT 永远走 CLARIFICATION。
> - `ChatStreamOrchestrator.finalize` 落 turn 时漏写 `sourceSnapshotList` / `toolTraceList` —— 补 `jsonCodec.toJson(references)` / `toJson(thinkingSteps)`。
> - `ReactAgentExecutor` 只透传 `AGENT_MODEL_STREAMING`，丢 `AGENT_MODEL_FINISHED`（非流式兜底时最终答案被吞）—— 补 FINISHED 过滤。
>
> **未修（Phase 8 处理）**：
> - [x] `ChatPreparationOrchestrator` / `ChatQueryRewriteService` 用手写构造器，应改 `@AllArgsConstructor`（CLAUDE.md §6）。
> - [x] `ChatPreparationOrchestrator.ModeBranch` 是 mutable bag + 工厂方法，应改 `@Builder` record（CLAUDE.md §6/§8）。
> - [x] `ChatPreparationOrchestrator.decideNavigation` 结构定位关键词（"第几/哪一节/目录/结构/章节"）仍 inline，与类 JavaDoc 声明"外置到 ChatIntentHints"不符 —— 移到 `ChatIntentHints`。
> - [x] `RagAnswerExecutor` 阻塞检索在 `Flux.defer` 里未 `subscribeOn(boundedElastic)`（当前靠 orchestrator 上游 publishOn 不阻塞 Netty，但不如 super-agent 显式）—— 加 `Mono.fromCallable(...).subscribeOn(boundedElastic).flatMapMany(...)`。
> - [x] `ReactAgentExecutor.ensureToolTracesList` 每次新建 list 注入 metadata，`TavilySearchTool.registerTrace` 写入后无人消费（`ChatTaskInfo` 无 `toolTraces` 字段）—— 删除 misleading 的 registerTrace 机制（保留 usedTools 写入；`ChatToolTrace` 类与 `ChatDebugTrace.toolTraces` 字段保留供 debug 模型复用）。
> - [x] `ChatRagRetrievalAdapter` 通道类型恒为 `"hybrid"`（rag 模块返回融合结果不暴露 per-channel），`reuben_agent_chat_channel_execution` 永远只有一行 —— **接受 hybrid-only，加 Javadoc 说明**；per-channel 保真需 rag 侧响应携带 channel 元数据（登记待测，Phase 9+）。
> - [x] `ChatQueryRewriteService.rewrite` 调 `callText` 未传 `traceRecorder`，改写 LLM 调用的 model-usage trace 丢失 —— 改调带 sink 重载，传 `recorder.traceSink()`。
> - [x] `routeKnowledge` 的 `docNames` map 声明后从不填充，`RouteCandidate.documentName` 恒 null（clarification 候选名回退成数字 documentId）。 —— **登记待测**：`RetrievalResult` 仅有 `documentId` 无 `documentName`，需 rag 侧 join document 表或响应携带 documentName（Phase 9+）。
> - [x] `ChatPreparationOrchestrator` 仍剩两处 `Map.of(...)`（INTENT/MEMORY stage snapshot）含 boolean/可能 null 风险，统一改 `HashMap`。
> - [x] `DocumentNavigationAction` 的 `LOCATE_ONLY` / `REJECT` 枚举值从未产出（decideNavigation 只产 DIRECT_RETRIEVAL / LOCATE_THEN_RETRIEVE）—— 删除枚举值 + 同步删 `toExecutionMode()` 中两个 case。
> - [x] `TimeSensitiveQueryHelper` / `ReactAgentExecutor` 的 `@Slf4j` 声明未使用（anti-pattern #9）。
> - [ ] 现网联调环境：pgvector + ES 索引在本机曾被卷重置（chunk 表有数据但 embedding 0 条），靠重新 confirm 策略触发 index-build 重建。**Phase 8 集成测试前确认索引完整性**，或在 `ChatDockerIntegrationTest` 里自带上传→索引→问答的完整 fixture。

- [x] **8.1 Executor 接口与注册**
  - [x] `ConversationExecutor` 接口：`ExecutionMode mode()` + `Flux<String> execute(ChatTaskInfo)`
  - [x] `ConversationExecutorRegistry`：`@Autowired List<ConversationExecutor>` → `EnumMap`，`get(mode)` 缺失抛 `ChatException(EXECUTOR_NOT_FOUND)`（不抛 `IllegalStateException`）

- [x] **8.2 补齐 executor**
  - [x] ~~`ClarificationExecutor`（mode=CLARIFICATION）~~：CLARIFICATION 故意无 executor —— `ChatStreamOrchestrator` 在分发前短路（无 LLM、无检索、无 stage），移进 executor 多一层空壳；已在 `ConversationExecutorRegistry` Javadoc 注明。
  - [x] `GraphOnlyExecutor`（mode=GRAPH_ONLY）：结构摘要，纯结构读取无 LLM（`IDocumentStructureNodeService.listDocumentNodes`，前 50 条 nodeNo+title+sectionPath）
  - [x] `GraphThenEvidenceExecutor`（mode=GRAPH_THEN_EVIDENCE）：先 `locateNode`（substring + 2-char token 滑窗）缩 filter → `ChatRagRetrievalAdapter.retrieveWithNodeFilter` → 复用 `RagAnswerExecutor.assembleAndStream`
  - [x] `RagAnswerExecutor`（Phase 6）/ `ReactAgentExecutor`（Phase 7）已就位

- [x] **8.3 接入 orchestrator**
  - [x] `ChatStreamOrchestrator.bootstrapConversation` 后：调 `ChatPreparationOrchestrator.prepare(plan) → ConversationExecutionPlan`
  - [x] `executorRegistry.get(plan.mode()).execute(taskInfo)` 订阅
  - [x] 移除 Phase 2 的 stub `EchoExecutor`

- [x] **8.4 全链路追踪**
  - [x] `ChatTraceRecorder`（每轮一个）：持有 per-turn `modelUsageTraces`（`synchronizedList`，`traceSink()` 回调写入） / 代理两个 store；`recordRetrievalResults` / `recordChannelExecutions` 失败 warn 不中断（落 trace error，不静默吞）
  - [x] `ChatTraceStageStore` + `MybatisChatTraceStageStoreImpl`（@Primary 顶替 Noop）：`startStage` / `finishStage`，落库失败降级返回 `-1L`；`listStages(turnId)` / `deleteByConversation` 留 Phase 9（登记待测）
  - [x] `ChatRetrievalObserveStore` + `MybatisChatRetrievalObserveStoreImpl`（@Primary 顶替 Noop）：落 retrieval_result / channel_execution
  - [x] 每个 stage 开始/结束经 recorder 落 `reuben_agent_chat_trace_stage`
  - [x] `ChatStageBenchmarkService` + Impl：P50/P90/P99/avg/max/min/sampleCount 滑窗，Redis LIST + Lua LTRIM 原子聚合（FLUSH_EVERY_N=20 触发 upsert）

---

### Phase 8 待测清单（本机无 Docker，集成测试在远端环境跑）

- [ ] Docker 集成测试：`MybatisChatTraceStageStoreImpl` insert/update 端到端、`MybatisChatRetrievalObserveStoreImpl` 批量 insert、`ChatStageBenchmarkServiceImpl` Redis Lua LTRIM 原子性 + 并发 push 下的 flush 触发。
- [ ] `GraphOnlyExecutor` 结构摘要格式 end-to-end（含超长文档截断到前 50 条节点）。
- [ ] `GraphThenEvidenceExecutor` 节点匹配准确度（substring + 2-char token）+ 缩小 `structureNodeId` filter 后召回率；复杂分词留 Phase 9。
- [ ] `ReactAgentExecutor` 的 model-usage trace 接入 —— 走 Alibaba ReactAgent，model 调用不经过 `ObservedChatModelService`，本期未接（需 hook agent 内部 ChatModel 调用），登记待测 Phase 9+。
- [ ] `ChatRagRetrievalAdapter` channel-type per-channel 保真 —— 当前恒 "hybrid"（rag 模块返回融合结果不暴露 per-channel），需 rag 侧响应携带 channel 元数据，Phase 9+。
- [ ] `routeKnowledge` `RouteCandidate.documentName` 填充 —— `RetrievalResult` 仅有 `documentId`，需 rag 侧 join document 表或响应携带 documentName，Phase 9+。
- [ ] `LOCATE_ONLY` / `REJECT` 枚举删除后无运行时引用（编译期已验证 0 命中）。
- [ ] pgvector/ES 索引完整性（集成测试前 confirm；或在 `ChatDockerIntegrationTest` 自带上传→索引→问答 fixture）。
- [ ] `ChatTraceStageStore` 接口扩展 `listStages(turnId)` / `deleteByConversation`（Phase 9 观测查询需要）。
- [ ] turn 表 `model_usage_json` 字段持久化 —— 当前 `recorder.snapshotModelUsageTraces()` 仅返回内存 list 供 finalize 落 trace snapshot；若要落 turn 表需加列，Phase 9。


---

## Phase 9 — 推荐追问 + 会话控制 + 观测查询 API  `[ ]`

**产出**：补齐 super-agent 的边角能力，让对话体验完整。

- [ ] **9.1 推荐追问** `ChatRecommendationService`
  - [ ] `recommend(question, answer, references) → List<String>`：`observedChatModelService.callText` + `recommendation-user.st`，`CompletableFuture.orTimeout(recommendationTimeoutMs)`
  - [ ] 失败：warn + 落 trace + 返回空（前端可显示占位），**不静默吞**（修正问题 16）
  - [ ] JSON 提取用 `ChatJsonCodec`（不裸 indexOf `[`/`]`）
  - [ ] 完成后 emit recommend 事件 + 落 turn.followup_suggestion_list
  - [ ] `ChatProperties.Recommendation.enabled` 开关

- [ ] **9.2 会话控制**
  - [ ] `stopConversation(conversationId)`：调 orchestrator.stopStream → `ChatStopVo`
  - [ ] `resetConversation(conversationId)`：删 turns + 清 checkpoint + 删 memory_summary，返回 `ChatResetVo`（counts），单事务
  - [ ] `rebuildSummary(conversationId)`：同步调 `memoryService.rebuildConversationSummary`

- [ ] **9.3 观测查询**（DTO 强类型，修正 super-agent 全 String + 矛盾注解）
  - [ ] `getTurnDetail(conversationId, turnId)` → `ChatTurnDetailView`（turn + stageTraces）
  - [ ] `getRetrievalResults(conversationId, turnId)` → `List<RetrievalResultView>`
  - [ ] `getChannelExecutions(conversationId, turnId)` → `List<ChannelExecutionView>`
  - [ ] `getStageBenchmarks(executionMode)` → `List<StageBenchmarkView>`


---

## Phase 10 — Controller + 配置装配 + 集成测试  `[ ]`

**产出**：对外 `/api/chat` 全量接口 + yaml + 集成测试，整体可跑。

- [ ] **10.1 DTO/VO 补齐**（全部强类型 + `@Valid`，`@Data @Builder @NoArgsConstructor @AllArgsConstructor`）
  - [ ] 入参：`ChatStreamDto`（question @NotBlank / conversationId 可空 / chatMode @NotNull Integer / selectedDocumentId Long）/ `ChatSessionListDto` / `ConversationIdentityDto`（conversationId @NotBlank）/ `ChatTurnDetailDto` / `ChatRenameDto`
  - [ ] 出参 VO：`ChatStreamEvent`（已在 Phase 2）/ `ConversationSessionListVo`（复用 `PageVo`）/ `ConversationView` / `ChatTurnView` / `ChatTurnDetailView` / `RetrievalResultView` / `ChannelExecutionView` / `StageBenchmarkView` / `ChatStopVo` / `ChatResetVo` / `KnowledgeDocumentOptionVo` / `SearchReference`

- [ ] **10.2 `ChatController`**（`@RestController @RequestMapping("/api/chat") @AllArgsConstructor @Tag`）
  - [ ] `POST /stream` → `Flux<String>`（SSE，返回 `ApiResponse` 之外的裸 Flux，参考 super-agent）
  - [ ] `GET /session/list`（改 super-agent 的 POST 为 GET，更 RESTful）→ `ApiResponse<ConversationSessionListVo>`
  - [ ] `GET /session/detail` → `ApiResponse<ConversationView>`
  - [ ] `POST /session/rename` → `ApiResponse<Void>`（新增）
  - [ ] `DELETE /session` → `ApiResponse<ChatResetVo>`（reset）+ 单独 `DELETE /session/{conversationId}`（delete）
  - [ ] `POST /session/stop` → `ApiResponse<ChatStopVo>`
  - [ ] `POST /session/summary/rebuild` → `ApiResponse<Void>`
  - [ ] `GET /exchange/detail` / `GET /exchange/retrieval/results` / `GET /exchange/channel/executions` / `GET /stage/benchmarks`
  - [ ] `GET /document/options` → `ApiResponse<List<KnowledgeDocumentOptionVo>>`
  - [ ] Controller 内**不 catch**，全部抛 `ChatException` 交 Handler

- [ ] **10.3 配置装配**
  - [ ] `launcher/application.yml` 补 `reuben.chat.*` 全段（agent / memory / rag / recommendation / tavily / executor / trace / pricing）
  - [ ] 确认 `deepSeekChatModel` Bean 对 chat 可见；若 chat 需要独立 model options（temperature 等），在 `ChatAgentConfiguration` 定义 `OpenAiChatOptions` Bean 注入 agent
  - [ ] Kafka topic（如异步追问/异步摘要落库需要）命名 `reuben-agent-chat-*`，参考 document 模式建 `ChatKafkaConfiguration` + producer/consumer（按需，本期可全同步）

- [ ] **10.4 集成测试**
  - [ ] `ChatDockerIntegrationTest`（`@ActiveProfiles("docker")` + 真实 MySQL/Redis/DeepSeek mock 或真实 key）
  - [ ] 用例：创建会话 → 流式问答（OPEN_CHAT 走 ReAct+联网 mock）→ DOCUMENT 模式 RAG 回答 → 停止 → 重置 → 列表 → 轮次详情 → 追踪查询
  - [ ] 建表脚本对齐：测试用 `ChatTestSchema` 手建表（参考 `DocumentTestSchema`）
  - [ ] 启动命令文档化：`docker compose up -d && mvn test -pl business/chat -am -Dtest=ChatDockerIntegrationTest -Dspring.profiles.active=docker`

- [ ] **10.5 全量编译 + 打包**：`mvn install -pl launcher -am -DskipTests` 通过；`mvn spring-boot:run -pl launcher` 能起，`/api/chat/stream` 可调

---

## 验收清单（功能对齐 super-agent，不阉割）

- [ ] SSE 流式回答（text/thinking/status/error/reference/recommend/done 事件）
- [ ] 会话 CRUD + 列表分页 + 详情 + 重命名（新增）
- [ ] 停止 / 重置 / 重建摘要
- [ ] 三种 ChatMode（DOCUMENT / OPEN_CHAT / AUTO_DOCUMENT）
- [ ] 五种 ExecutionMode（GRAPH_ONLY / GRAPH_THEN_EVIDENCE / RETRIEVAL / REACT_AGENT / CLARIFICATION）
- [ ] ReAct Agent + 联网搜索工具 + 工具拦截器（retry/error/fallback）
- [ ] RAG 检索回答（复用 reuben-agent rag 模块）+ 引用映射 + 拒答
- [ ] 短期 recent window + 长期摘要压缩记忆 + checkpoint
- [ ] 查询改写 + 子问题拆分
- [ ] 推荐追问（可关）
- [ ] 全链路追踪（stage / retrieval / channel execution）+ stage benchmark
- [ ] 轮次级观测查询 API
- [ ] 全程符合 reuben-agent 风格（异常/枚举/Builder/注入/注释），super-agent 18 项问题已修正

---

## 风险与决策点

| 决策 | 选项 | 倾向 |
|------|------|------|
| ReAct loop | Alibaba ReactAgent vs 自实现 | Alibaba（功能对齐、省风险），Phase 7 |
| Checkpoint 存储 | Alibaba MysqlSaver(GRAPH_*) vs 自建语义表 | 跟随 ReAct 选择——用 Alibaba 则沿用 GRAPH_*，Phase 4.3 |
| RAG 是否作为 Agent tool | 独立 executor vs tool | 本期独立 executor（对齐 super-agent），留 TODO |
| DashScope 兼容 | 保留 555 行拦截器 vs 移除 | 移除（DeepSeek 为主），Phase 7.2 |
| 跨实例停止会话 | 内存 registry vs Redis 分布式 | 本期内存（单实例），Phase 2.2，注释 TODO |
| 知识路由评分 | 复用 document 侧 vs 在 chat 重搓 | 复用 document 侧，Phase 5.3 |
