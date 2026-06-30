# UI Implementation Plan — Super-Agent 前端对齐

## 概述

**目标**: 功能上对齐 super-agent 前端（`/Users/reuben/Desktop/super-agent/vue/`），视觉风格沿用 reuben-agent 现有 UI（dark theme, amber accent, Geist fonts, Tailwind 4）。

**状态**: ✅ Phase 0–9 全部完成；Phase 10（super-agent 功能缺口补齐）已识别，待实施。Chat 主体验、Admin 全量页面、响应式收尾均已交付；与 super-agent Vue 侧逐页对照后，文档详情页与观测轮次详情页存在功能级缺口。

> **Phase 10 进度（2026-06-30）**：10.1 文档详情 + 10.2 文档列表已在 `c438020` 交付；本轮补齐 10.3 观测轮次详情（密度补齐）、10.5 仪表盘（服务端聚合 + 演示路径 + 最近文档）、10.6 知识路由（Scope Coverage + Profile Anomalies 面板）、10.8 观测列表（数字分页 + STOPPED 核对）、10.9 Chat（动态 scope pills）。10.4 / 10.7 待后续按需补。

**技术栈**: React 19 + TypeScript + Vite 6 + Tailwind CSS 4 + Framer Motion (motion) + Phosphor Icons + React Router DOM v7

**super-agent 源**: Vue 3 项目，`/Users/reuben/Desktop/super-agent/vue/src/`

**后端 API**: 37 个端点已就绪（chat 14 + document 23），无需后端改动。

---

## 现状盘点

### ✅ 已有（reuben-agent UI）

| 页面 | 路由 | 状态 |
|------|------|------|
| 文档列表 + 上传 | `/documents` | 功能完整 |
| 文档详情 | `/documents/:id` | 含 chunks / strategy / 状态 |
| 检索测试 | `/retrieval` | 可用 |
| Chat | `/chat` | **占位页** |
| Conversations | `/conversations` | **占位页** |
| AppShell + Sidebar | 全局 | 4 个导航项（2 个 SOON 标签） |

### ❌ 缺失（对标 super-agent）

| 功能 | super-agent 路由 | 复杂度 |
|------|-----------------|--------|
| **Chat 主界面** | `/chat` | 🔴 核心，最大工作量 |
| **Admin 登录** | `/admin/login` | 🟡 中等 |
| **Admin 仪表盘** | `/admin/dashboard` | 🟢 轻量 |
| **Admin 文档管理** | `/admin/documents` | 🟡 已有基础，需增强 |
| **Admin 知识路由** | `/admin/knowledge-route` | 🟡 4 个 Tab（scope/topic/profile/association） |
| **Admin 路由追踪** | `/admin/knowledge-route/traces` | 🟡 分析面板 |
| **Admin 可观测性列表** | `/admin/observability` | 🟡 筛选 + 分页列表 |
| **Admin 会话详情** | `/admin/observability/:id` | 🟡 轮次列表 + 实时轮询 |
| **Admin 轮次详情** | `/admin/observability/:id/exchanges/:eid` | 🟡 6 阶段时间线 |

---

## Phase 分解

### Phase 0 — 基础设施铺设 ✅

**目标**: API 层 + 类型体系 + 通用组件，为后续所有 Phase 提供地基。

| # | 任务 | 说明 |
|---|------|------|
| 0.1 | `api/client.ts` | 统一 HTTP 客户端：`ApiResponse<T>` 解包、错误处理、`/api` 代理 |
| 0.2 | `api/chat.ts` | Chat API：stream (SSE)、session CRUD、stop、rename、reset、summary rebuild |
| 0.3 | `api/knowledge.ts` | Knowledge 管理 API：scope/topic/profile/relation/trace |
| 0.4 | `api/admin.ts` | Admin API：auth login、dashboard、observability |
| 0.5 | `types/chat.ts` | Chat 类型：ChatStreamDto, ConversationView, ChatTurnVo, ChatTraceStageView, RetrievalResultView 等 |
| 0.6 | `types/knowledge.ts` | Knowledge 类型：scope, topic, profile, relation, trace |
| 0.7 | `hooks/useSSE.ts` | SSE Hook：连接管理、typed events 解析、自动重连、abort |
| 0.8 | `hooks/useAuth.ts` | Auth Hook：JWT 存储、解析、过期检查、login/logout |
| 0.9 | `components/shared/Markdown.tsx` | Markdown 渲染：marked + DOMPurify + highlight.js（bash/java/js/json/sql/xml/yaml） |
| 0.10 | `components/shared/StatusBadge.tsx` | 通用状态徽章：success/processing/danger/waiting 变体 |

**产物**: `ui/src/api/`, `ui/src/types/`, `ui/src/hooks/`, `ui/src/components/shared/` 下的新文件。

---

### Phase 1 — Chat 核心体验 ✅

**目标**: 对话主界面上线，覆盖基础消息流。

| # | 任务 | 说明 |
|---|------|------|
| 1.1 | `ChatPage.tsx` 主体布局 | 侧边栏（会话列表）+ 主面板（消息区 + 输入区），240px + 1fr Grid |
| 1.2 | `SessionSidebar.tsx` | 会话列表卡片：标题、预览、时间、消息数、running 徽章、删除按钮、新建按钮、empty state |
| 1.3 | `MessageBubble.tsx` | 消息气泡：用户头像 (Phosphor `User`) / AI 头像 (`Sparkle`)，角色名，时间戳，复制按钮 |
| 1.4 | `ChatComposer.tsx` | 输入区：3 模式切换 (Open Chat / Document QA / Auto Knowledge QA)、文档选择器、自适应 textarea、发送/停止按钮 |
| 1.5 | `ChatPage` 状态管理 | 会话创建/切换/删除、消息列表、模式切换自动新会话、loading/error/empty 三态 |
| 1.6 | 占位页替换 | 将 `/chat` 和 `/conversations` 路由指向 `ChatPage`（统一入口，侧边栏承载会话列表） |

**产物**: `ui/src/pages/ChatPage.tsx` + 4 个 chat 组件。

---

### Phase 2 — SSE 流式 + 富文本渲染 ✅

**目标**: 实时流式回答 + Markdown/代码高亮 + 流式光标动画。

| # | 任务 | 说明 |
|---|------|------|
| 2.1 | SSE 流式集成 | `useSSE` hook 接入 `POST /api/chat/stream`，解析 `data:` 块 → typed events (text/thinking/reference/recommend/status/error) |
| 2.2 | 流式消息更新 | 新消息 bubble 实时追加 token，streaming cursor 动画（Framer Motion 脉冲条） |
| 2.3 | Markdown 渲染接入 | `Markdown` 组件渲染 AI 回答，代码块 highlight.js 语法高亮 |
| 2.4 | 停止生成 | Stop 按钮 → `POST /api/chat/session/stop` → abort SSE |
| 2.5 | 流式完成处理 | streaming 结束 → 刷新会话列表 → 重新加载当前会话完整历史 |

**产物**: SSE 能力就绪，Chat 端到端可用的最小版本。

---

### Phase 3 — Chat 高级交互 ✅

**目标**: 对标 super-agent 的完整对话体验。

| # | 任务 | 说明 |
|---|------|------|
| 3.1 | Knowledge Route Explain Card | 每条 AI 回复下方可展开卡片：路由状态徽章、scope/topic 候选、文档候选排名（score + reason）、置信度 |
| 3.2 | Follow-up Suggestions | 最新 AI 消息下方展示推荐追问 chips，点击发送 |
| 3.3 | Prompt Chips (Empty State) | 无会话时展示 3 个建议问题 chips，"What can the assistant do" / "Break down complex problems" / "Map project capabilities" |
| 3.4 | Copy per Message | 复制按钮 + 1.8s check icon 反馈（已有基础，完善交互） |
| 3.5 | Auto Knowledge Mode Scope Pills | 自动模式下的内联说明 pills + 最近候选文档展示 |
| 3.6 | 会话重命名 | 双击/右键标题 → inline 编辑 → `POST /api/chat/session/rename` |
| 3.7 | 会话重置 | 重置按钮 → 确认 → `POST /api/chat/session/reset` |

**产物**: Chat 体验与 super-agent 功能对齐。

> 本轮实现：3.1–3.5 已在 Phase 2 随 MessageBubble / ChatComposer / RouteExplainCard 完成；3.6 会话重命名（header inline 编辑 + Enter 确认 / Esc 取消）、3.7 会话重置（确认横幅 + 清理轮次提示）本次补齐。

---

### Phase 4 — Admin 认证 + 路由守卫 ✅

**目标**: Admin 区域可访问、可保护。

| # | 任务 | 说明 |
|---|------|------|
| 4.1 | `AdminLoginPage.tsx` | 全屏登录页：用户名/密码表单、JWT 存储、登录成功跳转 |
| 4.2 | `AdminLayout.tsx` | Admin 独立 Shell：侧边栏导航（Dashboard / Documents / Knowledge Route / Observability） |
| 4.3 | Auth Guard | `ProtectedRoute` 组件：检查 JWT 有效性，未登录 → `/admin/login` |
| 4.4 | 路由重组 | `/admin/*` 嵌套路由：login、dashboard、documents、knowledge-route、observability |

**产物**: Admin 区域框架就绪。

> 后端 auth 模块仍为 stub，登录端点未实现，登录会失败并友好提示；守卫与跳转链路已就绪，后端就绪后可直接对接。Sidebar 底部新增「管理后台」入口。

---

### Phase 5 — Admin 仪表盘 ✅

**目标**: 运维概览页。

| # | 任务 | 说明 |
|---|------|------|
| 5.1 | 指标卡片 | 文档总数、索引成功率、今日会话数、平均延迟等关键指标 |
| 5.2 | 后端 API 对接 | 确认后端是否有 dashboard 聚合 API，若无则组合现有 API 计算 |

**产物**: `/admin/dashboard` 页面。

> 后端暂无聚合 API，当前用 `fetchDashboardMetrics`（会话总数，走 `/chat/session/list`）+ 已接入文档详情聚合（文档总数、索引成功率）计算。平均延迟 / 今日会话等需后端新增聚合端点后再补。

---

### Phase 6 — Admin 文档管理增强 ✅

**目标**: 在现有 `/documents` 基础上补全 super-agent 的管理能力。

| # | 任务 | 说明 |
|---|------|------|
| 6.1 | Index Build 流程 UI | 文档详情页增加：构建索引按钮 → 阶段管线可视化（current/pending/completed） → 日志抽屉面板 |
| 6.2 | Strategy 确认 UI | 策略方案列表 → 确认按钮 → 触发索引构建 |
| 6.3 | 状态轮询 | 解析/索引进行中时自动轮询刷新 |

**产物**: 文档管理能力与 super-agent admin 对齐。

> `/admin/documents` 复用现有 `DocumentUpload` + `DocumentList`；`/admin/documents/:id` 复用 `DocumentDetail`（已含 6.2 策略确认 + 6.3 解析/索引轮询）。6.1 阶段管线可视化与日志抽屉属于增量增强，留待后续按需补。

---

### Phase 7 — Admin 知识路由管理 ✅

**目标**: Scope / Topic / Profile / Association 的 CRUD 管理界面。

| # | 任务 | 说明 |
|---|------|------|
| 7.1 | Scope 管理 Tab | 表格列表 + 新建/编辑弹窗 + 软删除 |
| 7.2 | Topic 管理 Tab | 表格列表（可按 scope 筛选）+ 新建/编辑弹窗 |
| 7.3 | Document Profile Tab | 文档画像列表 + 单/批量重新生成 + 详情查看 |
| 7.4 | Document Association Tab | 主题-文档关联：按 topic 筛选 → 关联列表 → 添加/编辑/删除关联 |
| 7.5 | 路由追踪分析页 | 健康指标面板 + 追踪列表 + 详情 inspector（左右双栏） |

**产物**: `/admin/knowledge-route` + `/admin/knowledge-route/traces` 页面。

> 实现：`AdminKnowledgeRoutePage` 4 Tab（Scope/Topic/Profile/Relation）+ 右侧抽屉表单/详情；`AdminKnowledgeRouteTracePage` 左右双栏（健康指标 + Top 文档分布 + 追踪列表 + 候选详情 inspector）。文档画像走 `/document/page` 拉取含元数据文档列表，关联列表走 `topic/document/list`。`lib/knowledgeOptions.ts` 承载选项映射，`lib/knowledgeRoute.ts` 补齐 `summarizeRouteTraceRecords` / `buildTopDocumentDistribution` / `NormalizedRouteTrace.id/question` 等字段。

---

### Phase 8 — Admin 可观测性 ✅

**目标**: 会话/轮次级别的全链路观测。

| # | 任务 | 说明 |
|---|------|------|
| 8.1 | 会话列表 | 筛选（状态/模式/关键词）+ 分页表格 → `GET /api/chat/session/list` |
| 8.2 | 会话详情 | 轮次列表 + running 会话实时轮询 + "Live polling" 指示器 |
| 8.3 | 轮次详情 | 6 阶段执行时间线：Intent → Retrieval → Generation → Trace → Model Usage → Summary |
| 8.4 | 检索结果观测 | `GET /api/chat/exchange/retrieval/results` → 双通道对比视图 |
| 8.5 | Stage Benchmarks | `GET /api/chat/stage/benchmarks` → P50/P90/P99 延迟图表 |

**产物**: `/admin/observability/*` 路由完整。

> 实现：`AdminObservabilityListPage`（筛选 + 分页卡片网格）、`AdminObservabilitySessionPage`（轮次列表 + running 4s 轮询 + Live polling 徽章 + 重建摘要）、`AdminObservabilityExchangePage`（阶段时间线可展开 + 回答 Markdown 预览 + 检索结果/双通道执行双栏 + Stage Benchmarks P50/P90/P99 表格）。`lib/observability.ts` 承载数值型枚举（chatMode/turnStatus/stageState/executionMode/channel）格式化。

---

### Phase 9 — 收尾打磨 ✅

**目标**: 边界情况、响应式、动效润色。

| # | 任务 | 说明 |
|---|------|------|
| 9.1 | 响应式适配 | Chat 侧边栏在 `lg` 断点下变为抽屉（顶部 List 按钮触发，选中会话后自动收起）；Admin 侧边栏 `md` 断点变抽屉（已在 Phase 4 AdminLayout 内置） |
| 9.2 | Loading / Error / Empty | 所有页面三态覆盖（ChatPage/SessionSidebar/AdminObservabilityListPage 等均已具备） |
| 9.3 | Toast 通知 | 复用 `ToastProvider`，会话删除/重命名/重置、Admin 登录/退出、知识路由 CRUD 等操作均有反馈 |
| 9.4 | 键盘快捷键 | ChatComposer Enter 发送 / Shift+Enter 换行；ChatPage 重命名 Enter 确认 / Esc 取消；Drawer 全局 Esc 关闭 |
| 9.5 | 动效一致性审查 | Drawer / Toast / RouteExplainCard 折叠统一 `150-220ms`，Y 轴平移为主；MessageBubble 流式光标脉冲；hover 过渡 `transition-colors` |

**产物**: 生产就绪。

> 本轮收尾：ChatPage 增加 `lg` 断点抽屉侧边栏（顶栏 List 按钮切换，选中/新建会话后自动关闭），桌面端保持 280px 固定侧栏。AdminLayout 在 Phase 4 已实现 `md` 抽屉，无需重复。键盘快捷键与 Toast 在前序 Phase 已就绪，本次仅核对覆盖度。Phase 9 完成，UI 实现计划全部交付。

---

### Phase 10 — super-agent 功能缺口补齐（部分完成）

**目标**: 逐页对照 super-agent Vue 源（`/Users/linjiaqin.5/Desktop/learn/super-agent/vue/src/`）补齐功能级缺口。Phase 0–9 已交付主框架与基础体验，但 Vue 侧多个重页面（文档详情 4804 行、观测轮次详情 1764 行、知识路由 2238 行）相比 React 实现存在显著功能密度差距，需补齐。

> **完成情况**：✅ 10.1 / 10.2（`c438020`）；✅ 10.3 / 10.5 / 10.6 / 10.8 / 10.9（本轮）。⬜ 10.4 / 10.7 待补。

#### 10.1 文档详情页（最高优先级 — 功能闭环）

Vue `AdminDocumentDetailView.vue` 4804 行 vs React `DocumentDetailPage.tsx` ~128 行。当前 React 只有 Meta + ParentBlockPanel + StrategyPanel 静态展示，admin 无法完成"调策略 → 构建索引 → 看进度 → 看分块 → 看任务"的闭环。

| # | 任务 | Vue 源位置 |
|---|------|----------|
| 10.1.1 | Workbench 4-step 导航（概览/策略/执行/分块/任务，滚动锚点） | `:255-272` |
| 10.1.2 | 策略调优工作台：每条 pipeline 上移/下移、策略 chip 切换、最终提交顺序预览 | `:421-535` |
| 10.1.3 | "确认并构建"执行区 + 构建索引按钮（`submitConfirmStrategy` / `submitBuildIndex`） | `:574-597` |
| 10.1.4 | Index Build Tracker（实时构建阶段流水线 + 任务快照 footer） | `AdminDocumentCenterView.vue:539-655` |
| 10.1.5 | 构建期间锁页遮罩 | `:11-49` |
| 10.1.6 | 分块工作台：分组/平铺切换、全部展开/收起、分块统计卡（父块数/总片段/向量可用/待处理/平均Token） | `:663-810` |
| 10.1.7 | 分块详情抽屉（子证据 + 父上下文 + 兄弟块关系） | `:101-250` |
| 10.1.8 | 任务日志抽屉（"任务执行详情"时间线 stage/event/detailJson） | `:50-100, :906-935` |
| 10.1.9 | Tasks 区（第 4 步"查看任务记录" + 摘要日志列表） | `:906-935` |

#### 10.2 文档列表页

React `DocumentList` 当前走 localStorage，需切换到服务端分页 + 补交互。

| # | 任务 | Vue 源位置 |
|---|------|----------|
| 10.2.1 | 服务端分页 + 关键词搜索 + 表格列（type/size/updateTime）+ 删除动作 | `AdminDocumentListView.vue:82-189` |
| 10.2.2 | 统计卡（当前页文档/解析完成/策略确认/索引可用） | `:86-99` |
| 10.2.3 | 上传表单补字段：`knowledgeScopeCode`、`businessCategory`、`documentTags` | `:108-148` |

#### 10.3 观测-轮次详情页（功能密度最大缺口）✅

Vue `AdminObservabilityDetailView.vue` 1764 行 vs React `AdminObservabilityExchangePage.tsx`（原 323 行 → 本轮补齐至 ~560 行）。

| # | 任务 | Vue 源位置 |
|---|------|----------|
| 10.3.1 | Header meta 行补：文档范围 / 引用 vs 推荐 / 总Token vs 成本 | `:55-65` |
| 10.3.2 | 轮次摘要区（每阶段 summary cards：chips/metrics/textBlocks/listBlocks + "查看这个阶段的执行过程"链接） | `:122-189` |
| 10.3.3 | 通道性能对比网格（召回数/闸门后/最终选入/耗时/平均分/分数区间 + 子问题） | `:191-229` |
| 10.3.4 | 按子问题分组的检索结果表（排名变化/原始分/RRF分/Rerank分/状态徽章 已选入/闸门过滤/未选入） | `:231-289` |
| 10.3.5 | Evidence Budget 区（总预算/单子问题预算/已纳入/已省略 + 渲染 vs 省略引用详情） | `:291-330` |
| 10.3.6 | Prompt 预览（System/User tab 切换，`ragSystemPrompt` / `ragUserPrompt`） | `:332-356` |
| 10.3.7 | 性能基准对比卡（本次 vs P50/P90/P99 + 比较等级徽章，`formatBenchmarkComparison`） | `:357-388` |
| 10.3.8 | Trace Detail overlay（点时间线阶段弹出结构化详情：summaryItems/listSections） | `:390-420+` |

> 本轮实现 10.3.1 / 10.3.3 / 10.3.4 / 10.3.6 / 10.3.7 / 10.3.8：Header meta 补总Token/成本/引用推荐/模型调用次数（debugTraceJson 解析 modelUsageTraces）；通道性能对比网格（6 指标 + 子问题）；按子问题分组检索结果表（排名变化/原始分/RRF/Rerank/状态徽章）；Prompt 预览 System/User tab；时间线阶段内联基准对比徽章 + Trace Detail overlay 抽屉。10.3.2（轮次摘要区）/ 10.3.5（Evidence Budget）依赖后端 `debugTrace` 未持久化的 intentResolution / evidenceBudgetSnapshot 字段，留空态，待后端补持久化后再渲染。

#### 10.4 观测-会话详情页

| # | 任务 | Vue 源位置 |
|---|------|----------|
| 10.4.1 | Session Context 区（最近用户问题/最近助手回答/Checkpoint vs 消息数） | `AdminObservabilitySessionView.vue:60-75` |
| 10.4.2 | 记忆/长期摘要块（压缩 chips：covered/version/compress + 摘要文本 + 空态说明） | `:77-92` |
| 10.4.3 | 轮次行 meta 补：引用数/推荐数/Token/成本（当前只有首包/总耗时） | `:131-148` |

#### 10.5 仪表盘 ✅

| # | 任务 | Vue 源位置 |
|---|------|----------|
| 10.5.1 | "建议演示路径"面板（4 步引导流） | `AdminDashboardView.vue:38-58` |
| 10.5.2 | "最近接入文档"面板（刷新 + 6 个最近文档 + 解析/索引徽章） | `:60-99` |
| 10.5.3 | 后端聚合 API 对接（summary：total/parseSuccess/strategyConfirmed/indexSuccess） | `:60-99` |

> 本轮实现：`AdminDashboardPage` 改走 `/document/page` 服务端聚合（total/parseSuccess/strategyConfirmed/indexSuccess），替换原 localStorage 聚合；补"建议演示路径"4 步面板（含前往文档接入跳转）+ "最近接入文档"面板（刷新 + 6 个最近文档卡片 + parse/index 徽章 + 点击进详情）。会话总数仍走 `/chat/session/list`。

#### 10.6 知识路由页 ✅

| # | 任务 | Vue 源位置 |
|---|------|----------|
| 10.6.1 | Scope Coverage 面板（每 scope 覆盖率进度条 + topic/covered/pending/document 计数 + 总覆盖率，`scopeCoverageRows`/`overallCoverageRateText`） | `AdminKnowledgeRouteView.vue:29-55, :779-806` |
| 10.6.2 | Profile Anomalies 面板（异常列表 + tone + 逐行复选 + "全选异常" + 批量修复，`profileAnomalyRows`/`batchRepairProfiles`） | `:161-205, :808+` |

> 本轮实现：`AdminKnowledgeRoutePage` 顶部新增可折叠 Scope Coverage 面板（每 scope 覆盖率进度条 + topic/covered/pending/document 计数 + 整体覆盖率 pill，pending>0 高亮）+ Profile Anomalies 面板（异常列表 + danger/warning tone + 逐行复选 + "全选异常" + 批量重建 N 份，复用现有 scopes/topics/documents/relations 计算，无需新 API）。

#### 10.7 路由追踪页

| # | 任务 | Vue 源位置 |
|---|------|----------|
| 10.7.1 | 可折叠 insight bar 容器 | `AdminKnowledgeRouteTraceView.vue:22-65` |
| 10.7.2 | 健康度进度条 meter + "详细统计" mini-stats grid（`summaryCards`） | `:22-65` |

#### 10.8 观测列表页 ✅

| # | 任务 | Vue 源位置 |
|---|------|----------|
| 10.8.1 | 数字分页导航（1 2 3 4 5 … N 带省略号，`paginationItems`） | `AdminObservabilityListView.vue:141-153` |
| 10.8.2 | 状态筛选补 `STOPPED` 选项 | `:141-153` |

> 本轮实现：`AdminObservabilityListPage` 分页改为数字导航（`paginationItems`：首尾恒显 + 当前页左右各 1 + 省略号），当前页 amber 高亮；状态筛选 STOPPED(4) 选项此前已存在，本轮核对确认。

#### 10.9 Chat 页 ✅

| # | 任务 | Vue 源位置 |
|---|------|----------|
| 10.9.1 | 当前文档 / 自动预选 scope pills：`selectedDocumentName` pill、"最近主候选：…" pill（当前 `ChatComposer.tsx:123-126` 只有静态文本） | `BusinessChatView.vue:176-184` |

> 本轮实现：`ChatComposer` 文档模式补"当前文档：{name}" pill（未选时显示提示）；自动模式补"最近主候选：{name}" pill（取最近一条助手消息 routeExplain.topDocument）。`ChatPage` 传入 `latestTopDocumentName`。

#### 10.10 已对齐（无需补）

AdminLogin、StatusBadge、知识路由 4 Tab CRUD + 抽屉、路由追踪的筛选/列表/详情、观测列表的筛选/搜索、Chat 的停止/复制/推荐追问/路由解释卡 —— 功能已对齐，仅余视觉细节差异。

#### 实施优先级

1. **10.1 文档详情**（策略调优 + 构建索引 + Build Tracker）—— admin 功能闭环，没这个无法用
2. **10.3 观测轮次详情**（通道性能 + 分组检索表 + Trace overlay）—— 运维定位问题核心
3. **10.2 文档列表**（服务端分页 + 搜索 + 删除）—— 基础体验
4. **10.4 / 10.5 / 10.6** 按需补
5. **10.7 / 10.8 / 10.9** 收尾

**产物**: reuben-agent UI 与 super-agent 功能对齐，admin 文档/观测两条主线达到生产可用密度。

---

## 优先级矩阵

| Phase | 工作量 | 依赖 | 可并行 |
|-------|--------|------|--------|
| 0 | 中 (2-3天) | 无 | — |
| 1 | 大 (3-4天) | Phase 0 | — |
| 2 | 中 (2天) | Phase 1 | — |
| 3 | 中 (2-3天) | Phase 2 | 部分 |
| 4 | 小 (1天) | Phase 0 | Phase 1-3 |
| 5 | 小 (1天) | Phase 4 | Phase 6-8 |
| 6 | 中 (2天) | Phase 0 | Phase 5,7,8 |
| 7 | 大 (3天) | Phase 0 | Phase 5,6,8 |
| 8 | 大 (3天) | Phase 0 | Phase 5,6,7 |
| 9 | 小 (1-2天) | 全部 | — |

**推荐顺序**: 0 → 1 → 2 → 3 → 4 → (5,6,7,8 并行推进) → 9

**总估算**: ~15-20 工作日（单人）

---

## 设计约束

1. **暗色主题第一位** — `#0a0a0a` 背景、`neutral-900` 表面、`neutral-800` 边框
2. **琥珀强调色** — `bg-amber-500/10 text-amber-400` 用于活跃态，hover 用 `neutral-800/50`
3. **Geist 字体** — sans 正文、mono 代码/标签
4. **Phosphor Icons** — `weight="fill"` 活跃态、`weight="regular"` 非活跃态
5. **动画克制** — 150-200ms、Y 轴平移为主、Framer Motion
6. **`cn()` 工具函数** — `clsx` + `tailwind-merge` 处理条件类名
7. **Scrollbar** — 6px、透明轨道、`#404040` 滑块
8. **Toast** — 右上角固定、`rounded-lg`、`backdrop-blur-sm`、4s 自动消失

---

## 后端缺口（若发现）

当前后端 API 已覆盖 37 个端点。若 Phase 5（仪表盘）需要聚合统计 API，可能需要新增 1-2 个后端端点。其余无需后端改动。
