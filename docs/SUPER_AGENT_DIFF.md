# reuben-agent vs super-agent 差异清单

对比时间：2026-07-05
基线：reuben-agent（feat-chat-system 分支）vs super-agent（~/Desktop/super-agent）
范围：除 auth 外的全部模块（chat / rag / document）
仅记录 🔴高 / 🟡中 严重级别，🟢低 不收录。每条含 reuben 路径、super-agent 对比、影响说明。

---

## 🔴 高严重（影响功能正确性 / 核心能力缺失）

### H1. 双 checkpoint 机制冲突，自建表永不写入
- **reuben**: `business/chat/src/main/java/com/reubenagent/chat/session/ChatCheckpointManager.java` + `entity/ChatCheckpoint.java` + `config/ChatAgentConfiguration.java`（MysqlSaver bean，line 133-139）
- **super-agent**: `super-agent-business-chat/.../service/ChatCheckpointManager.java`（单一机制，直接包装 MysqlSaver）
- **说明**: reuben 同时存在两套 checkpoint：(1) Alibaba `MysqlSaver` 写 `GRAPH_CHECKPOINT`/`GRAPH_THREAD` 表，被 `ReactAgent.builder().saver()` 实际使用；(2) 自建 `ChatCheckpointManager` 操作 `reuben_agent_chat_checkpoint`/`reuben_agent_chat_thread` 表。但 `ChatCheckpointManager.put()` 全代码无调用方，`get`/`list` 也只在 clearThread 用到。自建表永远空，是死代码，浪费建表与维护成本，且 API 语义与 super-agent 不一致（super-agent 返回 Alibaba `Checkpoint` 含 state/messages，reuben 返回自建空实体）。

### H2. checkpoint 计数读一张表、清理删另一张表 → reset 后计数不归零
- **reuben**: `business/chat/.../service/impl/ChatSessionServiceImpl.java`（countCheckpoints 用 JDBC 裸 SQL 查 `GRAPH_CHECKPOINT`，clearThread 调 `ChatCheckpointManager` 清 `reuben_agent_chat_checkpoint`）
- **super-agent**: 读写同一张 `GRAPH_CHECKPOINT` 表
- **说明**: H1 的衍生 bug。reset 会话后前端展示的 `checkpointCount` 仍非零，因为计数查的表没被清理。修复 H1 后此条自动消除。

### H3. QueryRewrite 不做子问题拆解 + 指代消解
- **reuben**: `business/rag/src/main/java/com/reubenagent/rag/service/QueryRewriteService.java`（只输出单条 query）
- **super-agent**: `super-agent-business-chat/.../rag/service/ChatQueryRewriteService.java`（rewrite + sub_questions，子问题独立跑管线再合流）
- **说明**: reuben 改写跳过判断只看 `minQueryLength` 长度，不做多问号/分号/编号项启发式检测，不拼 `historySummary`。"它/那个"这类指代无法消解，复合问题召回显著弱于 super-agent。

### H4. toolTraces 工具调用追踪只有工具名，丢失入参/出参/耗时/失败
- **reuben**: `business/chat/.../agent/TavilySearchTool.java`（registerTrace 仅 markUsedTool）+ `business/chat/.../orchestrate/ChatStreamOrchestrator.java`（buildToolTraces 用 `Set<String>` usedTools 反推空 trace）
- **super-agent**: `super-agent-business-chat/.../tool/TavilySearchTool.java`（registerToolTrace/completeToolTrace/failToolTrace，实时写 ChatToolTrace）
- **说明**: reuben 注释明确"不持久化工具 trace"，finalize 时只能反推出仅含 `toolName+SUCCESS` 的空记录，丢掉 inputSummary/outputSummary/durationMs/errorMessage/status。

### H5. REACT_AGENT snapshot 不完整
- **reuben**: `business/chat/.../orchestrate/ReactAgentExecutor.java`（completeStage 仅 put usedTools）
- **super-agent**: `super-agent-business-chat/.../rag/executor/ReactAgentExecutor.java`（doOnComplete 同时 put `toolNames=toolTraces` 与 `usedTools`）
- **说明**: reuben snapshot 只回传 usedTools（且因 H4 导致 toolTraces 本就空）。观测页阶段 inspector 看不到 toolTraces 列表。

### H6. ClarificationExecutor / RagChatExecutor 未独立成 executor
- **reuben**: `business/chat/.../orchestrate/ChatStreamOrchestrator.java`（line 252 `if (plan.isClarification())` 内联处理）+ `RagAnswerExecutor.java`
- **super-agent**: `super-agent-business-chat/.../rag/executor/ClarificationExecutor.java` + `RagChatExecutor.java`
- **说明**: reuben 把 clarification 逻辑内联进 ChatStreamOrchestrator 而非独立 executor；`RagAnswerExecutor` 对应 super-agent `RagChatExecutor` 但 trace 阶段只记 EVIDENCE_BUDGET，缺 RAG_RETRIEVE 和 ANSWER_GENERATE（见 M5/M6）。功能在但执行器抽象不完整，新增 mode 时需改 orchestrator 而非加 executor。

---

## 🟡 中严重（实现偏差 / 可观测性缺失 / 边界场景隐患）

### M1. 切块降级目标不同：reuben 降级到递归，super-agent 降级到语义
- **reuben**: `business/document/.../service/impl/DocumentStrategyServiceImpl.java` applyLlmChunking（catch → 降级递归切块）
- **super-agent**: `super-agent-business-chat/.../manage/service/impl/DocumentStrategyServiceImpl.java` applyLlmChunking（catch → 降级语义切块，line 968）
- **说明**: 语义切块质量一般优于递归。需确认 reuben 是否故意。若非故意，建议对齐 super-agent 降级到语义。

### M2. 父块提升缺字符预算控制
- **reuben**: `business/rag/.../service/ParentBlockElevationService.java`（按 parentBlockId 去重）
- **super-agent**: `super-agent-business-chat/.../rag/service/RagRetrievalEngine.java`（elevateToParentBlocks，`parentEvidenceMaxChars` 字符预算约束）
- **说明**: reuben 缺预算控制，长父块会整体塞入上下文，可能超 token 预算。

### M3. RRF 在 fuse 阶段就按 finalTopK 截断
- **reuben**: `business/rag/.../service/RrfFusionService.java`（fuse 阶段按 finalTopK 截断）
- **super-agent**: `super-agent-business-chat/.../rag/service/RagRetrievalEngine.java`（fuseByRrf 按 candidateTopK 截断，再 elevation/rerank，最后 limit(finalTopK)）
- **说明**: reuben 提前截断会丢掉 rerank 前的候选量，影响最终排序质量。

### M4. toolCalls 计数语义偏差：Set 去重代替调用次数
- **reuben**: `business/chat/.../orchestrate/ChatStreamOrchestrator.java` buildLimitStats（`toolCallsUsed = usedTools.size()`）+ `ChatTaskInfo`（usedTools 为 `Set<String>`）
- **super-agent**: `super-agent-business-chat/.../service/BusinessChatService.java` snapshotUsedTools
- **说明**: 同一工具调用 N 次只算 1 次，`toolCallsUsed` 永远 ≤ 1，limitStats 失真。super-agent 同样去重但 trace 完整可校正，reuben 因 H4 无法事后校正。

### M5. ANSWER_GENERATE stage 从不记录
- **reuben**: `business/chat/.../orchestrate/RagAnswerExecutor.java`（只记 EVIDENCE_BUDGET，line 165）+ `ChatTraceStageCode.ANSWER_GENERATE` 枚举存在但无 startStage 调用
- **super-agent**: `super-agent-business-chat/.../rag/executor/RagChatExecutor.java`（line 211 startStage(ANSWER_GENERATE)）
- **说明**: reuben 观测页看不到"回答生成"阶段的耗时和 token，只能从 model usage trace 反推。

### M6. RAG 回答生成 trace 阶段缺失 RAG_RETRIEVE
- **reuben**: `business/chat/.../orchestrate/RagAnswerExecutor.java`
- **super-agent**: `super-agent-business-chat/.../rag/executor/RagChatExecutor.java`
- **说明**: reuben 的 RagAnswerExecutor trace 只记 EVIDENCE_BUDGET，缺 RAG_RETRIEVE 和 ANSWER_GENERATE 两个独立 stage（ANSWER_GENERATE 见 M5）。检索与回答的耗时/token 在观测页无法分别展示。

### M7. 改写/Rerank 无 LLM 观测、无 metadata 落库
- **reuben**: `business/rag/.../service/QueryRewriteService.java` + `RerankService.java`
- **super-agent**: `super-agent-business-chat/.../rag/service/ChatQueryRewriteService.java`（ObservedChatModelService + OpenAiChatOptions + traceRecorder）+ `HttpDocumentRerankPostProcessor.java`（记 rerankQuery/rerankModel/rerankDurationMs）
- **说明**: reuben 改写失败原因、rerank 元数据都无法追踪。

### M8. trace 检索观测归集点分散
- **reuben**: `business/chat/.../trace/ChatTraceRecorder.java`（无 recordRetrievalResults/recordChannelExecutions）
- **super-agent**: `super-agent-business-chat/.../service/ConversationTraceRecorder.java`（统一收口，委托 RetrievalObserveStore）
- **说明**: reuben 把检索观测拆到 `ChatRetrievalObserveStore` 单独调，recorder 不统一收口，排查口径不一致。

### M9. 会话列表 turnStatus 过滤被静默忽略
- **reuben**: `business/chat/.../session/ChatArchiveStoreImpl.java`（line 70-96 listConversations 接收 turnStatus 但只过滤 keyword/chatMode）
- **super-agent**: `super-agent-business-chat/.../service/MybatisConversationArchiveStore.java`（applySessionPageFilters 完整实现 latestTurnStatus 过滤，line 431-472）
- **说明**: 前端按"进行中/已完成/已停止"筛选会话列表时，reuben 返回全部会话，过滤失效但不报错。

### M10. 缺增量游标接口 listExchangesAfter
- **reuben**: `business/chat/.../session/ChatArchiveStore.java`（无 listExchangesAfter）
- **super-agent**: `super-agent-business-chat/.../service/ConversationArchiveStore.java`（listExchangesAfter，line 49，被 PersistentConversationMemoryService 用于增量摘要）
- **说明**: reuben 摘要压缩只能 `listRecentTurns(conversationId, Integer.MAX_VALUE)` 拉全量再过滤，长对话性能差。

### M11. stage_order 永远等于 stage_code，失去独立排序语义
- **reuben**: `business/chat/.../trace/impl/MybatisChatTraceStageStoreImpl.java`（line 61 `.stageOrder(stageCode.getCode())`）+ `ChatTraceStageCode.java`（code 1-10 连续，无独立 order 字段）
- **super-agent**: `ConversationTraceStageCode.java`（独立 order 字段 10/20/30...，code 是字符串）
- **说明**: reuben 的 stage_order 列与 stage_code 完全相同，靠 code 恰好递增才排序正确。未来插入不连续 code 会让排序错乱，且 stage_order 列无独立信息量。

### M12. ReAct 流式路径 model token 追踪丢失
- **reuben**: `business/chat/.../agent/ModelUsageTraceInterceptor.java`（line 34 注释自承流式 ChatResponse 为 null，仅记耗时与状态，token 拿不到）
- **super-agent**: `ObservedChatModelService`（streamText 用 doOnComplete/doOnError，ReAct 路径同样丢 token——super-agent 原问题）
- **说明**: reuben 的 ModelUsageTraceInterceptor 是对 super-agent 的改进（hook Alibaba 内部），但流式 token 仍丢。非流式路径完整。两边共有局限。

### M13. 删除/重置会话不清理检索观测数据
- **reuben**: `business/chat/.../support/IChatRetrievalObserveStoreInternal.java`（接口无 delete 方法）+ `ChatSessionServiceImpl`（deleteConversation/resetConversation 不清理 retrieval_result/channel_execution 表）
- **super-agent**: `RetrievalObserveStore.java`（deleteByConversation，line 24）+ `BusinessChatService.java`（line 595 reset 时清理）
- **说明**: reuben 删除会话后，检索结果和通道执行记录成孤儿数据（依赖 MP 逻辑删除标记 is_deleted=1，永不物理清理）。

### M14. answerRecentTranscript 算了但没注入 RAG prompt
- **reuben**: `business/chat/.../service/impl/ChatMemoryServiceImpl.java`（line 121/142 计算 answerTranscript）+ `ChatRagPromptAssemblyService.java`（line 90-99 buildHistoryContextBlock 实际用 plan.getRecentTranscript()）
- **super-agent**: `ConversationExecutionPlan.java`（answerRecentTranscript 字段，line 99）+ `AnswerHistoryContextAssembler.java`（独立组装器）
- **说明**: reuben 的 `answerRecentTranscript` 是死代码，RAG 回答 prompt 用完整 recent transcript（含 user+assistant），比 super-agent 的"仅答案摘要"更耗 token。同时缺独立 AnswerHistoryContextAssembler，历史拼接粗糙无 token 预算管理。

### M15. 移除 DashScopeCompatibilityInterceptor
- **reuben**: `business/chat/.../config/ChatAgentConfiguration.java`（注释声明移除）
- **super-agent**: `super-agent-business-chat/.../support/DashScopeCompatibilityInterceptor.java`（555 行）
- **说明**: 对 DeepSeek 是合理简化，但若后续接通义/DashScope 模型，并行工具调用与流式 chunk 行为可能出错。设计取舍非 bug，记为能力缺口。

### M16. 会话视图不展示 ReAct checkpoint state/messages
- **reuben**: `business/chat/.../service/impl/ChatSessionServiceImpl.java`（line 460-468 只从业务 turns 取 latestUserMessage/latestAssistantMessage）
- **super-agent**: `BusinessChatService.java`（line 941-961 toSessionView 调 checkpointManager.get 取 state/messages，会话视图展示 messageCount/latestUserMessage/latestAssistantMessage，优先业务 exchanges 回退 checkpoint）
- **说明**: reuben 用户在会话视图看不到 ReAct agent 的中间消息状态，只能看业务轮次。与 H1 相关，H1 修复后可补此能力。

---

## 修复优先级建议

**第一优先级（功能正确性，建议先修）**:
- H1 + H2（checkpoint 双机制）—— 删自建表 + ChatCheckpointManager 改包装 MysqlSaver，H2 自动消除
- H4 + H5（toolTraces + snapshot）—— TavilySearchTool 实时写 trace，ReactAgentExecutor snapshot 补 toolTraces
- H3（QueryRewrite 子问题拆解）—— 改写层加 sub_questions + 指代消解

**第二优先级（可观测性 + 边界场景）**:
- M5 + M6（RagAnswerExecutor 补 RAG_RETRIEVE/ANSWER_GENERATE stage）
- M9（turnStatus 过滤）
- M13（删除会话清理检索观测）
- M1（切块降级对齐到语义）

**第三优先级（工程化优化，可延后）**:
- M2/M3/M4/M7/M8/M10/M11/M14/M15/M16
