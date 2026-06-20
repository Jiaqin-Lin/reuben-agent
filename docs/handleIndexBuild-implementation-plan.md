# `handleIndexBuild` 实现计划

## 背景与约束

### 项目背景

reuben-agent 当前处于"以自己的风格跟敲同目录的super-agent"的阶段。文档模块已完成：

- 文件上传 → Tika 解析 → 结构树提取（4-Stage Pipeline）
- 文档画像生成
- 策略推荐（`recommendStrategy` 生成 `DocumentStrategyPlanDraft`）
- `handleParseStrategyRoute` 异步解析+策略推荐编排（已实现，尚未接入调用链）

**目标**：将 super-agent 的 `DocumentAsyncProcessServiceImpl.handleIndexBuild` 完整移植到 reuben-agent，补全从"策略确认"到"向量入库"的后半段管线。

### 代码风格约束（来自 CLAUDE.md + memory）

1. **异常体系**：业务层禁止 `throw new RuntimeException("字符串")`，必须抛 `DocumentException(DocumentManageCode.XXX, "detail", cause)`
2. **枚举规范**：所有 code/msg 枚举必须 `implements BaseEnum`，`getFromCode` 委托 `EnumUtils`
3. **Controller 响应**：统一返回 `ApiResponse<T>`，异常由 `GlobalExceptionHandler` 统一处理
4. **实体规范**：继承 `BaseTableData`，主键雪花 ID + `@TableId(type = IdType.INPUT)`，`@Builder` + `@AllArgsConstructor` + `@NoArgsConstructor`
5. **注入规范**：`@AllArgsConstructor` 构造器注入，禁止 `@Autowired` 字段注入
6. **Builder 优先**：连续 3 次以上 `setXxx()` 使用 `@Builder` 构建
7. **日志规范**：关键路径（状态变更、异常、外部调用）补日志，无用 `@Slf4j` 要移除
8. **注释风格**：简洁 `// 阶段 N：做什么` 即可，不在方法体内写教材
9. **命名规范**：Entity 无前缀，Mapper 用 `I` + 实体名 + `Mapper`，Service 用 `I` + 实体名 + `Service` / 实体名 + `ServiceImpl`

### 不严格 1:1 复刻原则

super-agent 只是参考版本，以下问题在移植时需要修正：

| # | super-agent 问题 | reuben-agent 优化方向 |
|---|-----------------|---------------------|
| 1 | `LambdaUpdateWrapper` 直接写在 handler catch 块 | 提取到 Mapper 方法 `markChunksVectorFailedByTaskId(taskId)` |
| 2 | `EMBEDDING_BATCH_SIZE_LIMIT = 10` 硬编码 | 放入 `DocumentProperties.Pgvector.batchSize` |
| 3 | `vectorize()` 直接修改入参 chunk 的 `vectorStatus`，靠调用方回写 DB | 返回 result VO，显式处理持久化边界 |
| 4 | catch 块把**所有** chunk 标记 `VECTOR_FAILED`，不区分已完成/未完成 | 区分"已向量化成功但后续失败的 chunk"和"向量化失败的 chunk" |
| 5 | `tokenCount` 字段存在但永远为 0（注释掉了） | 集成 TikToken 实际计算，或删除字段 |
| 6 | `buildParentChildEntities` 手动 setter ~10 个字段 | 使用 `@Builder` |
| 7 | 复杂 stream filter 内联 | 提取为命名方法 `isValidParentBlock()` |
| 8 | `ObjectProvider.getIfAvailable()` + null check 做可选网关 | 组合模式：默认 NOOP 实现 + ES 可选 bean |
| 9 | 类名含 `SuperAgent` 前缀 | reuben-agent 已统一去掉品牌前缀 |
| 10 | `DocumentStrategyExecuteStatusEnum` 用 `EXECUTE_SUCCESS`/`EXECUTE_FAILED` | reuben-agent 已改进为 `SUCCESS`/`FAILED`（更简洁） |

### reuben-agent 已优于 super-agent 的部分（无需改动）

- 枚举命名：`WAIT_BUILD` (reuben) vs `WAIT_TO_BUILD` (super)
- 实体命名：`Document` vs `SuperAgentDocument`
- 包结构更规整、模块边界更清晰
- 结构树解析管线已成熟（`DocumentStructureNodeExtractor` 4-Stage Pipeline），可直接作为结构化切分的输入

---

## Phase 总览

```
Phase 0 (枚举) ──→ Phase 1 (DDL) ──→ Phase 2 (实体/Mapper)
     │                                      │
     └──────────────────────────────────────┤
                                            │
Phase 3 (候选模型) ─────────────────────────┤
     │                                      │
Phase 4 (Properties 扩展) ──────────────────┤
     │                                      │
     └──── Phase 5 (向量网关) ──────────────┤
                                            │
Phase 6 (策略执行 buildParentBlocks) ────────┤
     │                                      │
     └──── Phase 7 (handleIndexBuild 编排) ──┤
                                            │
Phase 8 (关键字搜索网关) ──────────────────────┤
                                            │
Phase 9 (集成 & 验证) ──────────────────────┘
```

---

## Phase 0: 新增枚举

> 依赖：无 | 工作量：~1h

在 `business/document/src/main/java/com/reubenagent/document/enums/` 下新增 3 个枚举：

### 0.1 `DocumentChunkSourceTypeEnum`

- [x] 创建文件，`implements BaseEnum`
- [x] 值：`ORIGINAL(1, "原始分块")`, `ENRICHED(2, "增强分块")`

### 0.2 `DocumentVectorStatusEnum`

- [x] 创建文件，`implements BaseEnum`
- [x] 值：`WAIT_VECTOR(1, "待向量化")`, `VECTORIZING(2, "向量化中")`, `VECTOR_SUCCESS(3, "向量化成功")`, `VECTOR_FAILED(4, "向量化失败")`

### 0.3 `DocumentVectorStoreTypeEnum`

- [x] 创建文件，`implements BaseEnum`
- [x] 值：`MILVUS(1, "Milvus")`, `PG_VECTOR(2, "PgVector")`, `ELASTICSEARCH(3, "Elasticsearch")`

---

## Phase 1: 数据库 DDL

> 依赖：无 | 工作量：~0.5h

在 `sql/` 目录下新增 DDL（或在现有 `reuben_agent_mysql.sql` 中追加）。

### 1.1 `reuben_agent_document_parent_block` 表

- [x] 编写 DDL
- [x] 字段：`id BIGINT PRIMARY KEY`, `document_id`, `task_id`, `plan_id`, `parent_no INT`, `source_type INT`, `section_path VARCHAR(500)`, `structure_node_id BIGINT`, `structure_node_type INT`, `canonical_path VARCHAR(500)`, `item_index INT`, `parent_text MEDIUMTEXT`, `char_count INT`, `token_count INT`, `child_count INT`, `start_chunk_no INT`, `end_chunk_no INT`, + `BaseTableData` 字段 (`create_time`, `update_time`, `is_deleted`)
- [x] 索引：`idx_parent_document_id (document_id)`, `idx_parent_task_id (task_id)`

### 1.2 `reuben_agent_document_chunk` 表

- [x] 编写 DDL
- [x] 字段：`id BIGINT PRIMARY KEY`, `document_id`, `task_id`, `plan_id`, `parent_block_id`, `chunk_no INT`, `source_type INT`, `section_path VARCHAR(500)`, `structure_node_id BIGINT`, `structure_node_type INT`, `canonical_path VARCHAR(500)`, `item_index INT`, `chunk_text MEDIUMTEXT`, `char_count INT`, `token_count INT`, `vector_status INT DEFAULT 1`, `vector_store_type INT`, `vector_id VARCHAR(200)`, + `BaseTableData` 字段
- [x] 索引：`idx_chunk_document_id (document_id)`, `idx_chunk_task_id (task_id)`, `idx_chunk_parent_block_id (parent_block_id)`

### 1.3 `reuben_agent_document_embedding` 表（PGVector）

- [x] 编写 DDL（**注意：此表在 PostgreSQL pgvector extension 中，不在 MySQL**）
- [x] 字段：`id BIGINT PRIMARY KEY`, `document_id BIGINT NOT NULL`, `chunk_id BIGINT NOT NULL`, `chunk_text TEXT`, `embedding vector(1536)`, `metadata JSONB`, `create_time TIMESTAMP DEFAULT NOW()`
- [x] 索引：`idx_embedding_document_id (document_id)`, 向量索引（IVFFlat 或 HNSW）

---

## Phase 2: 实体与 Mapper

> 依赖：Phase 0, Phase 1 | 工作量：~2h

### 2.1 `DocumentParentBlock` 实体

- [x] 创建文件 `entity/DocumentParentBlock.java`
- [x] 继承 `BaseTableData`
- [x] 注解：`@Data @Builder @NoArgsConstructor @AllArgsConstructor @EqualsAndHashCode(callSuper = true) @TableName("reuben_agent_document_parent_block")`
- [x] 主键：`@TableId(type = IdType.INPUT) private Long id`
- [x] 所有字段按 DDL 一一映射，`parentText` 用 `@TableField("parent_text")`

### 2.2 `IDocumentParentBlockMapper`

- [x] 创建文件 `mapper/IDocumentParentBlockMapper.java`
- [x] 继承 `BaseMapper<DocumentParentBlock>`
- [x] 空接口即可（MyBatis-Plus 自动提供 CRUD）

### 2.3 `DocumentChunk` 实体

- [x] 创建文件 `entity/DocumentChunk.java`
- [x] 继承 `BaseTableData`
- [x] 注解同上，`@TableName("reuben_agent_document_chunk")`
- [x] 主键雪花 ID，所有字段按 DDL 映射

### 2.4 `IDocumentChunkMapper`

- [x] 创建文件 `mapper/IDocumentChunkMapper.java`
- [x] 继承 `BaseMapper<DocumentChunk>`
- [x] 新增方法：`int markVectorFailedByTaskId(@Param("taskId") Long taskId)` — 对应 XML SQL 或 `@Update` 注解，将指定 task 下 `vectorStatus=WAIT_VECTOR` 的 chunk 批量标记为 `VECTOR_FAILED`

---

## Phase 3: 候选模型（In-Memory）

> 依赖：Phase 0 | 工作量：~0.5h

### 3.1 `ChunkCandidate`

- [x] 创建文件 `model/ChunkCandidate.java`
- [x] `@Data @Builder @NoArgsConstructor @AllArgsConstructor`
- [x] 字段：`sectionPath`, `structureNodeId`, `structureNodeType`, `canonicalPath`, `itemIndex`, `text`, `sourceType`
- [x] 提供便捷构造器：`ChunkCandidate(sectionPath, text, sourceType)`

### 3.2 `ParentBlockCandidate`

- [x] 创建文件 `model/ParentBlockCandidate.java`
- [x] `@Data @Builder @NoArgsConstructor @AllArgsConstructor`
- [x] 字段：`sectionPath`, `structureNodeId`, `structureNodeType`, `canonicalPath`, `itemIndex`, `text`, `sourceType`, `childChunks`（`List<ChunkCandidate>`，默认 `new ArrayList<>()`）
- [x] 提供便捷构造器：`ParentBlockCandidate(sectionPath, text, sourceType, childChunks)`

---

## Phase 4: Properties 扩展

> 依赖：无 | 工作量：~0.5h

### 4.1 `DocumentProperties` 新增内部类

- [x] 新增 `Pgvector` 内部类（对应 `reuben.document.pgvector`）：
  - `String tableName = "public.reuben_agent_document_embedding"`
  - `int batchSize = 10`（解决 super-agent 硬编码问题 #2）
  - `String embeddingModel = "text-embedding-3-small"`（可覆盖）
- [x] 新增 `Embedding` 内部类（对应 `reuben.document.embedding`）：
  - `int dimension = 1536`
- [x] 扩展 `Strategy` 内部类：
  - `int recursiveOverlapChars = 200`（递归切分重叠字符数）
  - `double semanticSimilarityThreshold = 0.7`（语义切分相似度阈值）
  - `int llmMaxInputChars = 8000`（LLM 切分单次最大输入）

### 4.2 `application.yml` 补充默认值

- [x] 在 `application.yml` 中为上述配置提供默认值

---

## Phase 5: 向量网关

> 依赖：Phase 2, Phase 4 | 工作量：~4h

### 5.1 `IDocumentVectorGateway` 接口

- [x] 创建文件 `service/IDocumentVectorGateway.java`
- [x] 方法：
  - `DocumentVectorizationResult vectorize(List<DocumentChunk> chunks)` — 返回 result VO 而非修改入参（优化 #3）
  - `void deleteByDocumentId(Long documentId)`

### 5.2 `DocumentVectorizationResult` VO

- [x] 创建文件 `model/DocumentVectorizationResult.java`
- [x] 字段：`int totalCount`, `int successCount`, `int failedCount`, `List<Long> successChunkIds`, `List<Long> failedChunkIds`
- [x] 让调用方显式知道哪些 chunk 成功了、哪些失败了（优化 #4）

### 5.3 `DefaultDocumentVectorGateway` 实现

- [x] 创建文件 `service/impl/DefaultDocumentVectorGateway.java`
- [x] 注入：`JdbcTemplate`（pgvector 数据源）、`EmbeddingModel`（Spring AI）、`DocumentProperties`
- [x] `vectorize()` 流程：
  1. 过滤 `chunkText` 非空的 chunk
  2. 按 `batchSize` 分批
  3. 每批调用 `EmbeddingModel.embed()` 获取 `float[]`
  4. `JdbcTemplate.batchUpdate` 做 `ON CONFLICT (id) DO UPDATE` 幂等 UPSERT
  5. 返回 `DocumentVectorizationResult`（不修改入参 chunk）
- [x] `toVectorLiteral(float[])` — `float[]` → `[0.12,0.34,...]` PGVector 字面量
- [x] `resolveEmbeddingModelName()` — 从 `DocumentProperties.Pgvector.embeddingModel` 读取
- [x] `deleteByDocumentId()` — `DELETE FROM <table> WHERE document_id = ?`

### 5.4 PGVector 数据源配置

- [x] 确认 `application.yml` 中 PostgreSQL + pgvector 数据源已配置
- [x] 新增 `DocumentPgVectorConfiguration`（`@Configuration`），创建独立的 `JdbcTemplate` bean 指向 pgvector 数据源

---

## Phase 6: 策略执行引擎（`buildParentBlocks`）

> 依赖：Phase 3, Phase 4, 现有 `IDocumentStructureNodeService` | 工作量：**10-14h** ⚠️ 最重

这是整个移植中最复杂的部分。reuben-agent 已有策略推荐（`recommendStrategy`），现在需要实现策略**执行**。

### 6.1 `IDocumentStrategyService` 接口扩展

- [x] 新增方法签名：
  ```java
  List<ParentBlockCandidate> buildParentBlocks(
      Document document,
      DocumentStrategyPlan plan,
      List<DocumentStrategyStep> steps,
      String parsedText);
  ```

### 6.2 `DocumentStrategyServiceImpl.buildParentBlocks()` 编排器

- [x] 按 `pipelineType` 将 steps 分为 parent steps 和 child steps
- [x] 校验两列表均非空（为空中断抛 `DocumentException`）
- [x] 加载文档结构节点 `structureNodeService.listDocumentNodes(documentId)`
- [x] 调用 `buildParentSeedList(parsedText, parentSteps, structureNodes)` 生成 parent seed 列表
- [x] 对每个 parent seed 去重 + 调用 `buildChildSeedList(parentSeed, childSteps, structureNodes)` 生成 child chunk 列表
- [x] 组装 `ParentBlockCandidate`（parent seed + 其 children）
- [x] 去重：`cleanupParentBlockList()`（基于 sectionPath + text 去重）
- [x] 返回最终列表

### 6.3 `buildParentSeedList` / `buildChildSeedList`

- [x] 实现管线执行引擎（遍历 steps，每个 step 根据 strategyType 调用对应策略）
- [x] step 按 `DocumentStrategyRoleEnum` 处理：PRIMARY → 主切分，OPTIMIZE → 合并/拆分优化，FALLBACK → 仅在前序 step 无产出时生效，ENHANCE → 追加补充
- [x] step 间去重（`cleanupChunkList`）

### 6.4 `applyStructureChunking(String text, DocumentStrategyStep step, List<DocumentStructureNode> nodes)`

- [x] **优势**：reuben-agent 已有成熟的 `DocumentStructureNodeExtractor` 4-Stage Pipeline，直接复用 `DocumentStructureNode` 实体
- [x] 按 heading 边界提取 parent chunks（每个 CHAPTER 节点为一个 parent）
- [x] 每个 parent 内按段落/逻辑行拆分为 child chunks
- [x] 过滤空块、噪声行（`isNoiseLine`）
- [x] `sectionPath` 从节点已有字段获取，支持 `resolveSectionPath` 向上回溯

### 6.5 `applyRecursiveChunking(String text, DocumentStrategyStep step)`

- [x] 按 `DocumentProperties.Strategy.recursiveMaxChars` 阈值切分
- [x] 先尝试按段落（`\n\n`）切分
- [x] 超阈值的段落按句子（`splitSentences`）切分
- [x] 仍超阈值的按固定窗口（charCount）切分 + overlap（`recursiveOverlapChars`）
- [x] 生成 `ChunkCandidate`

### 6.6 `applySemanticChunking(String text, DocumentStrategyStep step)`

- [x] 以句子为最小单元计算相邻句间 Jaccard 相似度
- [x] 相似度 < `semanticSimilarityThreshold` → 此处为语义边界，切分
- [x] 跨边界合并过小的 segment（minChars=50）
- [x] 纯 Jaccard 词袋（tokenize 1-2gram），无 Embedding 依赖

### 6.7 `applyLlmChunking(String text, DocumentStrategyStep step)`

- [x] 输入切分为不超过 `llmMaxInputChars` 的段落组
- [x] Prompt：识别语义断点，返回断点索引列表（`prompt/document-llm-chunking.st`）
- [x] 依赖：注入 `ChatModel`（Spring AI）
- [x] 用 `PromptTemplateService` 管理 prompt 模板
- [x] LLM 调用失败时降级为按句子切分

### 6.8 辅助方法

- [x] `cleanupChunkList(List<ChunkCandidate>)` — 基于 sectionPath + text 哈希去重
- [x] `cleanupParentBlockList(List<ParentBlockCandidate>)` — 合并同 sectionPath 的 parent，子 chunk 自动去重
- [x] `resolveSectionPath(structureNode, nodeMap)` — 从树节点向上回溯构造路径
- [x] `resolveCanonicalPath(path)` — 路径片段归一化（去 #、去多余空格、小写）
- [x] `splitSentences(text)` — 句子边界拆分
- [x] `jaccardSimilarity(a, b)` — 词袋 Jaccard 相似度
- [x] `isNoiseLine(line)` — 噪声行过滤（页码、分隔线、纯标点）
- [x] `collectSubtreeText(node, childrenMap)` — 递归收集子树文本

---

## Phase 7: `handleIndexBuild` 编排

> 依赖：Phase 5, Phase 6 | 工作量：~4h

### 7.1 `IDocumentAsyncProcessService` 接口扩展

- [x] 新增方法签名：
  ```java
  void handleIndexBuild(Long documentId, Long taskId, Long planId);
  ```

### 7.2 `DocumentAsyncProcessServiceImpl.handleIndexBuild()` 实现

- [x] 数据校验：select document / task / plan，任一为 null → log.warn + return
- [x] 阶段 1 `CHUNK_EXECUTE`：更新 document/task/plan 状态 → `storageService.downloadText()` → `strategyService.buildParentBlocks()`
- [x] 阶段 2 `CHUNK_POST_PROCESS`：`isValidParentBlock()` 过滤 → `buildParentChildEntities()` → 批量 insert parentBlocks + chunks
- [x] 阶段 3 `VECTORIZE`：`vectorGateway.vectorize()` → 根据 `DocumentVectorizationResult` 更新各 chunk 状态 → `keywordSearchGateway.indexChunks()`
- [x] 阶段 4 `STORE_COMPLETE`：更新 plan=EXECUTED, document=BUILD_SUCCESS, task=SUCCESS
- [x] catch block：document=BUILD_FAILED, `chunkMapper.markVectorFailedByTaskId()`, plan steps=FAILED, failTask + taskLog

### 7.3 `buildParentChildEntities` 私有方法

- [x] 使用 `@Builder` 构建 `DocumentParentBlock` 和 `DocumentChunk` 实体（优化 #6）
- [x] 雪花 ID 分配（`uidGenerator.getUid()`）
- [x] `charCount` = `text.length()`
- [x] 集成 TikToken（`com.knuddels:jtokkit`）实际计算 `tokenCount`（优化 #5）——杜绝 super-agent 设假值 0 的问题
- [x] 全局 `chunkNo` 跨所有 parent 递增

### 7.4 `ParentChildEntityBundle` 内部 record

- [x] 定义于 `DocumentAsyncProcessServiceImpl` 文件底部（package-private）
- [x] `record ParentChildEntityBundle(List<DocumentParentBlock> parentBlocks, List<DocumentChunk> childChunks) {}`

### 7.5 `isValidParentBlock` 辅助方法

- [x] 提取 stream filter 为命名方法（优化 #7）
- [x] `private static boolean isValidParentBlock(ParentBlockCandidate candidate)`

---

## Phase 8: 关键字搜索网关

> 依赖：Phase 2 | 工作量：~3h

### 8.1 `IDocumentKeywordSearchGateway` 接口

- [ ] 创建文件 `service/keyword/IDocumentKeywordSearchGateway.java`
- [ ] 创建 `dto/DocumentRetrieveRequest.java` — 字段：`query`, `topK`, `filterFields`（`Map<String, String>`）、`scoreThreshold`
- [ ] 方法：
  - `void indexChunks(List<DocumentChunk> chunks)`
  - `List<org.springframework.ai.document.Document> search(DocumentRetrieveRequest request)`
  - `void deleteByDocumentId(Long documentId)`

### 8.2 `NoOpKeywordSearchGateway` 默认实现

- [ ] 创建文件 `service/keyword/NoOpKeywordSearchGateway.java`
- [ ] 所有方法空实现 + `log.debug("Keyword search disabled, skipping...")`
- [ ] 注解 `@Primary` 或 `@ConditionalOnMissingBean`
- [ ] 这是优化 #8 的核心——handler 不需要做 null check，始终注入 `IDocumentKeywordSearchGateway`

### 8.3 `EsDocumentKeywordSearchGateway` 完整实现

- [ ] 创建文件 `service/keyword/EsDocumentKeywordSearchGateway.java`
- [ ] 依赖：`ElasticsearchClient`（ES 8.x Java API Client）
- [ ] `indexChunks()` — 批量索引到 ES（`_bulk` API）
- [ ] `search()` — 全文检索
- [ ] `deleteByDocumentId()` — `DELETE_BY_QUERY` where `document_id = ?`
- [ ] 注解：`@ConditionalOnBean(ElasticsearchClient.class)` + `@Primary`（当 ES 可用时自动替换 NOOP）

---

## Phase 9: 集成 & 验证

> 依赖：Phase 7, Phase 8 | 工作量：~3h

### 9.1 错误码补充

- [ ] 在 `DocumentManageCode` 枚举中补充索引相关错误码：
  - `INDEX_BUILD_FAILED`
  - `VECTORIZE_FAILED`
  - `CHUNK_EXECUTION_FAILED`

### 9.2 策略确认端点

- [ ] 在 `DocumentController` 中新增 `POST /api/document/strategy/confirm` — 用户确认策略方案，将 plan 状态从 `WAIT_CONFIRM` → `CONFIRMED`
- [ ] 确认后触发 `handleIndexBuild`（同步调用或 Kafka 异步）

### 9.3 调用链打通

- [ ] `handleParseStrategyRoute` 在策略推荐完成后，将 plan 状态设为 `WAIT_CONFIRM`
- [ ] 用户确认 → plan = `CONFIRMED` → 触发 `handleIndexBuild`
- [ ] 或：对于 `planSource = SYSTEM_RECOMMEND` 的自动方案，跳过确认直接触发 `handleIndexBuild`

### 9.4 集成测试

- [ ] 使用 Testcontainers PostgreSQL + pgvector 扩展
- [ ] Mock `EmbeddingModel`（避免实际调用 embedding API）
- [ ] 覆盖：正常流程 4 阶段全成功、向量化部分失败、策略执行异常
- [ ] 验证 chunk/parentBlock 持久化、状态转换正确性

### 9.5 文档更新

- [ ] 更新 `CLAUDE.md` 的 Document Module Domain Signal Pipeline 图，补充 Stage 5-8
- [ ] 补充 `application.yml` 注释说明新增配置项

---

## 工作量汇总

| Phase | 内容 | 预估工时 | 优先级 |
|-------|------|---------|--------|
| 0 | 新增枚举 (3个) | 1h | P0 |
| 1 | DDL (3张表) | 0.5h | P0 |
| 2 | 实体 + Mapper (2组) | 2h | P0 |
| 3 | 候选模型 (2个 POJO) | 0.5h | P0 |
| 4 | Properties 扩展 | 0.5h | P1 |
| 5 | 向量网关 | 4h | P1 |
| 6 | **策略执行引擎** | **10-14h** | **P1** ⚠️ |
| 7 | handleIndexBuild 编排 | 4h | P1 |
| 8 | 关键字搜索网关 | 3h | P1 |
| 9 | 集成 & 验证 | 3h | P1 |
| **合计** | | **28.5-32.5h** | |

Phase 0-2 可并行推进，Phase 6 是唯一阻塞项——其余 Phase 可在 Phase 6 开发过程中穿插完成。

---

## 风险点

1. **Phase 6 策略执行**是最不确定的部分——super-agent 的四种切分策略各有多少细节依赖、多少隐式假设，需要在移植过程中逐行审查。四种策略（structure / recursive / semantic / LLM）**必须全部实现**，不可阉割。reuben-agent 的结构树管线已就绪，structure 策略可直接复用；其余三个需从 super-agent 逐方法移植。
2. **PGVector 数据源**：当前 `application.yml` 已配置 PostgreSQL，但需确认 pgvector extension 是否在 docker-compose 的 PostgreSQL 容器中启用。
3. **EmbeddingModel 可用性**：依赖 Spring AI 的 `EmbeddingModel` bean，需确认 DeepSeek（或切换为 OpenAI）的 embedding API 是否已在 `application.yml` 配置。
4. **tokenCount 计算**：如集成 TikToken，需额外引入 `com.knuddels:jtokkit` 依赖或手动实现 cl100k_base 编码器。

---

## 参考资料

- super-agent `DocumentAsyncProcessServiceImpl`: `super-agent/super-agent-business/super-agent-business-chat/src/main/java/org/javaup/ai/manage/service/impl/DocumentAsyncProcessServiceImpl.java`
- super-agent `DocumentStrategyServiceImpl`: `super-agent/super-agent-business/super-agent-business-chat/src/main/java/org/javaup/ai/manage/service/impl/DocumentStrategyServiceImpl.java`
- super-agent `DefaultDocumentVectorGateway`: `super-agent/super-agent-business/super-agent-business-chat/src/main/java/org/javaup/ai/manage/service/impl/DefaultDocumentVectorGateway.java`
- reuben-agent 现有 `DocumentAsyncProcessServiceImpl`: `business/document/src/main/java/com/reubenagent/document/service/impl/DocumentAsyncProcessServiceImpl.java`
