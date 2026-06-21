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
Phase 1 (Properties) ──→ Phase 2 (DTO/VO/Enum) ──→ Phase 3 (向量检索)
                                                         │
                                                    Phase 4 (关键词检索)
                                                         │
                                                    Phase 5 (RRF + 引擎)
                                                         │
                                                    Phase 6 (Controller)
                                                         │
                                                    Phase 7 (集成验证)
```

所有 Phase 顺序执行（后一 Phase 依赖前一 Phase 的产出）。

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

- [ ] 创建文件 `business/rag/src/main/java/com/reubenagent/rag/controller/RagController.java`
- [ ] 注解：`@Slf4j @RestController @RequestMapping("/api/rag") @AllArgsConstructor @Tag(name = "RAG检索")`
- [ ] 注入：`IRagRetrievalService`
- [ ] 端点：
  ```java
  @PostMapping("/retrieve")
  @Operation(summary = "混合检索召回")
  public ApiResponse<RagRetrieveResponse> retrieve(@RequestBody @Valid RagRetrieveRequest request) {
      return ApiResponse.ok(ragRetrievalService.retrieve(request));
  }
  ```
- [ ] **不手动 catch**——参数校验失败由 `GlobalExceptionHandler` 统一处理
- [ ] query 为空时，Controller 层不做判断，由 Service 层抛 `ValidationException`

### 6.2 参数校验

- [ ] `RagRetrieveRequest.query` 加 `@NotBlank` 注解
- [ ] 确认 `spring-boot-starter-validation` 已在依赖中

---

## Phase 7: 集成验证

> 依赖：Phase 6 | 工作量：~2h

### 7.1 错误码注册

- [ ] 确认 `GlobalExceptionHandler` 能正确处理 `ValidationException` 和 `BusinessException(RagErrorCode)`

### 7.2 配置验证

- [ ] 启动应用，确认 `RagProperties` 绑定成功（看 log 或 actuator）
- [ ] 确认 `ragPgVectorJdbcTemplate` bean 创建成功
- [ ] 确认 `ElasticsearchClient` 复用成功

### 7.3 功能验证

- [ ] 先往 PGVector/ES 写入测试数据（用 document 模块的已有管线）
- [ ] `curl POST /api/rag/retrieve -d '{"query":"测试问题","topK":5}'`
- [ ] 验证返回的 `results` 包含 `chunkText`, `score`, `source`, `sectionPath`
- [ ] 验证 `source` 字段：纯向量命中 → `"vector"`，纯关键词命中 → `"keyword"`，两边都命中 → `"hybrid"`

### 7.4 降级验证

- [ ] 停掉 ES → 验证关键词通道降级为空列表，向量通道正常返回
- [ ] 停掉 PGVector → 验证向量通道降级为空列表，关键词通道正常返回
- [ ] 两个都停 → 验证返回空列表，不抛 500

### 7.5 集成测试（可选，后续补）

- [ ] `@SpringBootTest` + Testcontainers PostgreSQL/pgvector
- [ ] Mock `EmbeddingModel` 返回固定向量
- [ ] 验证 RRF 融合分数计算正确

---

## 工作量汇总

| Phase | 内容 | 预估工时 | 优先级 |
|-------|------|---------|--------|
| 1 | RagProperties 配置 | 0.5h | P0 |
| 2 | DTO / VO / Enum | 1h | P0 |
| 3 | 向量检索通道 | 3h | P0 |
| 4 | 关键词检索通道 | 2h | P0 |
| 5 | RRF 融合 + 检索引擎 | 3h | P0 |
| 6 | Controller | 0.5h | P0 |
| 7 | 集成验证 | 2h | P1 |
| **合计** | | **12h** | |

Phase 1-2 可并行（Properties 和 DTO 无依赖），Phase 3-4 可并行（两个通道独立），Phase 5 依赖 3+4，Phase 6 依赖 2+5。

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
| Query Rewrite（LLM 改写） | 先跑通基础检索，改写是锦上添花 |
| Rerank（cross-encoder 重排） | 需要额外的 rerank API，v1 RRF 融合已够用 |
| Parent Block Elevation | 需要在 RAG 模块访问 MySQL 的 `DocumentParentBlock` 表，跨模块了 |
| Evidence Gates（分数阈值过滤） | 先返回 top-K，阈值调优是后续的事 |
| Multi-subquestion 拆分 | 先支持单一 query |
| HyDE（假设文档嵌入） | 成本高，效果待验证 |

---

## 参考资料

- super-agent `RagRetrievalEngine`: `super-agent/super-agent-business/super-agent-business-chat/src/main/java/org/javaup/ai/chatagent/rag/service/RagRetrievalEngine.java`
- super-agent `HybridSearchService`: `super-agent/ai-example/ai-example-rag/ai-example-spring-ai-rag-pg-es/src/main/java/org/javaup/ai/rag/pges/service/HybridSearchService.java`
- super-agent `ChatRagProperties`: `super-agent/super-agent-business/super-agent-business-chat/src/main/java/org/javaup/ai/chatagent/rag/config/ChatRagProperties.java`
- reuben-agent `DefaultDocumentVectorGateway`: `business/document/src/main/java/com/reubenagent/document/service/impl/DefaultDocumentVectorGateway.java`
- reuben-agent `EsDocumentKeywordSearchGateway`: `business/document/src/main/java/com/reubenagent/document/service/keyword/EsDocumentKeywordSearchGateway.java`
- reuben-agent `DocumentProperties`: `business/document/src/main/java/com/reubenagent/document/config/DocumentProperties.java`
- reuben-agent `handleIndexBuild` 计划: `docs/handleIndexBuild-implementation-plan.md`
