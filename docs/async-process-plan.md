# DocumentAsyncProcessServiceImpl 补齐计划

## 第一阶段：补齐 handleParseStrategyRoute（本次）

目标：解析管道从上传到策略推荐完整跑通，task 正确结束。

### 1.1 修复现有缺陷

| 项 | 问题 | 改法 |
|----|------|------|
| 成功后不更新 document | 阶段 5 画像生成成功后，document.parseStatus 仍是 PARSING | 设置为 PARSE_SUCCESS，写入统计指标（charCount、tokenCount、structureLevel、contentQualityLevel、structureNodeCount、parsedTextPath） |
| 成功后不结束 task | task 永远是 RUNNING | 设置为 SUCCESS，写入 finishTime、costMillis |
| 成功后不写 COMPLETE log | 只有 START log，没有 COMPLETE | 写入 COMPLETE 事件 taskLog |
| 失败时只更新 task 不更新 document | catch 块只设了 task.status=FAILED | 同步设 document.parseStatus=PARSE_FAILED、parseErrorMsg |

### 1.2 新增策略推荐阶段

解析完成后，调用 LLM 分析文档特征，推荐最优分块方案，持久化 Plan + Steps。

**新增实体：**
- `DocumentStrategyPlan` — `reuben_agent_document_strategy_plan`
- `DocumentStrategyStep` — `reuben_agent_document_strategy_step`

**新增枚举：**
- `DocumentStrategyTypeEnum` — STRUCTURE / RECURSIVE / SEMANTIC / LLM
- `DocumentStrategyRoleEnum` — PRIMARY / OPTIMIZE / FALLBACK / ENHANCE
- `DocumentStrategyPipelineTypeEnum` — PARENT / CHILD
- `DocumentStrategySourceTypeEnum` — SYSTEM_RECOMMEND / USER_ADD / USER_KEEP
- `DocumentStrategyExecuteStatusEnum` — WAIT_EXECUTE / EXECUTING / SUCCESS / FAILED / SKIPPED
- `DocumentPlanStatusEnum` — WAIT_CONFIRM / CONFIRMED / EXECUTED / DISCARDED
- `DocumentPlanSourceEnum` — SYSTEM_RECOMMEND / USER_ADJUST

**新增 Mapper：**
- `IDocumentStrategyPlanMapper`
- `IDocumentStrategyStepMapper`

**新增 Model：**
- `DocumentStrategyPlanDraft` — LLM 返回的策略草稿（snapshot + reason + parentSteps + childSteps）
- `DocumentStrategyStepDraft` — 单个步骤（pipelineType + strategyType + strategyRole + sourceType + reason）

**新增 Service：**
- `IDocumentStrategyService` — `recommendStrategy(document, parseResult)` 一个方法
- `DocumentStrategyServiceImpl` — LLM 调用 + Plan/Step 持久化

**修改已有：**
- `DocumentAsyncProcessServiceImpl.handleParseStrategyRoute()` — 阶段 6 策略推荐 + 阶段 7 收尾
- `Document.java` — 补 `parseErrorMsg` 字段（如果有缺失）
- `sql/reuben_agent_mysql.sql` — 两张新表 DDL
- `DocumentTestSchema.java` — 测试 DDL

**测试：**
- `DocumentAsyncProcessServiceImplTest` — Mock 外部依赖（MinIO、LLM），验证状态流转正确
- `DocumentStrategyServiceImplTest` — Mock ChatModel，验证策略推荐 + 持久化
- 展示测试（可选）— 真实 LLM 跑一份文档看推荐结果

### 1.3 不做的事情

- ❌ 不做 Kafka 消费者/生产者（后续独立做）
- ❌ 不做 ES/Neo4j 导航同步（可选中间件，后续按需）
- ❌ 不做 strategy 的 Controller API（查询方案、确认方案 — 后续）
- ❌ 不做实际分块执行（第二阶段）

---

## 第二阶段：handleIndexBuild（后续）

目标：用户确认策略后，执行实际分块 → 向量化 → 索引存储。

- 分块引擎（STRUCTURE / RECURSIVE / SEMANTIC / LLM 四种策略）
- Parent-Child 双层分块
- 向量化 + 存储
- Kafka 串联整个流程
- Controller API（查询方案、确认方案、触发索引构建）

---

> 2026-06-20，基于 super-agent 对应实现分析。
