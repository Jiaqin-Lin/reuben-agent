# 知识路由 + Neo4j 图数据库 + 导航索引 实现计划

> 进度勾选约定：每个任务条目前 `[ ]` 完成后改 `[x]`。Phase 级勾选在 Phase 标题旁。
>
> 来源对标：`/Users/reuben/Desktop/super-agent/super-agent-business/super-agent-business-chat`（`org.javaup.ai.manage` 的知识路由/Neo4j/导航索引部分 + `org.javaup.ai.chatagent.rag` 的 `DocumentQuestionRouter`）。
>
> ⚠️ 禁止做阉割版本。super-agent 有的能力都要对齐：知识范围/主题三级路由、ES 知识路由索引、路由追踪审计、Neo4j 图投影+图查询+MySQL 回退、ES 导航章节索引、DocumentQuestionRouter 规则+LLM 双引擎、导航决策全动作。但是不追求1:1复刻，风格跟自己走，不适合的命名或者业务处理或者防御性编程我们不学

---

## 背景与约束

### 项目背景

reuben-agent 已完成四大核心模块：

- **document**：上传→解析→结构提取（Stage 1-4）→策略确认→切块→向量化→索引（Stage 5-8），全链路 Kafka 异步
- **rag**：查询改写→双通道检索（PGVector + ES）→RRF 融合→父块提升→可选 Rerank
- **chat**：SSE 流式对话、会话管理、多执行模式（GRAPH_ONLY / GRAPH_THEN_EVIDENCE / RETRIEVAL / REACT_AGENT / CLARIFICATION）、记忆压缩、全链路追踪、推荐追问
- **framework/common**：雪花 ID、Redisson 分布式锁/租约/队列、ApiResponse、BaseEnum、异常体系

**当前缺口**：super-agent 相比 reuben-agent，多出三大能力块——**知识路由（Knowledge）**、**Neo4j 图数据库**、**ES 导航索引（Navigation Index）**。这三块互有交叉但职责清晰：

```
用户问题
  │
  ├─→ 知识路由引擎 (Knowledge)    ← 决定"查哪个文档"
  │     Scope → Topic → Document 三级路由
  │     语义向量 + ES 词法 + 实体词命中
  │
  ├─→ 导航索引 (Navigation Index) ← 决定"查文档的哪一节"
  │     ES 章节多字段加权搜索
  │     DocumentQuestionRouter 规则+LLM 双引擎
  │
  └─→ 图数据库 (Neo4j)           ← 决定"章节之间怎么走"
        MySQL 结构节点 → Neo4j 投影
        兄弟/父子/条目关系遍历
        不可用时回退 MySQL 内存查询
```

### reuben-agent 已有基础（可直接复用/对接）

- **文档结构节点**：`DocumentStructureNode` 实体 + `IDocumentStructureNodeService`（`listDocumentNodes` 等），已落 MySQL `reuben_agent_document_structure_node` 表
- **文档画像**：`DocumentProfile` 实体（summary / coreTopics / exampleQuestions 等），解析管线 Stage 4 后落库
- **Embedding 能力**：Spring AI `EmbeddingModel`（bge-m3），PGVector 已在用
- **Elasticsearch**：已在 document 模块用于关键词索引（`reuben_document_chunk`），`ElasticsearchConfiguration` 已有
- **Chat 编排入口**：`ChatPreparationOrchestrator.prepare()` 五步管线（记忆→改写→模式分流→知识路由→导航决策），目前知识路由为空壳（直接返回 `selectedDocumentId`），导航决策始终返回 `WHOLE_DOCUMENT`
- **Chat 执行器**：`GraphOnlyExecutor` / `GraphThenEvidenceExecutor` 已就位，但目前查 MySQL `DocumentStructureNode` 表做图遍历，无 Neo4j
- **基础设施**：`ApiResponse` / `GlobalExceptionHandler` / `BaseEnum`+`EnumUtils` / `BaseTableData` / `UidGenerator` / MyBatis-Plus / FastJSON / Kafka / Redis

### 代码风格约束（来自 CLAUDE.md + memory，不可妥协）

1. 业务层禁止 `throw new RuntimeException("字符串")`，必须抛 `BusinessException` 子类。
2. 所有 code/msg 枚举 `implements BaseEnum`，`getFromCode` 委托 `EnumUtils`。
3. Controller 统一返回 `ApiResponse<T>`，异常交 `GlobalExceptionHandler`，Controller 内禁止 catch+error。
4. 实体继承 `BaseTableData`，`@TableName("reuben_agent_*")`，`@TableId(type=IdType.INPUT)`，雪花 ID，`@Builder`。
5. 注入用 `@AllArgsConstructor` 构造器注入，禁止 `@Autowired` 字段注入，Service 禁止 `@RequiredArgsConstructor`。
6. 连续 3+ `setXxx()` 用 `@Builder`。
7. 注释简洁：`// 阶段 N：做什么`，不要大段分隔线 / 教材式注释。
8. 热路径 `String.matches()` 预编译为 `static final Pattern`。
9. `@Slf4j` 声明就要在关键路径（状态变更/异常/外部调用）用日志。
10. 配置项进 `@ConfigurationProperties`，不硬编码魔法常量。

### 模块边界与对接约定

- **Knowledge 模块**：代码放 `business/document`（知识与文档强耦合，scope/topic/relation 表与 document 表同库）。不新建 Maven module。
- **Neo4j 模块**：代码放 `business/document`（图数据库是文档结构的另一种存储后端，不是独立领域）。不新建 Maven module。
- **Navigation Index 模块**：代码放 `business/document`（ES 导航索引在文档解析管线中构建，在 chat 管线中被消费）。索引构建在 document，查询接口在 document，chat 侧通过 Bean 注入调用。
- chat 模块的 `ChatPreparationOrchestrator` / `DocumentNavigationDecision` / executors **已在 chat 模块**，本计划只增强其能力（接入知识路由 + 导航索引 + Neo4j 图查询），不迁移代码。
- document 模块对外暴露的 Bean（`KnowledgeRouteService` / `DocumentNavigationIndexService` / `DocumentStructureGraphService`），chat 模块按类型注入，靠 launcher 聚合 classpath。

### super-agent 问题 → reuben-agent 优化方向（移植时一并修正）

| # | super-agent 问题 | reuben-agent 优化方向 |
|---|-----------------|---------------------|
| 1 | 知识路由权重（50/1.6/6/8/15/20）硬编码在 `KnowledgeRouteServiceImpl` | 全部进 `DocumentProperties.KnowledgeRoute` 嵌套配置，带默认值 |
| 2 | `KnowledgeRouteServiceImpl` 888 行 god-class，混合路由+评分+追踪+ES 刷新 | 拆为 `KnowledgeRouteService`（路由）+ `KnowledgeRouteIndexService`（ES）+ `KnowledgeRouteTraceService`（追踪） |
| 3 | `ElasticsearchKnowledgeRouteIndexService` 用 `AtomicLong` + `compareAndSet` 做 5 秒防抖，并发下可能丢刷新 | 用 `ScheduledExecutorService` 定时刷新 + `ReentrantLock` 保证不丢 |
| 4 | ES 索引初始化用 try/catch 回退 analyzer（IK→standard），静默吞异常 | 失败打 warn 日志 + 落 `DocumentProperties.Elasticsearch.analyzerFallback` 配置项显式控制 |
| 5 | `MysqlDocumentStructureGraphService` 每次查询 `listDocumentNodes` 全量加载到内存再 Java Stream 过滤，O(n) per query | 加 `ConcurrentHashMap` 缓存（per documentId），解析完成时主动失效；大文档（>2000 节点）降级为 SQL 直查 |
| 6 | `CompositeDocumentStructureGraphService.delegate()` 每次查询调 `isGraphAvailable`（一次 Cypher round-trip） | 加 `ConcurrentHashMap<Long, Boolean>` 缓存，projection 完成后主动设 true，delete 后设 false |
| 7 | `DocumentQuestionRouter` 20+ 关键词列表（ADJACENCY_HINTS / OUTLINE_HINTS ...）散落类体 | 全部外置到 `DocumentProperties.Navigation` 或 `ChatIntentHints` 常量类，可配置覆盖 |
| 8 | `DocumentQuestionRouter.classifyQuestionIntentWithModel()` LLM 输出用 `indexOf("{")` + `substring` 手撕 JSON | 用 FastJSON `JSON.parseObject` + 容错提取首个平衡花括号（对齐 chat 模块 `ChatJsonCodec`） |
| 9 | `DocumentQuestionRouter` LLM fallback 是同步阻塞调用，嵌在 `Flux.defer` 上游未显式 `subscribeOn` | 加 `Mono.fromCallable(...).subscribeOn(boundedElastic)` |
| 10 | `GraphOnlyExecutor` 纯结构读取无 LLM，大文档（>50 节点）摘要难以阅读 | 节点数 >50 时加 LLM 压缩摘要（`observedChatModelService.callText`），阈值进配置 |
| 11 | Neo4j 每个查询方法内 `driver.session()` 开新 session，无 try-with-resources 确保关闭 | 全部改为 try-with-resources `Session`，或抽 `executeRead(Consumer<TransactionContext>)` 模板方法 |
| 12 | `Neo4jDocumentStructureGraphService` 所有查询异常静默返回 null/空列表 | 打 warn 日志 + 抛 `DocumentException(NEO4J_QUERY_FAILED)` 让 Composite 回退 MySQL |
| 13 | `KnowledgeRouteServiceImpl.tokenize()` 中文分词用 `[\s、，,；;：:（）()\-的和及与或]+` 硬编码 | 分隔符列表进 `DocumentProperties.KnowledgeRoute.tokenDelimiters` |
| 14 | `KnowledgeRouteServiceImpl.embedSingle()` 逐条调 embedding（N+1），scope/topic/document 三层各自循环 | 改为批量化：收集所有 routeText → 一次 `embedModel.embed(batch)` → 按索引分配 |
| 15 | `routeKnowledge` 的 `docNames` map 声明后从不填充，候选 documentName 恒 null | 注入 `ChatDocumentOptionService`（chat 模块已有）或直接用 `IDocumentManageService` 补名 |
| 16 | `KnowledgeManageController` 所有接口用 POST（含纯查询） | 查询类改 GET（scope/list、topic/list、document/profile/detail、topic/document/list），写操作保持 POST |

---

## Phase 总览

| Phase | 主题 | 产出 | 依赖 |
|-------|------|------|------|
| 0 | Knowledge 地基：实体/枚举/Mapper/SQL/异常/配置 | 可编译的知识域骨架 + 建表脚本 | — |
| 1 | Knowledge ES 路由索引 | `KnowledgeRouteIndexService` + ES 索引初始化 + 记录模型 | Phase 0 |
| 2 | Knowledge 路由引擎 | `KnowledgeRouteService`（三级路由 + 追踪） | Phase 0, 1 |
| 3 | Knowledge 管理 API | `KnowledgeManageController` + DTOs/VOs | Phase 0, 2 |
| 4 | Neo4j 地基：依赖/配置/Driver/Health/图模型 | Neo4j 条件 Bean + 图模型 POJO | — |
| 5 | Neo4j 图投影 + 查询服务 | Projection + Query Service + Composite + MySQL 回退 | Phase 4 |
| 6 | Neo4j 图查询引擎 + Executor 增强 | `StructureGraphQueryEngine` + `GraphAnswerRenderer` + 增强现有 Executor | Phase 5 |
| 7 | Navigation Index 地基：ES 索引/记录/初始化/搜索 | `DocumentNavigationIndexService` + ES 实现 | — |
| 8 | Document Question Router | 规则引擎 + LLM fallback + 导航决策全动作 | Phase 7 |
| 9 | 全量集成与接线 | 三模块接入 document 解析管线 + chat 编排管线 | Phase 2, 3, 6, 8 |
| 10 | 集成测试 + 验收 | Docker 集成测试 + 端到端验证 | Phase 9 |

---

## Phase 0 — Knowledge 地基：实体 / 枚举 / Mapper / SQL / 异常 / 配置  `[x]`

**产出**：知识域完整数据模型 + 可编译骨架。

### 0.1 SQL 建表

reuben-agent 已有 DDL（`sql/reuben_agent_mysql.sql`），需对照 super-agent 补全缺失列：

- [x] **0.1.1 `reuben_agent_knowledge_scope_node` 补列**：现有 DDL 缺 `aliases`、`examples`、`sort_order`、`status`（`BaseTableData.isDeleted` 替代），对照补：
  - 新增 `aliases varchar(512)` — 逗号分隔别名，用于路由召回
  - 新增 `examples text` — JSON 数组示例问题
  - 新增 `sort_order int default 0`
  - 确认 `scope_code` 有 `UNIQUE KEY`

- [x] **0.1.2 `reuben_agent_knowledge_topic_node` 补列**：现有 DDL 缺 `aliases`、`examples`、`answer_shape`、`execution_preference`、`sort_order`，对照补：
  - 新增 `aliases varchar(512)`
  - 新增 `examples text`
  - 新增 `answer_shape varchar(64)` — explain/list/steps/compare/structure
  - 新增 `execution_preference varchar(64)` — retrieval/graph_only/graph_then_evidence
  - 新增 `sort_order int default 0`
  - 确认 `topic_code` 有 `UNIQUE KEY`

- [x] **0.1.3 `reuben_agent_topic_document_relation` 补列**：现有 DDL 已有基础字段，对照补：
  - 确认 `relation_score decimal(8,4)`
  - 新增 `relation_source varchar(32) default 'auto'` — auto/manual/mixed
  - 新增 `reason varchar(1024)`
  - 确认 `UNIQUE KEY uk_topic_document (topic_code, document_id)`

- [x] **0.1.4 `reuben_agent_knowledge_route_trace` 补列**：现有 DDL 需对照补：
  - 确认 `conversation_id varchar(64)`、`exchange_id bigint`(→ `turn_id bigint`)
  - 确认 `question text`、`rewrite_question text`
  - 确认 `mode varchar(32)` — shadow/auto
  - 确认 `top_scopes_json text`、`top_topics_json text`、`top_documents_json text`
  - 确认 `selected_document_id bigint`、`hit_selected_document tinyint(1)`、`confidence decimal(8,4)`、`route_status int`、`error_msg varchar(1024)`
  - 确认索引 `idx_conversation_turn (conversation_id, turn_id)` / `idx_route_status` / `idx_create_time`

### 0.2 枚举

- [x] **0.2.1 `KnowledgeRouteStatus`**：SUCCESS(1) / LOW_CONFIDENCE(2) / FAILED(3)，`implements BaseEnum`
- [x] **0.2.2 `KnowledgeRouteMode`**：SHADOW(1) / AUTO(2)，`implements BaseEnum`
- [x] **0.2.3 `KnowledgeRelationSource`**：AUTO(1) / MANUAL(2) / MIXED(3)，`implements BaseEnum`
- [x] **0.2.4 `KnowledgeAnswerShape`**：EXPLAIN(1) / LIST(2) / STEPS(3) / COMPARE(4) / STRUCTURE(5)，`implements BaseEnum`
- [x] **0.2.5 `KnowledgeExecutionPreference`**：RETRIEVAL(1) / GRAPH_ONLY(2) / GRAPH_THEN_EVIDENCE(3)，`implements BaseEnum`
- [x] **0.2.6 补 `DocumentManageCode`**：新增知识域错误码段 `50001–50099`（scope 不存在/ topic 不存在/ 路由失败/ ES 索引失败/ 画像生成失败 等）

### 0.3 实体

全部 `@Data @Builder @NoArgsConstructor @AllArgsConstructor @EqualsAndHashCode(callSuper = true) extends BaseTableData`，`@TableName("reuben_agent_knowledge_*")`，`@TableId(type = IdType.INPUT)`。

- [x] **0.3.1 `KnowledgeScopeNode`**：`id` / `scopeCode` / `scopeName` / `parentScopeCode` / `description` / `aliases` / `examples` / `sortOrder`。JSON 列 `String` 字段。
- [x] **0.3.2 `KnowledgeTopicNode`**：`id` / `topicCode` / `topicName` / `scopeCode` / `description` / `aliases` / `examples` / `answerShape` / `executionPreference` / `sortOrder`
- [x] **0.3.3 `TopicDocumentRelation`**：`id` / `topicCode` / `documentId` / `relationScore` / `relationSource` / `reason`
- [x] **0.3.4 `KnowledgeRouteTrace`**：`id` / `conversationId` / `turnId` / `question` / `rewriteQuestion` / `mode` / `topScopesJson` / `topTopicsJson` / `topDocumentsJson` / `selectedDocumentId` / `hitSelectedDocument` / `confidence` / `routeStatus` / `errorMsg`

### 0.4 Mapper

全部 `interface IXxxMapper extends BaseMapper<Xxx>`，`@Mapper`，无自定义方法除非必要。

- [x] **0.4.1** `IKnowledgeScopeNodeMapper`
- [x] **0.4.2** `IKnowledgeTopicNodeMapper`
- [x] **0.4.3** `ITopicDocumentRelationMapper`
- [x] **0.4.4** `IKnowledgeRouteTraceMapper`

### 0.5 异常与配置

- [x] **0.5.1 `DocumentProperties` 追加 `KnowledgeRoute` 嵌套配置**：
  ```java
  static class KnowledgeRoute {
      boolean enabled = true;
      double semanticFloor = 0.20;        // 语义分阈值，低于此忽略
      double semanticWeight = 50.0;        // (score - floor) * weight
      double lexicalCap = 10.0;            // ES 词法分上限
      double lexicalWeight = 1.6;          // lexicalScore * weight
      double entityHitScore = 6.0;         // 每个实体词命中加分
      double scopeBoostTopic = 8.0;        // topic 级 scope 匹配加分
      double scopeBoostDocument = 15.0;    // document 级 scope 匹配加分
      double relationScoreWeight = 20.0;   // topic-doc relationScore 乘数
      double confidenceNormalizerBase = 10.0;  // 置信度归一化底数
      double confidenceNormalizerOffset = 5.0; // 置信度归一化偏移
      double lowConfidenceThreshold = 0.55;    // 低置信度阈值
      int batchEmbeddingSize = 10;         // 语义分批大小
      int maxScopeCandidates = 5;
      int maxTopicCandidates = 8;
      int maxDocumentCandidates = 5;
      String tokenDelimiters = "[\\s、，,；;：:（）()\\-的和及与或]+";
      int tokenMinLength = 2;
      int tokenMaxCount = 40;
  }
  ```
- [x] **0.5.2** 确认 `DocumentManageCode` 已追加知识域 code（`50001–50099`）

### 0.6 编译验证

- [x] **0.6** `mvn compile -pl business/document -am` 通过

---

## Phase 1 — Knowledge ES 路由索引  `[ ]`

**产出**：知识路由的 ES 索引层，为路由引擎提供词法匹配通道。对标 super-agent `ElasticsearchKnowledgeRouteIndexService` + `KnowledgeRouteIndexRecord` + `KnowledgeRouteElasticsearchIndexInitializer`。

### 1.1 索引记录模型

- [ ] **1.1.1 `KnowledgeRouteIndexRecord`**（`model/es/`）：
  - `routeId` / `entityType`(scope/topic/document) / `entityCode` / `documentId` / `scopeCode` / `scopeName` / `topicCode` / `topicName` / `documentName` / `businessCategory` / `displayName` / `descriptionText` / `aliasesText` / `examplesText` / `summaryText` / `routeText` / `entityTerms(List<String>)` / `tags(List<String>)`
  - `@Builder`，用于 ES 索引写入

### 1.2 ES 索引初始化

- [ ] **1.2.1 `KnowledgeRouteElasticsearchIndexInitializer`**（`config/`）：
  - `@PostConstruct` 检查 + 创建索引 `reuben_agent_knowledge_route`
  - 字段映射：`routeId`/`entityType`/`entityCode`/`scopeCode`/`topicCode`/`businessCategory` → `keyword`；`documentId` → `long`；`displayName`/`descriptionText`/`aliasesText`/`examplesText`/`summaryText`/`routeText`/`scopeName`/`topicName`/`documentName` → `text`（analyzer 来自配置）；`entityTerms`/`tags` → `keyword`
  - Analyzer 默认 `ik_max_word`（索引）/ `ik_smart`（搜索），失败回退 `standard` + **打 warn 日志**（修正 super-agent 问题 4）
  - 索引名来自 `DocumentProperties.Elasticsearch.routeIndexName`（默认 `reuben_agent_knowledge_route`）

### 1.3 索引服务接口与实现

- [ ] **1.3.1 `KnowledgeRouteIndexService` 接口**（`service/`）：
  - `void refreshAll()` — 全量重建索引
  - `List<RouteLexicalHit> search(String routingText, String entityType, int size)` — 按实体类型搜索
  - `void deleteDocumentRoute(Long documentId)` — 按文档删除路由记录

- [ ] **1.3.2 `RouteLexicalHit` 记录**：`routeId` / `entityCode` / `entityType` / `documentId` / `scopeCode` / `topicCode` / `documentName` / `score(double)`

- [ ] **1.3.3 `EsKnowledgeRouteIndexService`**（`service/impl/`）：
  - `@ConditionalOnProperty(prefix="reuben.document.elasticsearch", name="enabled", havingValue="true", matchIfMissing=true)`
  - **`search()`**：bool query → filter(`term(entityType)`) + should(`matchPhrase(displayName, boost=12)` + `multiMatch(displayName^10/aliasesText^8/examplesText^6/summaryText^5/routeText^4/descriptionText^3, BestFields)` + per-entity-term `term(entityTerms, boost=9)`) → `minimumShouldMatch("1")` → size=max(cap=10)
  - **`refreshAll()`**：`deleteByQuery(matchAll)` → scope/topic/document 三类 batch `bulkIndex`
    - scope 记录：从 `KnowledgeScopeNode` 表全量加载，routeId=`"scope:{code}"`，entityType=`"scope"`
    - topic 记录：从 `KnowledgeTopicNode` 表全量加载，routeId=`"topic:{code}"`，entityType=`"topic"`
    - document 记录：从 `Document` + `DocumentProfile` 联查，routeId=`"document:{id}"`，entityType=`"document"`
  - **刷新策略**：用 `ScheduledExecutorService` 定时 30s + `ReentrantLock` 保证单线程刷新（修正 super-agent 问题 3：`AtomicLong`+`compareAndSet` 5s 防抖改用定时器+锁）
  - `entityTerms` 提取：code + name + aliases 的分词结果（逗号分隔 + n-gram），去重
  - `routeText` 组装：各实体类型的全文拼接（name + description + aliases + examples + summary + coreTopics）

---

## Phase 2 — Knowledge 路由引擎  `[ ]`

**产出**：三级路由引擎 + 路由追踪。对标 super-agent `KnowledgeRouteService` / `KnowledgeRouteServiceImpl`（888 行拆为 3 个类）。

### 2.1 路由模型

- [ ] **2.1.1 `KnowledgeRouteDecision`**（`model/route/`）：`scopes(List<ScopeRouteCandidate>)` / `topics(List<TopicRouteCandidate>)` / `documents(List<DocumentRouteCandidate>)` / `confidence(BigDecimal)` / `routeStatus(KnowledgeRouteStatus)` / `reason(String)`。`topDocument()` 返回最高分文档。
- [ ] **2.1.2 `ScopeRouteCandidate`**：`scopeCode` / `scopeName` / `score(BigDecimal)` / `reason(String)`
- [ ] **2.1.3 `TopicRouteCandidate`**：`topicCode` / `topicName` / `scopeCode` / `score(BigDecimal)` / `reason(String)`
- [ ] **2.1.4 `DocumentRouteCandidate`**：`documentId` / `documentName` / `lastIndexTaskId` / `knowledgeScopeCode` / `knowledgeScopeName` / `businessCategory` / `documentTags` / `score(BigDecimal)` / `reason(String)`

### 2.2 分词与查询上下文

- [ ] **2.2.1 `RouteQueryContext` 记录**（`model/route/`）：
  - `originalQuestion` / `rewriteQuestion` / `routingText`（拼接）/ `queryTerms(List<String>)` / `queryEmbedding(float[])`
- [ ] **2.2.2 `KnowledgeRouteTokenizer`**（`support/`，纯函数工具类）：
  - `tokenize(text, delimiters, minLen, maxCount) → List<String>` — 正则分隔 + 中文 n-gram 扩展（4+ 字词拆 2-6 gram）+ 去重截断
  - 分隔符从配置读（修正 super-agent 问题 13）

### 2.3 路由服务

- [ ] **2.3.1 `KnowledgeRouteService` 接口**（`service/`）：
  - `KnowledgeRouteDecision route(String question, String rewriteQuestion)` — 全量路由
  - `void recordShadowRoute(conversationId, turnId, selectedDocumentId, question, rewriteQuestion)` — 影子评估
  - `void recordAutoRoute(conversationId, turnId, question, rewriteQuestion, KnowledgeRouteDecision)` — 自动路由记录

- [ ] **2.3.2 `KnowledgeRouteServiceImpl`**（`service/impl/`，目标 <500 行）：
  - 依赖：`IKnowledgeScopeNodeMapper` / `IKnowledgeTopicNodeMapper` / `ITopicDocumentRelationMapper` / `IDocumentMapper` / `IDocumentProfileMapper` / `ObjectProvider<EmbeddingModel>` / `ObjectProvider<KnowledgeRouteIndexService>` / `UidGenerator` / `KnowledgeRouteTraceService`
  - **`route()` 主流程**：
    1. `buildQueryContext(question, rewriteQuestion)` → `RouteQueryContext`
    2. `rankScopes(ctx)` → `List<ScopeRouteCandidate>`（max 5）
    3. `rankTopics(ctx, scopeCandidates)` → `List<TopicRouteCandidate>`（max 8）
    4. `rankDocuments(ctx, scopeCandidates, topicCandidates)` → `List<DocumentRouteCandidate>`（max 5）
    5. `resolveConfidence(documents)` → `BigDecimal`
    6. `determineStatus(confidence, documents)` → `KnowledgeRouteStatus`
    7. `saveTrace(...)` → 异步写 trace
  - **评分公式**（所有权重来自 `DocumentProperties.KnowledgeRoute`）：
    ```
    semanticMainScore = max(0, (cosineScore - semanticFloor) * semanticWeight)
    lexicalAssist = min(lexicalCap, esLexicalScore * lexicalWeight)
    keywordEntityAssist = countMatchedEntityTerms * entityHitScore
    rawScore = semanticMainScore + lexicalAssist + keywordEntityAssist
    ```
    - topic 级额外：topScopeMatch ? `rawScore + scopeBoostTopic` : rawScore
    - document 级额外：topScopeMatch ? `rawScore + scopeBoostDocument` : rawScore；topTopicInRelation ? `rawScore + relationScore * relationScoreWeight` : rawScore
  - **置信度**：`confidence = topScore / max(confidenceNormalizerBase, topScore + secondScore + confidenceNormalizerOffset)`
  - **Fallback 链**（优雅降级，不走短路抛异常）：
    1. EmbeddingModel 不可用 → semanticMainScore 全部为 0，仅靠 lexical + keyword
    2. KnowledgeRouteIndexService 不可用 → lexicalAssist 全部为 0，仅靠 semantic + keyword
    3. 两者都不可用 → 纯 keyword entity hits
    4. scope 表无数据 → `deriveScopesFromDocuments()` 从 Document 表聚合 scopeCode/scopeName
    5. topic 表无数据 → `deriveTopicsFromProfiles()` 从 DocumentProfile.coreTopics JSON 数组提取
  - **批量 embedding**：所有 scope/topic/document 的 routeText 收集后一次 `embedModel.embed(batch)` 调用，按索引分配回各候选（修正 super-agent 问题 14：N+1 → 批量）

### 2.4 路由追踪

- [ ] **2.4.1 `KnowledgeRouteTraceService`**（`service/impl/`）：
  - `saveTrace(RouteTraceContext)` → 异步写 `reuben_agent_knowledge_route_trace`（`@Async` 或用 `chatPostProcessExecutor` 同款线程池）
  - `RouteTraceContext`：conversationId / turnId / question / rewriteQuestion / mode / scopeCandidates / topicCandidates / documentCandidates / selectedDocumentId / confidence / routeStatus / errorMsg
  - `hitSelectedDocument`：检查 selectedDocumentId 是否在 top 3 documents 中
  - 失败不中断路由主流程（warn 日志 + 落 routeStatus=FAILED）

---

## Phase 3 — Knowledge 管理 API  `[ ]`

**产出**：知识范围/主题/关系的 CRUD 接口 + 文档画像管理。对标 super-agent `KnowledgeManageController`。

### 3.1 DTOs / VOs

所有 `@Data @Builder @NoArgsConstructor @AllArgsConstructor`，入参 DTO 加 `@Valid`。

- [ ] **3.1.1 入参 DTO**（`dto/`，新建 knowledge 子包或直接放 dto/）：
  - `KnowledgeScopeSaveDto`：id(Long) / scopeCode(@NotBlank) / scopeName(@NotBlank) / parentScopeCode / description / aliases / examples / sortOrder
  - `KnowledgeScopeDeleteDto`：scopeCode(@NotBlank)
  - `KnowledgeTopicSaveDto`：id / topicCode(@NotBlank) / topicName(@NotBlank) / scopeCode(@NotBlank) / description / aliases / examples / answerShape / executionPreference / sortOrder
  - `KnowledgeTopicDeleteDto`：topicCode(@NotBlank)
  - `KnowledgeTopicQueryDto`：scopeCode
  - `TopicDocumentRelationSaveDto`：topicCode(@NotBlank) / documentId(@NotNull) / relationScore / relationSource / reason
  - `TopicDocumentRelationRemoveDto`：topicCode(@NotBlank) / documentId(@NotNull)
  - `KnowledgeRouteTraceQueryDto`：conversationId / mode / routeStatus / pageNo(@Min(1)) / pageSize(@Min(1))
- [ ] **3.1.2 出参 VO**（`vo/`）：
  - `KnowledgeScopeItemVo` / `KnowledgeTopicItemVo` / `TopicDocumentRelationItemVo`（含 documentName / scopeName 冗余字段）
  - `KnowledgeRouteTraceItemVo` / `KnowledgeRouteTracePageVo`（复用 `PageVo`）
  - `DocumentProfileVo`（已有 `DocumentProfile` 实体，VO 只投影对外字段：documentId / summary / documentType / coreTopics / exampleQuestions / graphFriendly / profileSource / profileStatus / errorMsg）

### 3.2 Service

- [ ] **3.2.1 `IKnowledgeManageService`**（`service/`）：
  - Scope CRUD：`saveScope` / `deleteScope` / `listScopes`
  - Topic CRUD：`saveTopic` / `deleteTopic` / `listTopics(scopeCode)`
  - Relation CRUD：`saveRelation` / `removeRelation` / `listRelations(topicCode)`
  - Profile：`getProfile(documentId)` / `regenerateProfile(documentId)` / `batchRegenerateProfiles(documentIds)`
  - RouteTrace：`pageQuery(KnowledgeRouteTraceQueryDto) → PageVo<KnowledgeRouteTraceItemVo>`
- [ ] **3.2.2 `KnowledgeManageServiceImpl`**（`service/impl/`）：
  - Scope/Topic 保存 upsert（按 unique code 冲突 update）
  - 软删除（`isDeleted=1`），不物理删
  - Relation 保存 upsert（按 `(topicCode, documentId)` 唯一约束）
  - Profile 重新生成：调 LLM 生成画像 → 更新 `DocumentProfile` 行 → 触发 ES 路由索引刷新（异步）
  - Scope/Topic/Relation 变更后触发 `KnowledgeRouteIndexService.refreshAll()`（异步）

### 3.3 Controller

- [ ] **3.3.1 `KnowledgeManageController`**（`controller/`，`@RestController @RequestMapping("/api/document/knowledge") @AllArgsConstructor @Tag(name="知识管理")`）：
  - `GET /scope/list` → `ApiResponse<List<KnowledgeScopeItemVo>>`
  - `POST /scope/save` → `ApiResponse<KnowledgeScopeItemVo>`
  - `POST /scope/delete` → `ApiResponse<Void>`
  - `GET /topic/list?scopeCode=xxx` → `ApiResponse<List<KnowledgeTopicItemVo>>`
  - `POST /topic/save` → `ApiResponse<KnowledgeTopicItemVo>`
  - `POST /topic/delete` → `ApiResponse<Void>`
  - `GET /topic/document/list?topicCode=xxx` → `ApiResponse<List<TopicDocumentRelationItemVo>>`
  - `POST /topic/document/save` → `ApiResponse<TopicDocumentRelationItemVo>`
  - `POST /topic/document/remove` → `ApiResponse<Void>`
  - `GET /document/profile/detail?documentId=xxx` → `ApiResponse<DocumentProfileVo>`
  - `POST /document/profile/regenerate` → `ApiResponse<DocumentProfileVo>`
  - `POST /document/profile/batch-regenerate` → `ApiResponse<List<DocumentProfileVo>>`
  - `GET /route/trace/page` → `ApiResponse<KnowledgeRouteTracePageVo>`
  - 查询类改 GET（修正 super-agent 问题 16：全 POST → 查询 GET + 写 POST）
  - Controller 内**不 catch**，全部抛 `DocumentException`

---

## Phase 4 — Neo4j 地基：依赖 / 配置 / Driver / Health / 图模型  `[ ]`

**产出**：Neo4j 条件基础设施 + 图模型 POJO。对标 super-agent `DocumentManageNeo4jConfiguration` + `DocumentGraphNeo4jHealthIndicator` + `model/graph/*`。

### 4.1 依赖

- [ ] **4.1.1 `business/document/pom.xml`** 新增：
  ```xml
  <dependency>
      <groupId>org.neo4j.driver</groupId>
      <artifactId>neo4j-java-driver</artifactId>
  </dependency>
  ```
  版本由 Spring Boot 3.5.6 BOM 管理（5.x 系列）。**不引入** `spring-boot-starter-data-neo4j`（与 super-agent 一致，用原生 Driver）。

### 4.2 配置

- [ ] **4.2.1 `DocumentProperties` 追加 `Neo4j` 嵌套配置**：
  ```java
  static class Neo4j {
      boolean enabled = false;              // 默认关闭
      String uri = "bolt://127.0.0.1:7687";
      String username = "neo4j";
      String password = "neo4j";
      String database = "neo4j";
      int queryTimeoutSeconds = 5;
      int maxConnectionPoolSize = 10;
      long connectionAcquisitionTimeoutMs = 5000;
  }
  ```
- [ ] **4.2.2 `application.yml`** 已有 `spring.neo4j.*` 配置（docker-compose 已拉 Neo4j），确认与 `reuben.document.neo4j.*` 不冲突。`DocumentManageNeo4jConfiguration` 用自己的 `Driver` Bean，不从 `spring.neo4j` 自动配置读取。

### 4.3 Driver Bean

- [ ] **4.3.1 `DocumentManageNeo4jConfiguration`**（`config/`）：
  - `@ConditionalOnProperty(prefix="reuben.document.neo4j", name="enabled", havingValue="true")`
  - `@Bean(destroyMethod = "close") DocumentManageNeo4jDriver` — `GraphDatabase.driver(uri, AuthTokens.basic(user, pass), Config.builder().withConnectionTimeout(...).withMaxConnectionPoolSize(...).build())`
  - 方法名即 Bean 名 → `documentManageNeo4jDriver`

### 4.4 Health Indicator

- [ ] **4.4.1 `DocumentGraphNeo4jHealthIndicator`**（`config/`）：
  - `@Component @ConditionalOnBean(name = "documentManageNeo4jDriver") implements HealthIndicator`
  - `health()`：`try(Session) { session.run("RETURN 1 AS ok").consume() }` → UP + database 详情；catch → DOWN + 异常信息

### 4.5 图模型 POJO

全部 `@Data @Builder @NoArgsConstructor @AllArgsConstructor`，放 `model/graph/`。

- [ ] **4.5.1 `GraphSection`**：nodeId / documentId / parseTaskId / nodeNo / depth / parentNodeId / prevSiblingNodeId / nextSiblingNodeId / nodeCode / title / anchorText / sectionPath / canonicalPath / contentText。`displayTitle()` → sectionPath > (nodeCode + title) > title
- [ ] **4.5.2 `GraphItem`**：nodeId / documentId / parseTaskId / nodeNo / nodeType / sectionNodeId / prevSiblingNodeId / nextSiblingNodeId / title / anchorText / sectionPath / canonicalPath / contentText / itemIndex。`displayText()` → contentText > anchorText > title
- [ ] **4.5.3 `GraphQueryResult`**：targetSection / parentSection / previousSibling / nextSibling / targetItem / children(List) / matchedItems(List) / allItems(List)
- [ ] **4.5.4 `GraphSectionWithChildren`**：section + children
- [ ] **4.5.5 `GraphSectionWithSiblings`**：section + parent + previousSibling + nextSibling
- [ ] **4.5.6 `GraphItemWithContext`**：section + item + siblingItems

---

## Phase 5 — Neo4j 图投影 + 查询服务 + 组合路由  `[ ]`

**产出**：MySQL 结构节点 → Neo4j 投影同步 + Cypher 查询 + MySQL 回退 + 组合路由。对标 super-agent `Neo4jDocumentStructureGraphProjectionService` + `Neo4jDocumentStructureGraphService` + `MysqlDocumentStructureGraphService` + `CompositeDocumentStructureGraphService`。

### 5.1 图服务接口

- [ ] **5.1.1 `DocumentStructureGraphService` 接口**（`service/`）：
  ```java
  boolean isGraphAvailable(Long documentId);
  GraphSection findSectionById(Long documentId, Long sectionNodeId);
  GraphSection findSectionByCode(Long documentId, String nodeCode);
  GraphSection findSectionByTitle(Long documentId, String title);
  GraphSection findSectionByCanonicalPath(Long documentId, String canonicalPath);
  GraphSection findBestSection(Long documentId, String topic, String facet);
  List<GraphSection> listSections(Long documentId);
  List<GraphSection> listChildren(Long documentId, Long sectionNodeId);
  GraphSection parentSection(Long documentId, Long sectionNodeId);
  GraphSection previousSibling(Long documentId, Long sectionNodeId);
  GraphSection nextSibling(Long documentId, Long sectionNodeId);
  GraphItem findItemByIndex(Long documentId, Long sectionNodeId, Integer itemIndex);
  List<GraphItem> listItems(Long documentId, Long sectionNodeId);
  List<GraphItem> searchItemsInSection(Long documentId, Long sectionNodeId, String keyword);
  ```

### 5.2 Neo4j 投影服务

- [ ] **5.2.1 `Neo4jDocumentStructureGraphProjectionService`**（`service/impl/`）：
  - `@Service @ConditionalOnBean(name = "documentManageNeo4jDriver")`
  - **`@PostConstruct initSchema()`**：创建 8 个索引（Document.documentId / Section.nodeId / Section.documentId / Section.(documentId,nodeId) / Section.(documentId,nodeCode) / Section.(documentId,normalizedTitle) / Item.nodeId / Item.(documentId,nodeId)）——仅不存在时创建
  - **`projectToGraph(documentId, parseTaskId)`**：单 `session.executeWrite` 事务内：
    1. `MATCH (n) WHERE (n:Document OR n:Section OR n:Item) AND n.documentId = $documentId DETACH DELETE n`
    2. `CREATE (d:Document {documentId, documentName, parseTaskId, currentVersion})`
    3. batch `CREATE (s:Section {...})` — 所有 SECTION 节点
    4. batch `CREATE (i:Item {...})` — 所有 STEP/LIST_ITEM 节点
    5. 遍历：`MERGE (d)-[:HAS_SECTION]->(s)` / `MERGE (s)-[:BELONGS_TO_DOCUMENT]->(d)`
    6. 遍历：`MERGE (p)-[:HAS_CHILD]->(s)` (parentNodeId→nodeId)
    7. 遍历：`MERGE (a)-[:NEXT_SIBLING]->(b)` / `MERGE (b)-[:PREV_SIBLING]->(a)`
    8. 遍历：`MERGE (s)-[:HAS_ITEM]->(i)` / `MERGE (i)-[:BELONGS_TO_SECTION]->(s)`
    9. 遍历：`MERGE (a)-[:NEXT_ITEM]->(b)` / `MERGE (b)-[:PREV_ITEM]->(a)`
    - 完成更新 `DocumentTask.extJson` 记录 `graph_index_status=SUCCESS` + `last_graph_index_time`
  - **`deleteByDocumentId(documentId)`**：`MATCH (n) WHERE n.documentId = $documentId DETACH DELETE n`
  - 所有 Neo4j 异常 catch + warn 日志，更新 task `graph_index_status=FAILED` + `graph_index_error_msg`

### 5.3 Neo4j 图查询服务

- [ ] **5.3.1 `Neo4jDocumentStructureGraphService`**（`service/impl/`）：
  - `@Service @ConditionalOnBean(name = "documentManageNeo4jDriver")`
  - 每个方法使用 **try-with-resources Session**（`SessionConfig.forDatabase(database)`），执行参数化 Cypher，映射到 `GraphSection`/`GraphItem`（修正 super-agent 问题 11：session 泄漏）
  - 异常打 warn + 抛 `DocumentException(NEO4J_QUERY_FAILED)`（修正 super-agent 问题 12：静默吞）
  - `findBestSection()`：`listSections()` 后在 Java 内存评分（title 包含 +8、anchorText 包含 +6、contentText 包含 +2；facet 匹配 +5 精确/+1 部分），取最高分
  - `asLong/asInteger/asText` 安全读取 Neo4j Value（null-safe）

### 5.4 MySQL 回退服务

- [ ] **5.4.1 `MysqlDocumentStructureGraphService`**（`service/impl/`）：
  - `@Service("mysqlDocumentStructureGraphService")`
  - 全部实现通过 `IDocumentStructureNodeService.listDocumentNodes(documentId, null)` 获取全量节点
  - **增加 `ConcurrentHashMap<Long, List<GraphSection>>` 缓存**（per documentId），解析完成时 `projectToGraph` 调用后主动刷新；`deleteByDocumentId` 后主动清除。大文档（>2000 节点）降级每次 SQL 直查（修正 super-agent 问题 5：O(n) per query）
  - `findBestSection()` 评分逻辑与 Neo4j 版一致
  - 兄弟关系通过 `prevSiblingNodeId`/`nextSiblingNodeId` 字段 + `findSectionById` 解析

### 5.5 组合路由服务

- [ ] **5.5.1 `CompositeDocumentStructureGraphService`**（`service/impl/`）：
  - `@Primary @Service @AllArgsConstructor`
  - 依赖：`MysqlDocumentStructureGraphService` + `ObjectProvider<Neo4jDocumentStructureGraphService>`
  - **`delegate(documentId)`**：Neo4j bean 存在 → 调 `isGraphAvailable(documentId)` → true 走 Neo4j，false 走 MySQL。使用 `ConcurrentHashMap<Long, Boolean> graphAvailabilityCache`（修正 super-agent 问题 6：每次查询一次 Cypher）
  - 所有接口方法一行委派 `delegate(documentId).method(...)`

---

## Phase 6 — Neo4j 图查询引擎 + Executor 增强  `[ ]`

**产出**：chat 侧可用的图查询引擎 + 图答案渲染 + 增强现有 `GraphOnlyExecutor` / `GraphThenEvidenceExecutor` 接入 Neo4j。对标 super-agent `StructureGraphQueryEngine` + `GraphAnswerRenderer`。

### 6.1 图查询引擎

代码放 `business/chat`（chat 侧消费 document 侧 `DocumentStructureGraphService`）。

- [ ] **6.1.1 `StructureGraphQueryEngine`**（`chat/orchestrate/` 或 `chat/support/`）：
  - `@Service @AllArgsConstructor`，注入 `DocumentStructureGraphService`（按类型，实际是 `CompositeDocumentStructureGraphService`）
  - `findSectionWithChildren(documentId, topic) → GraphSectionWithChildren` — `findBestSection` → `listChildren`
  - `findSectionWithChildren(documentId, sectionNodeId) → GraphSectionWithChildren`
  - `findSectionWithSiblings(documentId, sectionNodeId) → GraphSectionWithSiblings` — `findSectionById` + `parentSection` + `previousSibling` + `nextSibling`
  - `findItemInSection(documentId, sectionTopic, itemIndex) → GraphItemWithContext`
  - `findItemInSection(documentId, sectionNodeId, itemIndex) → GraphItemWithContext`
  - `searchItemsInSectionTree(documentId, sectionNodeId, keyword) → List<GraphItem>` — 递归遍历子章节的 items
  - `buildGraphResult(documentId, targetSectionNodeId, targetItemIndex, itemKeyword) → GraphQueryResult` — 聚合查询（章节+子节点+items+目标item+匹配items+父/兄弟）

### 6.2 图答案渲染器

- [ ] **6.2.1 `GraphAnswerRenderer`**（`chat/orchestrate/`）：
  - **`GRAPH_ONLY` 模式**：
    - 邻接问题（"上一节/下一节/属于哪个章节"）→ 渲染 target + parent + prev/next sibling
    - 子章节问题（"包含哪些章节"）→ 渲染 target + children 列表
    - 默认 → 渲染章节标题 + contentText（截断）
  - **`GRAPH_THEN_EVIDENCE` 模式**：
    - 有 targetItem → "第X步：contentText"
    - 有 matchedItems → 命中列表
    - 默认 → section.contentText
  - `asksAdjacency(String question)` / `asksChildren(String question)` / `asksStructure Outline(String question)` — 从 `DocumentProperties.Navigation` 或 `ChatIntentHints` 读关键词列表（修正 super-agent 问题 7）

### 6.3 Executor 增强

- [ ] **6.3.1 增强 `GraphOnlyExecutor`**（`chat/orchestrate/`）：
  - 注入 `StructureGraphQueryEngine` + `GraphAnswerRenderer`（替代当前直接查 MySQL `DocumentStructureNodeService`）
  - 大文档（>50 节点）加 LLM 压缩摘要：`observedChatModelService.callText(stageName, structureText, ...)`（修正 super-agent 问题 10），阈值进配置 `ChatProperties.Graph.structureSummaryNodeThreshold`
  - `execute()` 通过 `NavigationDecision.structureAnchor` 决定查哪个章节

- [ ] **6.3.2 增强 `GraphThenEvidenceExecutor`**（`chat/orchestrate/`）：
  - 注入 `StructureGraphQueryEngine` + `ChatRagRetrievalAdapter`
  - 先 `buildGraphResult()` 定位章节 → 缩小 `structureNodeId` filter → 调 `ChatRagRetrievalAdapter.retrieveWithNodeFilter()`
  - 证据校验：有 itemAnchor 则必须找到目标 item；否则校验 contentText 非空

- [ ] **6.3.3 增强 `ChatPreparationOrchestrator.decideNavigation()`**：接入 `StructureGraphQueryEngine` 辅助判断结构定位类问题（当前 `decideNavigation` 始终返回 `WHOLE_DOCUMENT`，Phase 8 接入 `DocumentQuestionRouter` 后改为真正的导航决策）

---

## Phase 7 — Navigation Index 地基：ES 索引 / 记录 / 初始化 / 搜索  `[ ]`

**产出**：ES 导航章节索引 + 搜索服务。在 document 解析管线中构建，在 chat 管线中被 `DocumentQuestionRouter` 消费。对标 super-agent `DocumentNavigationIndexService` + `ElasticsearchDocumentNavigationIndexService` + `DocumentNavigationIndexRecord` + `DocumentNavigationElasticsearchIndexInitializer`。

### 7.1 索引记录模型

- [ ] **7.1.1 `DocumentNavigationIndexRecord`**（`model/es/`）：
  - `nodeId(Long)` / `documentId(Long)` / `parseTaskId(Long)` / `nodeType(String)` / `nodeCode(String)` / `nodeNo(Integer)` / `depth(Integer)` / `parentNodeId(Long)` / `title(String)` / `anchorText(String)` / `sectionPath(String)` / `canonicalPath(String)` / `contentText(String)` / `itemIndex(Integer)`
  - `@Builder`，用于 ES bulk 写入

### 7.2 ES 索引初始化

- [ ] **7.2.1 `DocumentNavigationElasticsearchIndexInitializer`**（`config/`）：
  - `@PostConstruct` 检查 + 创建索引 `reuben_agent_document_navigation`
  - 字段映射：`nodeId`/`documentId`/`parseTaskId`/`parentNodeId` → `long`；`nodeNo`/`depth`/`itemIndex` → `integer`；`nodeType`/`nodeCode` → `keyword`；`canonicalPath` → `keyword`；`title`/`anchorText`/`sectionPath`/`contentText` → `text`（analyzer=来自配置，默认 `ik_max_word`；searchAnalyzer=默认 `ik_smart`）
  - Analyzer 回退同 Phase 1
  - 索引名来自 `DocumentProperties.Elasticsearch.navigationIndexName`（默认 `reuben_agent_document_navigation`）

### 7.3 导航索引服务

- [ ] **7.3.1 `DocumentNavigationIndexService` 接口**（`service/`）：
  - `void reindexDocumentNodes(Long documentId, Long parseTaskId, List<DocumentStructureNode> nodes)` — 删除旧索引 → bulk 写入新节点
  - `void deleteByDocumentId(Long documentId)` — 按文档删除
  - `List<NavigationSectionHit> searchSections(Long documentId, String topic, String facet, String informationNeed, String question, int size)` — 四维章节搜索

- [ ] **7.3.2 `NavigationSectionHit` 记录**：`nodeId` / `nodeCode` / `title` / `sectionPath` / `canonicalPath` / `score(double)`

- [ ] **7.3.3 `EsDocumentNavigationIndexService`**（`service/impl/`）：
  - `@ConditionalOnProperty(prefix="reuben.document.elasticsearch", name="enabled", havingValue="true", matchIfMissing=true)`
  - **`reindexDocumentNodes()`**：`deleteByQuery(term(documentId))` → `toIndexRecord(node)` 转换 → `bulkIndex(Refresh.WaitFor)`。只索引 `nodeType=SECTION` 的节点（step/list_item 不索引入导航索引）
  - **`deleteByDocumentId()`**：`deleteByQuery(term(documentId))`
  - **`searchSections()`**：
    - `buildQueries(topic, facet, informationNeed, question)` → 去重非空 query text 列表
    - bool query → filter(`term(documentId)` + `term(nodeType=SECTION)`) + should（每个 query text：`matchPhrase(title, boost=20)` + `matchPhrase(sectionPath, boost=15)` + `multiMatch(title^10, sectionPath^8, anchorText^5, contentText, BestFields)`）→ `minimumShouldMatch("1")` → size=max(cap=20)

### 7.4 接入 document 解析管线

- [ ] **7.4.1 在 `DocumentAsyncProcessServiceImpl`（或等效的索引构建完成回调）中**：`DocumentStructureNode` 持久化后 → `ObjectProvider<DocumentNavigationIndexService>.getIfAvailable()` → `reindexDocumentNodes(documentId, parseTaskId, nodes)`
- [ ] **7.4.2 文档删除时**：`DocumentManageServiceImpl.deleteDocument()` → `documentNavigationIndexService.deleteByDocumentId(documentId)`

---

## Phase 8 — Document Question Router  `[ ]`

**产出**：规则+LLM 双引擎导航决策路由。对标 super-agent `DocumentQuestionRouter`（全动作：SECTION_ADJACENCY_LOOKUP / CHILD_SECTION_DESCEND / ITEM_REFERENCE / FRESH_TOPIC / TOPIC_CONTINUE / SIBLING_SECTION_SWITCH / ANCESTOR_SECTION_RETURN）。

### 8.1 导航模型增强（chat 侧）

reuben-agent chat 已有 `DocumentNavigationAction` / `NavigationScopeMode` / `DocumentNavigationDecision` / `ConversationExecutionPlan`。需增强：

- [ ] **8.1.1 `DocumentNavigationAction` 补全枚举值**：
  - 保留：`DIRECT_RETRIEVAL`(1) / `LOCATE_THEN_RETRIEVE`(2) / `GRAPH_ONLY`(3)
  - 新增：`TOPIC_CONTINUE`(4) / `TOPIC_SWITCH`(5) / `FRESH_TOPIC`(6) / `SIBLING_SECTION_SWITCH`(7) / `CHILD_SECTION_DESCEND`(8) / `ANCESTOR_SECTION_RETURN`(9) / `ITEM_REFERENCE`(10) / `SECTION_ADJACENCY_LOOKUP`(11)
  - `toExecutionMode()` 更新映射：`GRAPH_ONLY/SECTION_ADJACENCY_LOOKUP/CHILD_SECTION_DESCEND/SIBLING_SECTION_SWITCH/ANCESTOR_SECTION_RETURN` → `GRAPH_ONLY`；`ITEM_REFERENCE/LOCATE_THEN_RETRIEVE` → `GRAPH_THEN_EVIDENCE`；其余 → `RETRIEVAL`

- [ ] **8.1.2 `NavigationScopeMode` 补全枚举值**：
  - 保留：`WHOLE_DOCUMENT`(1) / `SECTION_SCOPE`(2) / `PARENT_BLOCK_SCOPE`(3)
  - 新增：`NONE`(0) / `SOFT`(4) / `HARD_SECTION`(5) / `HARD_ITEM`(6) / `HARD_PARENT_WITH_SIBLINGS`(7)

- [ ] **8.1.3 `DocumentNavigationDecision` 补字段**：
  - 已有：`action` / `scopeMode` / `sectionPath` / `parentBlockId` / `reason`
  - 新增：`structureAnchor(ConversationStructureAnchor)` — 含 rootSectionCode/rootSectionTitle/targetSectionHint/structureNodeId/canonicalPath/scopeMode
  - 新增：`itemAnchor(ConversationItemAnchor)` — 含 itemIndex/itemText/structureNodeId/canonicalPath
  - 新增：`retrievalPlan` — 含 rewrittenQuery/subQuestions
  - 新增：`queryContextHints(List<String>)` — 检索关键词提示
  - 新增：`softSectionHints(List<String>)` — RETRIEVAL 模式的软章节提示

- [ ] **8.1.4 新建 `ConversationStructureAnchor`**（`chat/model/orchestrate/`）：`rootSectionCode` / `rootSectionTitle` / `targetSectionHint` / `structureNodeId` / `canonicalPath` / `scopeMode(String)`
- [ ] **8.1.5 新建 `ConversationItemAnchor`**（`chat/model/orchestrate/`）：`itemIndex` / `itemText` / `structureNodeId` / `canonicalPath`

### 8.2 意图关键词配置

- [ ] **8.2.1 `ChatIntentHints` 补全**（`chat/support/`，或进 `ChatProperties.Navigation`）：
  - `ADJACENCY_HINTS` — 上一节/下一节/前一节/后一节/上一章/下一章/属于哪个章节/章节位置
  - `OUTLINE_HINTS` — 包含哪些章节/有哪些章节/有哪些小节/章节列表/目录
  - `OUTLINE_EXPLICIT_HINTS` — 子章节/子小节/下级章节/展开目录/列出目录
  - `ITEM_HINTS` — 哪一步/哪一项/第几步/第几项/具体步骤/步骤中的
  - `ANALYTIC_STRONG_HINTS` — 为什么/原因/影响/区别/对比/比较/如何理解/分析/解释
  - `STRUCTURAL_RELATION_HINTS` — 前后关系/相邻关系/上下级关系/父子关系/属于哪个章节
  - `GRAPH_ONLY_STRUCTURE_OBJECT_HINTS` — 章节/小节/这章/这节/这部分/标题/目录/模块/节点
  - `GRAPH_ONLY_OUTLINE_ACTION_HINTS` — 下面/下级/子章节/子小节/展开/包含哪些/列出/组成
  - `GRAPH_ONLY_EXPLICIT_ADJACENCY_HINTS` — 前一个/后一个/上一个/下一个/前一/后一/相邻/前后/位置
  - （等等，共约 20 组关键词列表，全部从 `ChatProperties.Navigation` 读，默认值硬编码在配置类中，允许 yml 覆盖——修正 super-agent 问题 7）

### 8.3 Document Question Router

- [ ] **8.3.1 `DocumentQuestionRouter`**（`chat/orchestrate/`）：
  - `@Service @AllArgsConstructor`
  - 依赖：`DocumentStructureGraphService` / `ObjectProvider<DocumentNavigationIndexService>` / `ObservedChatModelService` / `ChatPromptTemplateService` / `ChatProperties`
  - **`route(Long documentId, String originalQuestion, ChatRewriteResult rewriteResult) → DocumentNavigationDecision`**

  **Phase 1 — 规则引擎**（`detectGraphOnlyIntentByRules`）：
  1. 预编译 `Pattern`：`SECTION_CODE_PATTERN`=`(\d+(?:\.\d+)+)`、`CHINESE_SECTION_REFERENCE_PATTERN`=`第\s*([0-9一二三四五六七八九十百]+)\s*(章|节|小节)`、`STEP_REFERENCE_PATTERN`=`第\s*([0-9一二三四五六七八九十百]+)\s*步`、`ORDINAL_REFERENCE_PATTERN`=`第\s*([0-9一二三四五六七八九十百]+)\s*(条|点|项|个)`、`QUOTED_TEXT_PATTERN`=`["“”]([^“”]{2,40})["“”]`（修正 CLAUDE.md §8）
  2. 优先级判定：
     - Adjacency hints 命中 → ACTION=`SECTION_ADJACENCY_LOOKUP`，confidence=1.0
     - Section code + direction → ACTION=`SECTION_ADJACENCY_LOOKUP`，confidence=0.92
     - Quoted title + direction → ACTION=`SECTION_ADJACENCY_LOOKUP`，confidence=0.90
     - Structure object + explicit adjacency → ACTION=`SECTION_ADJACENCY_LOOKUP`，confidence=0.86
     - Outline explicit → ACTION=`CHILD_SECTION_DESCEND`，confidence=1.0
     - Anchor + outline action → ACTION=`CHILD_SECTION_DESCEND`，confidence=0.86
     - Item hints + no analytic → ACTION=`ITEM_REFERENCE`，confidence=0.80

  **Phase 2 — LLM Fallback**（仅规则不确定时触发）：
  1. 条件：单子问题 + 非强分析类 + 有结构导航线索
  2. `Mono.fromCallable(...).subscribeOn(boundedElastic)`（修正 super-agent 问题 9）
  3. 调用 prompt 模板 `document-graph-only-intent.st`（需新建），temperature=0.0
  4. FastJSON 解析 LLM 输出（修正 super-agent 问题 8：不用 `indexOf("{"))`）

  **Section Resolution**（`resolveSection`）：
  1. By section code → `graphService.findSectionByCode()`
  2. By navigation index → `navigationIndexService.searchSections()` → `graphService.findSectionById()`
  3. By local structure scoring（sectionPath=100, title=90, anchorText=80, contentText=45，阈值 45）
  4. By best section → `graphService.findBestSection()`

  **`buildDecision()`**：综合 Phase 1/2 结果 + Section 定位 → 组装 `DocumentNavigationDecision`（action + executionMode + structureAnchor + itemAnchor + retrievalPlan + queryContextHints + softSectionHints）

### 8.4 Prompt 模板

- [ ] **8.4.1 `document-graph-only-intent.st`**（`business/chat/src/main/resources/prompt/`）：
  - System：判断用户问题是否需要纯结构图查询 / 章节邻接查询 / 条目查询 / 证据检索
  - 输出 JSON：`{ "intent_type": "ADJACENCY|OUTLINE|ANALYTIC|ITEM_LOOKUP|CONTENT_QA", "confidence": 0.0-1.0, "action": "SECTION_ADJACENCY_LOOKUP|CHILD_SECTION_DESCEND|ITEM_REFERENCE|FRESH_TOPIC", "graph_only": boolean, "analytic": boolean, "outline": boolean, "item_lookup": boolean, "structure_hint": boolean, "reason": "..." }`
  - 置信度阈值 `0.75` 进配置

---

## Phase 9 — 全量集成与接线  `[ ]`

**产出**：三模块串入现有 document 解析管线 + chat 编排管线 + 端到端可跑。

### 9.1 Document 解析管线接入

- [ ] **9.1.1 结构节点持久化后**（`DocumentAsyncProcessServiceImpl` 或等效的 Stage 3/4 完成回调）：
  - `Neo4jDocumentStructureGraphProjectionService.projectToGraph(documentId, parseTaskId)`（Neo4j enabled 时）
  - `DocumentNavigationIndexService.reindexDocumentNodes(documentId, parseTaskId, structureNodes)`（ES enabled 时）
  - 两者异步（`@Async` 或 `CompletableFuture.runAsync`），失败不阻塞主解析管线

- [ ] **9.1.2 文档删除时**（`DocumentManageServiceImpl.deleteDocument()`）：
  - `Neo4jDocumentStructureGraphProjectionService.deleteByDocumentId(documentId)`
  - `DocumentNavigationIndexService.deleteByDocumentId(documentId)`
  - `KnowledgeRouteIndexService.deleteDocumentRoute(documentId)`
  - `CompositeDocumentStructureGraphService` 缓存失效

- [ ] **9.1.3 Scope/Topic/Relation 变更后**（`KnowledgeManageServiceImpl`）：
  - `KnowledgeRouteIndexService.refreshAll()`（异步，防抖 30s）

### 9.2 Chat 编排管线接入

- [ ] **9.2.1 `ChatPreparationOrchestrator.modeBranch()` 增强**：
  - `AUTO_DOCUMENT` 模式：调用 `KnowledgeRouteService.route(question, rewriteQuestion)` → 取 `topDocument()` → 若 低置信度 → CLARIFICATION（生成候选文档列表让用户选）；否则选中文档 → 继续

- [ ] **9.2.2 `ChatPreparationOrchestrator.decideNavigation()` 替换**：
  - 当前本地硬编码逻辑 → 替换为 `DocumentQuestionRouter.route(documentId, question, rewriteResult)` → 产完整的 `DocumentNavigationDecision`（action + executionMode + structureAnchor + itemAnchor）
  - 删除当前 `decideNavigation` 中始终返回 `WHOLE_DOCUMENT` 的占位逻辑

- [ ] **9.2.3 `KnowledgeRouteTrace` 写入**：
  - DOCUMENT 模式 → `KnowledgeRouteService.recordShadowRoute(conversationId, turnId, selectedDocumentId, question, rewriteQuestion)`
  - AUTO_DOCUMENT 模式 → `KnowledgeRouteService.recordAutoRoute(conversationId, turnId, question, rewriteQuestion, decision)`

### 9.3 Executor 分发接入

- [ ] **9.3.1 `ConversationExecutorRegistry`** 确认 `SECTION_ADJACENCY_LOOKUP` / `CHILD_SECTION_DESCEND` 等新增 action 对应的 `ExecutionMode` 能正确路由到 `GraphOnlyExecutor` / `GraphThenEvidenceExecutor`
- [ ] **9.3.2 `GraphOnlyExecutor` / `GraphThenEvidenceExecutor`** 从 `DocumentNavigationDecision.structureAnchor` / `itemAnchor` 读取章节定位信息，通过 `StructureGraphQueryEngine` 查询

### 9.4 配置装配

- [ ] **9.4.1 `application.yml`** 补全：
  ```yaml
  reuben:
    document:
      neo4j:
        enabled: false  # 默认关闭，需要时手动开启
        uri: bolt://127.0.0.1:7687
        username: neo4j
        password: ${NEO4J_PASSWORD:neo4j123}
        database: neo4j
        query-timeout-seconds: 5
      knowledge-route:
        enabled: true
        # ... 权重配置（全部带默认值，允许调参）
      elasticsearch:
        route-index-name: reuben_agent_knowledge_route
        navigation-index-name: reuben_agent_document_navigation
    chat:
      navigation:
        # ... 20+ 关键词列表可配置覆盖
  ```
- [ ] **9.4.2 编译验证**：`mvn compile -pl launcher -am` 通过

---

## Phase 10 — 集成测试 + 验收  `[ ]`

**产出**：Docker 集成测试 + 端到端验证 + 验收清单。

### 10.1 集成测试

- [ ] **10.1.1 `KnowledgeDockerIntegrationTest`**（`@ActiveProfiles("docker")` + 真实 MySQL/ES/Embedding）：
  - Scope CRUD 全流程
  - Topic CRUD + Relation 绑定
  - 路由引擎：有 scope/topic 节点时三级路由打分 → 低置信度降级 → 无节点时 fallback derive
  - Route trace 落库
- [ ] **10.1.2 `Neo4jDockerIntegrationTest`**（`@ActiveProfiles("docker")` + 真实 MySQL/Neo4j）：
  - `projectToGraph` → 节点+关系落 Neo4j
  - `findSectionByCode` / `listChildren` / `previousSibling` / `nextSibling` → Cypher 查询验证
  - `CompositeDocumentStructureGraphService` → Neo4j 路由 + MySQL 回退
  - `deleteByDocumentId` → 级联清图
- [ ] **10.1.3 `NavigationIndexDockerIntegrationTest`**（`@ActiveProfiles("docker")` + 真实 MySQL/ES）：
  - `reindexDocumentNodes` → ES 索引可搜索
  - `searchSections` → 按 topic/facet/question 查询返回章节
  - `DocumentQuestionRouter.route` → 规则引擎命中 + LLM fallback
- [ ] **10.1.4 端到端集成测试**（`@ActiveProfiles("docker")` + 全套中间件，LLM mock）：
  - DOCUMENT 模式：知识路由 → 导航决策 → executor 分发 → 回答
  - AUTO_DOCUMENT 模式：全链路上游到下游
  - CLARIFICATION 短路：低置信度候选文档列表

### 10.2 验收清单（功能对齐 super-agent，不阉割）

- [ ] Knowledge Scope CRUD + 列表查询
- [ ] Knowledge Topic CRUD + 按 scope 筛选
- [ ] Topic-Document Relation CRUD + 关联分数
- [ ] 文档画像查询 + 重新生成（单个/批量）
- [ ] 三级路由引擎（scope → topic → document）+ 语义+ES 词法+实体词
- [ ] Fallback 链（无 scope 节点→derive from documents；无 topic 节点→derive from profiles；无 Embedding→纯词法；无 ES→纯语义+关键词）
- [ ] ES 知识路由索引（refreshAll + search + deleteDocumentRoute）
- [ ] 路由追踪落库 + 分页查询
- [ ] Neo4j Driver 条件 Bean（`enabled=false` 不创建）
- [ ] Neo4j 图投影（MySQL 结构节点 → Neo4j Document/Section/Item + 关系）
- [ ] Neo4j 图查询（按 code/title/id/兄弟/父子/条目 全 12 个方法）
- [ ] MySQL 回退（无 Neo4j 时 Composite 走 MySQL，缓存加速）
- [ ] Neo4j Health Indicator
- [ ] Graph Query Engine（章节+子节点+兄弟+条目递归查询）
- [ ] Graph Answer Renderer（GRAPH_ONLY 邻接/大纲/子章节；GRAPH_THEN_EVIDENCE 条目/命中/内容）
- [ ] ES 导航索引（reindex + searchSections 四维搜索 + deleteByDocumentId）
- [ ] Document Question Router（规则引擎 7 级优先级 + LLM fallback + Section Resolution 4 级回退）
- [ ] 导航决策全 8 动作（`SECTION_ADJACENCY_LOOKUP` / `CHILD_SECTION_DESCEND` / `ITEM_REFERENCE` / `FRESH_TOPIC` / `TOPIC_CONTINUE` / `SIBLING_SECTION_SWITCH` / `ANCESTOR_SECTION_RETURN` / `TOPIC_SWITCH`）
- [ ] chat 编排管线完整接入（知识路由 → 导航决策 → executor 分发）
- [ ] 全程符合 reuben-agent 风格（异常/枚举/Builder/注入/注释），super-agent 16 项问题已修正

---

## 风险与决策点

| 决策 | 选项 | 倾向 |
|------|------|------|
| Knowledge 代码放哪个模块 | 新建 `business/knowledge` vs 放 `business/document` | 放 document（知识与文档强耦合，scope/topic/relation 表与 document 同库，不新建 Maven module） |
| Neo4j 使用方式 | Spring Data Neo4j (`@Node`) vs 原生 Driver + Cypher | 原生 Driver（对齐 super-agent，更灵活，不引入 ORM 框架约束） |
| Neo4j 默认状态 | enabled=true vs enabled=false | **默认 false**（对齐 super-agent 行为，Neo4j 是可选增强，大多数部署不需要图数据库） |
| Graph 查询缓存 (MySQL 回退) | 每次全量加载 vs `ConcurrentHashMap` 缓存 | 缓存 + 大文档阈值降级（修正 super-agent O(n) per query 问题） |
| Navigation Index 索引入口 | 结构节点持久化后自动触发 vs 手动 API | 自动触发（解析管线 Stage 3/4 完成后异步建索引，对齐 super-agent 的 `syncNavigationArtifacts`） |
| Document Question Router LLM fallback | 始终调 LLM vs 规则优先 + LLM 补充 | **规则优先**（零延迟覆盖 80% case），LLM 仅在规则不确定且满足条件时补充 |
| 路由追踪写入 | 同步 vs 异步 | 异步（不阻塞主路由路径，失败仅 warn） |
| ES 索引刷新策略 | `ScheduledExecutorService` 定时 vs `AtomicLong` 防抖 | 定时器 + `ReentrantLock`（修正 super-agent 竞态问题） |
