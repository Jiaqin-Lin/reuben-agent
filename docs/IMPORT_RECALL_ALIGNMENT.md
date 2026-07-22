# 导入 & 召回质量对齐清单

对比对象：reuben-agent vs super-agent
仅关注质量（非可观测性），按修改复杂度排序。

---

## 1. ✅ LLM 切块失败降级策略 (M1)

**文件**：`business/document/.../impl/DocumentStrategyServiceImpl.java`

- [x] `llmChunkSingleSegment()` catch 块从 per-sentence 改为调 `applySemanticChunking()`
- [x] 方法签名加 `DocumentStrategyStep step` 参数以传递至 `applySemanticChunking`

---

## 2. ✅ 父块提升缺字符预算 (M2)

**文件**：`ParentBlockElevationService.java` / `RagProperties.java` / `application.yml`

- [x] `RagProperties.Retrieval` 加 `parentEvidenceMaxChars = 2000`
- [x] `ParentBlockElevationService` 注入 `RagProperties`，`buildElevatedResult()` 调 `trimText()`
- [x] 新增 `trimText(text, maxChars)` 方法，超长截断 + `…`
- [x] `application.yml` 加 `parent-evidence-max-chars: 2000`

---

## 3. ✅ 语义切块缺 maxChars 上限

**文件**：`DocumentStrategyServiceImpl.java` / `DocumentProperties.java`

- [x] `DocumentProperties.Strategy` 加 `semanticMaxChars = 2000`
- [x] `applySemanticChunking()` 检查 segment/buffer 是否超过 maxChars
- [x] 新增 `splitLongSegment()` 对超过 maxChars 的语义 segment 按句子边界强制切分
- [x] 修复 `segmentStart` 在 `continue` 时不更新的 bug

---

## 4. ✅ 管线执行模型：FALLBACK → 顺序精炼

**文件**：`DocumentStrategyServiceImpl.java`
**改动量**：大（重构管线执行引擎）

- [x] 重构 `executeStepPipeline()` / `executeSingleStep()` — 每步处理前一步的输出（而非原始 text）
- [x] 各切块策略签名从 `(String text, DocumentStrategyStep step)` 改为 `(List<ChunkCandidate> sourceList, ...)`
- [x] 去掉 FALLBACK role 的跳过逻辑，所有步骤顺序执行
- [x] 验证：LLM → RECURSIVE 管线中，RECURSIVE 正确拆分 LLM 产出的超长 chunk
- [x] 新增 `copyMetadataIfAbsent()` 辅助方法，供各拆分策略继承父 chunk 元数据
- [x] 移除 `optimizeChunks()` 死代码（原 OPTIMIZE role 调用路径）

---

## 5. ✅ QueryRewrite 子问题拆解 + 指代消解 (H3)

**文件**：`QueryRewriteService.java` / `rag-query-rewrite.st` / `ChatRagRetrievalAdapter.java` / `ChatRewriteResult.java` / `RagRetrievalServiceImpl.java`
**改动量**：大（涉及 prompt + 解析 + 管线改造）

- [x] 改写 prompt 模板：要求 LLM 输出 JSON `{rewrite, sub_questions: []}`
- [x] 新增 `RewriteResult` model（rag 模块，含 subQuestions 字段）
- [x] `QueryRewriteService.rewrite()` 返回结构化结果而非 String
- [x] 多问号/分号/编号项启发式检测（skip LLM 时的规则拆分）
- [x] `ChatRagRetrievalAdapter` 按 sub_questions 逐个调 `IRagRetrievalService.retrieve()`（已有）
- [x] RAG 管线编排支持子问题并行检索 + 结果合流

---

## 修改顺序建议

1. ✅ ~~1（M1 降级）~~ — 完成
2. ✅ ~~2（M2 父块预算）~~ — 完成
3. ✅ ~~3（语义 maxChars）~~ — 完成
4. ✅ ~~4（管线模型）~~ — 完成
5. ✅ ~~5（子问题拆解）~~ — 完成
