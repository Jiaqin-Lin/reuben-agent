# RAG 检索接口实现计划

## 背景与约束

### 项目背景

reuben-agent 文档模块已完成全量管线：

- 文件上传 → Tika 解析 → 结构树提取（4-Stage Pipeline）
- 策略推荐 → 切块执行（structure/recursive/semantic/LLM 四策略）
- 向量化入库（PGVector `reuben_agent_document_embedding` 表）
- 关键词索引（ES `reuben_document_chunk` 索引）
- Kafka 异步编排（parse-route → index-build）

**数据已经在 PGVector 和 ES 里了，就差一个检索端把数据查出来。** RAG 模块当前是空壳（只有 `.gitkeep`），下一步就是实现检索召回接口。

### 目标

实现 `POST /api/rag/retrieve` 接口，背后是 **向量检索 + 关键词检索 → RRF 融合** 的混合检索引擎。对标同目录 super-agent 的 `RagRetrievalEngine` + `HybridSearchService`，但以自己的代码风格重写。
对于 super-agent 不好的 代码风格/类、函数、字段变量命名/防御性编程/业务处理 都可以提出优化 不追求1:1复刻

### 代码风格约束（来自 CLAUDE.md + memory）

1. **异常体系**：业务层禁止 `throw new RuntimeException("字符串")`，必须抛 `BusinessException` 子类
2. **枚举规范**：所有 code/msg 枚举必须 `implements BaseEnum`，`getFromCode` 委托 `EnumUtils`
3. **Controller 响应**：统一返回 `ApiResponse<T>`，异常由 `GlobalExceptionHandler` 统一处理
4. **实体规范**：继承 `BaseTableData`，主键雪花 ID + `@TableId(type = IdType.INPUT)`，`@Builder` + `@AllArgsConstructor` + `@NoArgsConstructor`
5. **注入规范**：`@AllArgsConstructor` 构造器注入，禁止 `@Autowired` 字段注入
6. **Builder 优先**：连续 3 次以上 `setXxx()` 使用 `@Builder` 构建
7. **日志规范**：关键路径（状态变更、异常、外部调用）补日志
8. **注释风格**：简洁 `// 阶段 N：做什么` 即可
9. **命名规范**：Entity 无前缀，Service 用 `I` + 实体名 + `Service` / 实体名 + `ServiceImpl`
10. **模块隔离**：禁止 business 模块间 Maven 依赖（可通过共享存储解耦）

### 不严格 1:1 复刻原则

super-agent 只是参考版本，以下问题在移植时修正：

| # | super-agent 问题 | reuben-agent 优化方向 |
|---|-----------------|---------------------|
| 1 | `RagRetrievalEngine` 300+ 行，融合逻辑、超时控制、结果截断混在一起 | 拆分为 `RagRetrievalService`(编排) + `RrfFusionService`(纯融合) |
| 2 | `RetrievalChannel` 接口有 `supports()` 方法但实际只用 `retrieve()` | 去掉过度抽象——两个通道各一个类，不用接口多态 |
| 3 | 向量检索引擎依赖 Spring AI `VectorStore` 抽象，底层 SQL 不透明 | 直接写 JDBC SQL 查 PGVector，更可控、更好调试 |
| 4 | `HybridSearchService` 的 RRF 实现用 `HashMap` 逐条 merge，O(n²) | 用 `LinkedHashMap` 单次遍历 + `compute` 合并，O(n) |
| 5 | ES 查询构造用 builder 嵌套 5 层，可读性差 | 提取 query builder 为私有方法，主流程保持扁平 |
| 6 | 超时后不返回任何结果（`CompletableFuture.orTimeout` + 异常吞掉） | 超时不阻塞另一通道，有结果就融合返回 |
| 7 | 类名含 `SuperAgent` 前缀 | reuben-agent 已统一无品牌前缀 |
| 8 | 多处 `System.out.println` 残留 | 全部用 `log` |
| 9 | metadata 字段用字符串 key 散落各处 | 定义 `MetadataKeys` 常量类统一管理 |

### reuben-agent 已有基础（可直接复用）

- **PGVector 表** `public.reuben_agent_document_embedding` — embedding (vector), chunk_text, metadata_json (JSONB), 含 IVFFlat 余弦索引
- **ES 索引** `reuben_document_chunk` — chunkText (text/standard analyzer), documentId, sectionPath 等
- **EmbeddingModel** — Spring AI 已配置（bge-m3, 1024 维），`DefaultDocumentVectorGateway` 在用
- **PGVector DataSource** — `DocumentPgVectorConfiguration` 已定义，RAG 模块可定义自己的只读 JdbcTemplate
- **ES Client** — `ElasticsearchClient` bean 已就绪
- **DocumentProperties** — 已含 PGVector/ES/Embedding 配置，RAG 模块定义自己的 `RagProperties`

---

## Phase 总览

```
已完成 (Phase 1-6):
  Properties ──→ DTO/VO/Enum ──→ 向量检索 + 关键词检索 ──→ RRF + 引擎 ──→ Controller

待补齐 (Phase 10-11):
  Phase 10 (Rerank) ──→ Phase 11 (集成验证)
                                                                      │
                                                                 Phase 10 (Rerank)
                                                                      │
                                                                 Phase 11 (集成验证)
```

Phase 7-10 对应 super-agent `RagRetrievalEngine` 中缺失的 4 个关键环节，补齐后的检索管线：

```
query → rewrite → 并行双通道 → evidence gate → RRF → parent block elevation → rerank → topK → 结果
```

---

## Phase 1: RAG Properties 配置

> 依赖：无 | 工作量：~0.5h

### 1.1 `RagProperties` 配置类

- [x] 创建文件 `business/rag/src/main/java/com/reubenagent/rag/config/RagProperties.java`
- [x] 注解：`@Data @ConfigurationProperties(prefix = "reuben.rag")`
- [x] 内部类 `Retrieval`（检索参数）：
  - `int vectorTopK = 8` — 向量通道召回数
  - `int keywordTopK = 8` — 关键词通道召回数
  - `int finalTopK = 5` — 融合后最终返回数
  - `int rrfK = 60` — RRF 公式 K 值
  - `long channelTimeoutMs = 5000` — 单通道超时
- [x] 内部类 `Pgvector`（PGVector 只读连接，**独立于 document 模块的 DataSource**）：
  - `String host = "127.0.0.1"`
  - `int port = 5432`
  - `String database = "reuben_agent_pgvector"`
  - `String username = "reuben"`
  - `String password = "reuben123"`
  - `String tableName = "public.reuben_agent_document_embedding"`
  - `String poolName = "RagPgVectorPool"` — **连接池名与 document 模块区分**
- [x] 内部类 `Elasticsearch`：
  - `String indexName = "reuben_document_chunk"` — 与 document 模块写入同一索引
- [x] 内部类 `Embedding`：
  - `String model = "bge-m3"`
  - `int dimension = 1024`

### 1.2 `application.yml` 补充

- [x] 在 `launcher/src/main/resources/application.yml` 中添加 `reuben.rag.*` 默认值

### 1.3 启用 ConfigurationProperties

- [x] 在 `LauncherApplication` 或新增 `RagConfiguration` 上添加 `@EnableConfigurationProperties(RagProperties.class)`

---

## Phase 2: DTO / VO / 枚举

> 依赖：Phase 1 | 工作量：~1h

### 2.1 `RagRetrieveRequest` (DTO)

- [x] 创建文件 `business/rag/src/main/java/com/reubenagent/rag/dto/RagRetrieveRequest.java`
- [x] 注解：`@Data @Builder @NoArgsConstructor @AllArgsConstructor`
- [x] 字段：
  - `String query` — 检索查询文本（必填）
  - `Integer topK` — 返回数量，默认取 `RagProperties.retrieval.finalTopK`
  - `Map<String, String> filterFields` — 过滤条件（如 `documentId → "123"`）

### 2.2 `RagRetrieveResponse` (VO)

- [x] 创建文件 `business/rag/src/main/java/com/reubenagent/rag/vo/RagRetrieveResponse.java`
- [x] 注解：`@Data @Builder @NoArgsConstructor @AllArgsConstructor`
- [x] 字段：
  - `List<RetrievalResult> results` — 检索结果列表
  - `long totalCostMs` — 总耗时（ms）

### 2.3 `RetrievalResult` (Model)

- [x] 创建文件 `business/rag/src/main/java/com/reubenagent/rag/model/RetrievalResult.java`
- [x] 注解：`@Data @Builder @NoArgsConstructor @AllArgsConstructor`
- [x] 字段：
  - `Long chunkId` — chunk ID
  - `String chunkText` — chunk 文本
  - `Double score` — 融合后分数
  - `String sectionPath` — 章节路径
  - `Long documentId` — 来源文档 ID
  - `Long parentBlockId` — 父块 ID
  - `String source` — 来源标记：`"vector"` / `"keyword"` / `"hybrid"`

### 2.4 `RagErrorCode` (Enum)

- [x] 创建文件 `business/rag/src/main/java/com/reubenagent/rag/enums/RagErrorCode.java`
- [x] `implements BaseEnum`
- [x] 值：
  - `RETRIEVE_FAILED(10001, "检索失败")`
  - `EMBEDDING_FAILED(10002, "向量化失败")`
  - `CHANNEL_TIMEOUT(10003, "检索通道超时")`
  - `INVALID_QUERY(10004, "查询参数无效")`

---

## Phase 3: 向量检索通道

> 依赖：Phase 1, Phase 2 | 工作量：~3h

### 3.1 `RagPgVectorConfiguration`

- [x] 创建文件 `business/rag/src/main/java/com/reubenagent/rag/config/RagPgVectorConfiguration.java`
- [x] 注解：`@Configuration`
- [x] 创建 `@Bean @Qualifier("ragPgVectorJdbcTemplate") JdbcTemplate` — HikariDataSource 连接 PGVector，**只读连接**（`readOnly = true`）
- [x] 连接参数从 `RagProperties.Pgvector` 读取
- [x] Pool name 设为 `RagPgVectorPool`，与 document 模块的 `DocumentPgVectorPool` 隔离

### 3.2 `VectorRetrievalChannel`

- [x] 创建文件 `business/rag/src/main/java/com/reubenagent/rag/service/VectorRetrievalChannel.java`
- [x] 注解：`@Slf4j @Component @AllArgsConstructor`
- [x] 注入：`@Qualifier("ragPgVectorJdbcTemplate") JdbcTemplate`、`ObjectProvider<EmbeddingModel>`、`RagProperties`
- [x] 核心方法：`List<RetrievalResult> retrieve(String query, int topK, Map<String, String> filters)`
  1. 校验 query 非空
  2. 获取 `EmbeddingModel`（`requireEmbeddingModel()` 防御性检查）
  3. `embeddingModel.embed(query)` → `float[]`
  4. 构建 SQL：`SELECT ... FROM <table> ORDER BY embedding <=> CAST(? AS vector) LIMIT ?`（余弦距离）
  5. 支持 `metadata_json` 过滤：`metadata_json->>'documentId' = ?`
  6. 遍历 ResultSet 构建 `RetrievalResult`（source = `"vector"`）
  7. catch 异常 → `log.warn` + 返回空列表（不抛异常，允许降级到单通道）
- [x] 防御性：query 为空 → 空列表；EmbeddingModel 不可用 → log.warn + 空列表；SQL 异常 → log.error + 空列表

### 3.3 集成测试

- [x] 创建 `VectorRetrievalChannelIntegrationTest` — 5 个测试全部通过
- [x] 基本向量检索 — 语义相似 chunk 排在前面 ✅
- [x] 过滤检索 — metadata_json documentId 过滤 ✅
- [x] TopK 截断 — 返回数量不超过 topK ✅
- [x] 防御性 — query 为空返回空列表 ✅
- [x] 防御性 — 无匹配数据不抛异常 ✅

---

## Phase 4: 关键词检索通道

> 依赖：Phase 1, Phase 2 | 工作量：~2h

### 4.1 `RagElasticsearchConfiguration`

- [x] 创建文件 `business/rag/src/main/java/com/reubenagent/rag/config/RagElasticsearchConfiguration.java`
- [x] 注解：`@Configuration @ConditionalOnBean(ElasticsearchClient.class)`
- [x] 如果 ES Client 已存在（document 模块注册），直接复用；否则注册空 Bean 占位

### 4.2 `KeywordRetrievalChannel`

- [x] 创建文件 `business/rag/src/main/java/com/reubenagent/rag/service/KeywordRetrievalChannel.java`
- [x] 注解：`@Slf4j @Component @AllArgsConstructor`
- [x] 注入：`ObjectProvider<ElasticsearchClient>`（允许 ES 不可用）、`RagProperties`
- [x] 核心方法：`List<RetrievalResult> retrieve(String query, int topK, Map<String, String> filters)`
  1. 校验 query 非空，ES Client 可用
  2. 构建 `match` 查询：字段 `chunkText`，standard analyzer
  3. 可选 filter：`term` 精确匹配（如 `documentId`）
  4. 解析 `SearchResponse<Map>` → 提取 `chunkId`, `chunkText`, `_score`, `sectionPath`, `documentId`, `parentBlockId`
  5. 构建 `RetrievalResult`（source = `"keyword"`）
  6. catch 异常 → `log.warn` + 空列表
- [x] 防御性：ES Client 不可用 → log.debug + 空列表（不炸通道）

### 4.3 新增 `MetadataKeys` 常量类

- [x] 创建文件 `business/rag/src/main/java/com/reubenagent/rag/constant/MetadataKeys.java`
- [x] 统一管理 ES 索引字段名：`CHUNK_ID`, `CHUNK_TEXT`, `DOCUMENT_ID`, `PARENT_BLOCK_ID`, `SECTION_PATH` 等

### 4.4 集成测试

- [x] 创建 `KeywordRetrievalChannelIntegrationTest` — 6 个测试全部通过 ✅
- [x] 基本关键词检索 — BM25 匹配 ✅
- [x] 过滤检索 — documentId term 精确过滤 ✅
- [x] TopK 截断 — 返回数量不超过 topK ✅
- [x] 防御性 — query 为空/null/空白 返回空列表 ✅
- [x] 防御性 — 无匹配数据返回空列表 ✅
- [x] 防御性 — source 字段缺失不抛异常 ✅

### 4.5 与 super-agent 的关键差异

- super-agent 的 `ElasticsearchKeywordService` 用 `refresh(Refresh.WaitFor)` 等待索引可见——**检索端不需要 refresh**，那是写入端的职责
- super-agent 的 ES 结果元数据用字符串 key 散落——统一用 `MetadataKeys` 常量

---

## Phase 5: RRF 融合 + 检索引擎

> 依赖：Phase 3, Phase 4 | 工作量：~3h

### 5.1 `RrfFusionService`

- [x] 创建文件 `business/rag/src/main/java/com/reubenagent/rag/service/RrfFusionService.java`
- [x] 注解：`@Component`（纯函数，无状态，不需要 `@Slf4j`）
- [x] 核心方法：`List<RetrievalResult> fuse(List<RetrievalResult> vectorResults, List<RetrievalResult> keywordResults, int k, int finalTopK)`
  1. `LinkedHashMap<Long, RetrievalResult>` 按 chunkId 去重合并
  2. 公式：`RRF_score(d) = Σ(c∈channels) 1/(K + rank_c(d))`，K=60
  3. 同一 chunk 在两边都命中 → 分数累加，source 标记为 `"hybrid"`
  4. 按 `score` 降序排序 → 截断到 `finalTopK`
  5. O(n) 单次遍历，不用 HashMap 逐条 merge

### 5.2 `RagRetrievalService`

- [x] 创建文件 `business/rag/src/main/java/com/reubenagent/rag/service/IRagRetrievalService.java`
- [x] 方法签名：`RagRetrieveResponse retrieve(RagRetrieveRequest request)`

### 5.3 `RagRetrievalServiceImpl`

- [x] 创建文件 `business/rag/src/main/java/com/reubenagent/rag/service/impl/RagRetrievalServiceImpl.java`
- [x] 注解：`@Slf4j @Service @AllArgsConstructor`
- [x] 注入：`VectorRetrievalChannel`、`KeywordRetrievalChannel`、`RrfFusionService`、`RagProperties`
- [x] 核心方法：`retrieve(RagRetrieveRequest request)`
  1. `long start = System.currentTimeMillis()`
  2. 校验 query 非空 → 抛 `ValidationException`
  3. 从 `RagProperties.Retrieval` 取 `vectorTopK`, `keywordTopK`, `finalTopK`, `rrfK`, `channelTimeoutMs`
  4. 并行调用两个通道：
     ```java
     CompletableFuture<List<RetrievalResult>> vectorFuture =
         CompletableFuture.supplyAsync(() -> vectorChannel.retrieve(...))
             .orTimeout(channelTimeoutMs, TimeUnit.MILLISECONDS)
             .exceptionally(ex -> { log.warn("向量通道异常", ex); return List.of(); });
     // 关键词通道同理
     ```
  5. `CompletableFuture.allOf(vectorFuture, keywordFuture).join()` 等两个都完成（或超时）
  6. `rrfFusionService.fuse(vectorResults, keywordResults, rrfK, finalTopK)`
  7. 计算 `totalCostMs` → 构建 `RagRetrieveResponse`
  8. catch 全局异常 → 抛 `BusinessException(RagErrorCode.RETRIEVE_FAILED)`

### 5.4 与 super-agent 的关键差异

- super-agent 的 `RagRetrievalEngine` 还有 sub-question 拆分、evidence gate、parent block elevation、rerank —— **v1 全部不做**，等核心链路跑通后迭代
- super-agent 用自定义 thread pool（`ChatRagExecutorConfiguration`）—— v1 用 `CompletableFuture` 默认 ForkJoinPool，够用
- super-agent 的超时处理吞异常且不返回部分结果——优化为 `exceptionally` 降级，一个通道炸了另一个通道的结果仍可用

---

## Phase 6: Controller

> 依赖：Phase 2, Phase 5 | 工作量：~0.5h

### 6.1 `RagController`

- [x] 创建文件 `business/rag/src/main/java/com/reubenagent/rag/controller/RagController.java`
- [x] 注解：`@Slf4j @RestController @RequestMapping("/api/rag") @AllArgsConstructor @Tag(name = "RAG检索")`
- [x] 注入：`IRagRetrievalService`
- [x] 端点：
  ```java
  @PostMapping("/retrieve")
  @Operation(summary = "混合检索召回")
  public ApiResponse<RagRetrieveResponse> retrieve(@RequestBody @Valid RagRetrieveRequest request) {
      return ApiResponse.ok(ragRetrievalService.retrieve(request));
  }
  ```
- [x] **不手动 catch**——参数校验失败由 `GlobalExceptionHandler` 统一处理
- [x] query 为空时，Controller 层不做判断，由 Service 层抛 `ValidationException`

### 6.2 参数校验

- [x] `RagRetrieveRequest.query` 加 `@NotBlank` 注解
- [x] 确认 `spring-boot-starter-validation` 已在依赖中（由 spring-boot-starter-web 传递引入）

---

## Phase 7: Evidence Gates（证据门控）

> 依赖：Phase 5 | 工作量：~1h

在检索引擎中增加两道门槛，过滤弱相关噪声：

### 7.1 `RagProperties.Retrieval` 扩展

- [x] 新增字段：
  - `double minVectorSimilarity = 0.45` — 向量通道最低余弦相似度
  - `double keywordRelativeScoreFloor = 0.35` — 关键词通道相对分数下限（相对于该通道最高分的比例）
- [x] `application.yml` 和 `application-docker.yml` 测试配置同步

### 7.2 修改 `RagRetrievalServiceImpl`

- [x] 向量通道结果过滤：`result.score < minVectorSimilarity` → 丢弃
- [x] 关键词通道结果过滤：`result.score < maxScore * keywordRelativeScoreFloor` → 丢弃
- [x] 过滤后的结果才进入 RRF 融合
- [x] gate 前后的数量记入 debug 日志

### 7.3 与 super-agent 的差异

- [x] super-agent 的 gate 逻辑散落在 `RagRetrievalEngine` 的 300 行方法里
- [x] reuben-agent 提取为 `applyEvidenceGates(vectorResults, keywordResults)` 私有方法
- [x] 测试：`RagEvidenceGateIntegrationTest` 5 个 Docker 集成测试全部通过

---

## Phase 8: Query Rewrite（查询改写）

> 依赖：Phase 5 | 工作量：~2h

用 LLM 改写用户原始问题，解决指代消解、口语化、表达偏差，提升召回命中率。

### 8.1 `QueryRewriteService`

- [x] 创建文件 `business/rag/src/main/java/com/reubenagent/rag/service/QueryRewriteService.java`
- [x] 注解：`@Slf4j @Component @AllArgsConstructor`
- [x] 注入：`ObjectProvider<ChatModel>`、`RagProperties`
- [x] 核心方法：`String rewrite(String originalQuery)`
  1. 规则前置：短 query（<6 字）或明显口语化 → 不调 LLM，直接返回原 query
  2. LLM 改写：通过 prompt 模板 `rag-query-rewrite.st` 让 LLM 输出改写后的检索友好 query
  3. LLM 失败 → log.warn + 返回原 query（降级，不阻塞检索）
- [x] Prompt 模板：`src/main/resources/prompt/rag-query-rewrite.st`，内容简洁——只做改写，不拆子问题

### 8.2 修改 `RagRetrievalServiceImpl`

- [x] 在检索前插入：`String rewrittenQuery = rewriteService.rewrite(request.getQuery())`
- [x] 用 `rewrittenQuery` 替代原 query 传给两个通道
- [x] `RagRetrieveResponse` 新增字段：`String rewrittenQuery` — 返回改写结果给调用方（可观测）

### 8.3 与 super-agent 的差异

- super-agent 的 `ChatQueryRewriteService` 还做子问题拆解、多轮历史上下文拼接——v1 只做单 query 改写
- super-agent 的 skip 逻辑用 18 字符阈值——简化为 6 字 + 口语化检测

---

## Phase 9: Parent Block Elevation（父块提升）

> 依赖：Phase 5 | 工作量：~2h

RRF 融合后，将小 chunk 替换为所属的 parent block 文本，返回更完整的上下文。

### 9.1 `ParentBlockElevationService`

- [x] 创建文件 `business/rag/src/main/java/com/reubenagent/rag/service/ParentBlockElevationService.java`
- [x] 注解：`@Slf4j @Component @AllArgsConstructor`
- [x] 注入：`@Qualifier("ragMySqlJdbcTemplate") JdbcTemplate`（直连 MySQL JDBC，不引入 MyBatis-Plus）
- [x] 核心方法：`List<RetrievalResult> elevate(List<RetrievalResult> fusedResults)`
  1. 收集所有 `parentBlockId`
  2. 用 parentBlockId 查 PGVector 表：`SELECT DISTINCT parent_block_id, chunk_text FROM <table> WHERE parent_block_id IN (?)`（取 parent block 的 chunk_text，通过 `metadata_json->>'sourceType' = '1'` 或 chunk_no 最小的那个）
  
  等等——PGVector 的 `metadata_json` 里有 `parentBlockId`，但 **parent block 的完整文本不在 PGVector 里，在 MySQL 的 `reuben_agent_document_parent_block.parent_text`** 中。

  所以这里需要读 MySQL：
  1. 从 fused results 提取 `parentBlockId` 列表
  2. 通过 MyBatis-Plus `IDocumentParentBlockMapper.selectBatchIds(parentBlockIds)` 查 MySQL
  3. 用 `parentBlock.parentText` 替换 `result.chunkText`
  4. 标记 `result.source = result.source + "+parent"`
  5. 去重：同一个 parent block 只保留一条

- [x] 防御性：parentBlockId 为 null → 跳过该 result；MySQL 查不到/异常 → 保留原 chunk（降级）

### 9.2 修改 `RagRetrievalServiceImpl`

- [x] RRF 融合后：`List<RetrievalResult> elevated = elevationService.elevate(fusedResults)`
- [x] 截断 topK 在 elevation **之后**进行（elevate 后数量可能减少）
- [x] 注入 `ParentBlockElevationService`

### 9.3 与 super-agent 的差异

- [x] super-agent 通过 `DocumentKnowledgeService.elevateToParentBlocks()` 实现，依赖 entity 层
- [x] reuben-agent 使用 JDBC 直连 MySQL（`JdbcTemplate`），更轻量，不引入 MyBatis-Plus 依赖
- [x] 创建 `RagMySqlConfiguration` 独立连接池（`RagMySqlPool`），与 document 模块隔离
- [x] 测试：`ParentBlockElevationServiceIntegrationTest` 5 个 Docker 集成测试

**⚠️ 模块隔离**：RAG 模块通过自己的 `RagMySqlConfiguration`（独立 HikariCP 连接池）访问同一 MySQL 库，与 document 模块无编译依赖。

---

## Phase 10: Rerank（重排序）

> 依赖：Phase 9 | 工作量：~2h

用 cross-encoder 模型对 elevation 后的结果精排，比向量余弦距离精准得多。

### 10.1 `RagProperties.Retrieval` 扩展

- [ ] 新增内部类 `Rerank`：
  - `boolean enabled = false` — 默认关闭（需要额外 API）
  - `String url = "https://api.siliconflow.cn/v1/rerank"`
  - `String model = "BAAI/bge-reranker-v2-m3"`
  - `int topN = 5`
  - `int connectTimeoutMs = 3000`
  - `int readTimeoutMs = 6000`

### 10.2 `RerankService`

- [ ] 创建文件 `business/rag/src/main/java/com/reubenagent/rag/service/RerankService.java`
- [ ] 注解：`@Slf4j @Component @AllArgsConstructor`
- [ ] 注入：`RagProperties`
- [ ] 核心方法：`List<RetrievalResult> rerank(String query, List<RetrievalResult> candidates)`
  1. 如果 `enabled = false` 或 candidates 为空 → 直接返回 candidates
  2. 构建 HTTP POST：`{model, query, documents: [candidate.chunkText], top_n}`
  3. 解析返回的 `relevance_score` → 更新 `result.score`，记录 `result.metadata["rerankScore"]`
  4. 按新分数降序排列
  5. 异常 → log.warn + 返回原顺序（降级，不阻塞）

### 10.3 修改 `RagRetrievalServiceImpl`

- [ ] Parent Block Elevation 之后：`List<RetrievalResult> reranked = rerankService.rerank(originalQuery, elevated)`
- [ ] 最终截断到 `finalTopK`

### 10.4 与 super-agent 的差异

- super-agent 用 `RestClient` + `DocumentPostProcessor` 接口 —— reuben-agent 直接用 `RestTemplate`，更简单
- super-agent 的 rerank 结果附加 5 个 metadata 字段 —— 精简为 `rerankScore` 一个

---

## Phase 11: 补齐后集成验证

> 依赖：Phase 7-10 | 工作量：~2h

### 7.1 错误码注册

- [x] 确认 `GlobalExceptionHandler` 能正确处理 `ValidationException` 和 `BusinessException(RagErrorCode)` — Controller 集成测试通过 HTTP 验证 ✅

### 7.2 配置验证

- [x] 启动应用，确认 `RagProperties` 绑定成功（`RagControllerIntegrationTest` 启动验证 ✅）
- [x] 确认 `ragPgVectorJdbcTemplate` bean 创建成功（`VectorRetrievalChannelIntegrationTest` 连接验证 ✅）
- [x] 确认 `ElasticsearchClient` 复用成功（`KeywordRetrievalChannelIntegrationTest` ping 验证 ✅）

### 7.3 功能验证

- [x] 先往 PGVector/ES 写入测试数据（测试中自动插入）
- [x] HTTP `POST /api/rag/retrieve` — `RagControllerIntegrationTest` 7 个用例覆盖 ✅
- [x] 验证返回的 `results` 包含 `chunkText`, `score`, `source`, `sectionPath` ✅
- [x] 验证 `source` 字段：纯向量命中 → `"vector"`，纯关键词命中 → `"keyword"`，两边都命中 → `"hybrid"` ✅

### 7.4 降级验证

- [x] 通道异常降级由 `RagRetrievalServiceImpl.exceptionally` 处理，`RagRetrievalServiceImplIntegrationTest` 覆盖 ✅
- [x] 两个通道都无结果 → 返回空列表，不抛 500 ✅

### 7.5 集成测试

- [x] `@SpringBootTest(webEnvironment = RANDOM_PORT)` + Docker PGVector/ES/Ollama
- [x] 验证 RRF 融合分数计算正确（`RrfFusionServiceTest` 10 个用例） ✅
- [x] 验证 HTTP 校验错误处理（`GlobalExceptionHandler` 统一返回 ApiResponse） ✅

---

## 工作量汇总

| Phase | 内容 | 预估工时 | 状态 |
|-------|------|---------|------|
| 1 | RagProperties 配置 | 0.5h | ✅ 已完成 |
| 2 | DTO / VO / Enum | 1h | ✅ 已完成 |
| 3 | 向量检索通道 | 3h | ✅ 已完成 |
| 4 | 关键词检索通道 | 2h | ✅ 已完成 |
| 5 | RRF 融合 + 检索引擎 | 3h | ✅ 已完成 |
| 6 | Controller | 0.5h | ✅ 已完成 |
| 7 | **Evidence Gates** | **1h** | ✅ 已完成 |
| 8 | **Query Rewrite** | **2h** | ✅ 已完成 |
| 9 | **Parent Block Elevation** | **2h** | ✅ 已完成 |
| 10 | **Rerank** | **2h** | ❌ 待补齐 |
| 11 | 补齐后集成验证 | 2h | ❌ |
| | | | |
| **已完成** | | **14h** | |
| **待补齐** | | **5h** | |
| **合计** | | **19h** | |

Phase 7-10 相对独立，可并行推进。

---

## 风险点

1. **PGVector 向量维度不匹配**：配置写 1024，但实际 embedding 模型返回其他维度 → SQL cast 报错。**对策**：启动时日志打印 embedding 实际维度 vs 配置维度
2. **ES Client bean 冲突**：document 模块已注册 `ElasticsearchClient`，RAG 模块用 `ObjectProvider` 获取已有 bean，不重复创建
3. **PGVector 连接池冲突**：document 和 RAG 各连同一 PGVector 库 → 各用自己的连接池名（`DocumentPgVectorPool` vs `RagPgVectorPool`），互不干扰
4. **EmbeddingModel 可用性**：依赖 Spring AI 的 `EmbeddingModel` bean，本地需配置 OpenAI-compatible embedding API（DeepSeek / SiliconFlow 等）
5. **模块隔离与 Bean 共享**：CLAUDE.md 禁止 business 模块间 Maven 依赖，但 Spring 扫描 `com.reubenagent` 会使所有模块的 Bean 在同一 ApplicationContext。RAG 模块通过 `ObjectProvider` 获取 ES/Embedding 等公共 Bean，通过 `@Qualifier` 获取自己的数据源——遵循"无编译依赖，运行时可协作"原则

---

## v1 不做（后续迭代）

| 功能 | 原因 |
|------|------|
| Multi-subquestion 拆分 | 先支持单一 query，拆子问题复杂度高 |
| HyDE（假设文档嵌入） | 加一次 LLM 调用换 embedding，成本收益待验证 |
| Intent Routing（知识路由） | 依赖知识分类体系（Scope/Topic），需要先补文档模块 |
| Neo4j 图检索 | 基础设施未部署 |

---

## 参考资料

### Phase 7-10 对齐目标

- super-agent `RagRetrievalEngine`（含 evidence gate + parent elevation + rerank 编排）: `super-agent/super-agent-business/super-agent-business-chat/src/main/java/org/javaup/ai/chatagent/rag/service/RagRetrievalEngine.java`
- super-agent `ChatQueryRewriteService`: `super-agent/super-agent-business/super-agent-business-chat/src/main/java/org/javaup/ai/chatagent/rag/service/ChatQueryRewriteService.java`
- super-agent `HttpDocumentRerankPostProcessor`: `super-agent/super-agent-business/super-agent-business-chat/src/main/java/org/javaup/ai/chatagent/rag/service/HttpDocumentRerankPostProcessor.java`
- super-agent `ChatRagProperties`: `super-agent/super-agent-business/super-agent-business-chat/src/main/java/org/javaup/ai/chatagent/rag/config/ChatRagProperties.java`

### Phase 1-6 已完成阶段

- super-agent `HybridSearchService`（RRF 参考）: `super-agent/ai-example/ai-example-rag/ai-example-spring-ai-rag-pg-es/src/main/java/org/javaup/ai/rag/pges/service/HybridSearchService.java`
- reuben-agent `DefaultDocumentVectorGateway`: `business/document/src/main/java/com/reubenagent/document/service/impl/DefaultDocumentVectorGateway.java`
- reuben-agent `EsDocumentKeywordSearchGateway`: `business/document/src/main/java/com/reubenagent/document/service/keyword/EsDocumentKeywordSearchGateway.java`
- reuben-agent `DocumentProperties`: `business/document/src/main/java/com/reubenagent/document/config/DocumentProperties.java`
- reuben-agent `handleIndexBuild` 计划: `docs/handleIndexBuild-implementation-plan.md`
- reuben-agent `DocumentParentBlock` 实体: `business/document/src/main/java/com/reubenagent/document/entity/DocumentParentBlock.java`
