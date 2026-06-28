# UI Implementation Plan — Super-Agent 前端对齐

## 概述

**目标**: 功能上对齐 super-agent 前端（`/Users/reuben/Desktop/super-agent/vue/`），视觉风格沿用 reuben-agent 现有 UI（dark theme, amber accent, Geist fonts, Tailwind 4）。

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

### Phase 3 — Chat 高级交互

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

---

### Phase 4 — Admin 认证 + 路由守卫

**目标**: Admin 区域可访问、可保护。

| # | 任务 | 说明 |
|---|------|------|
| 4.1 | `AdminLoginPage.tsx` | 全屏登录页：用户名/密码表单、JWT 存储、登录成功跳转 |
| 4.2 | `AdminLayout.tsx` | Admin 独立 Shell：侧边栏导航（Dashboard / Documents / Knowledge Route / Observability） |
| 4.3 | Auth Guard | `ProtectedRoute` 组件：检查 JWT 有效性，未登录 → `/admin/login` |
| 4.4 | 路由重组 | `/admin/*` 嵌套路由：login、dashboard、documents、knowledge-route、observability |

**产物**: Admin 区域框架就绪。

---

### Phase 5 — Admin 仪表盘

**目标**: 运维概览页。

| # | 任务 | 说明 |
|---|------|------|
| 5.1 | 指标卡片 | 文档总数、索引成功率、今日会话数、平均延迟等关键指标 |
| 5.2 | 后端 API 对接 | 确认后端是否有 dashboard 聚合 API，若无则组合现有 API 计算 |

**产物**: `/admin/dashboard` 页面。

---

### Phase 6 — Admin 文档管理增强

**目标**: 在现有 `/documents` 基础上补全 super-agent 的管理能力。

| # | 任务 | 说明 |
|---|------|------|
| 6.1 | Index Build 流程 UI | 文档详情页增加：构建索引按钮 → 阶段管线可视化（current/pending/completed） → 日志抽屉面板 |
| 6.2 | Strategy 确认 UI | 策略方案列表 → 确认按钮 → 触发索引构建 |
| 6.3 | 状态轮询 | 解析/索引进行中时自动轮询刷新 |

**产物**: 文档管理能力与 super-agent admin 对齐。

---

### Phase 7 — Admin 知识路由管理

**目标**: Scope / Topic / Profile / Association 的 CRUD 管理界面。

| # | 任务 | 说明 |
|---|------|------|
| 7.1 | Scope 管理 Tab | 表格列表 + 新建/编辑弹窗 + 软删除 |
| 7.2 | Topic 管理 Tab | 表格列表（可按 scope 筛选）+ 新建/编辑弹窗 |
| 7.3 | Document Profile Tab | 文档画像列表 + 单/批量重新生成 + 详情查看 |
| 7.4 | Document Association Tab | 主题-文档关联：按 topic 筛选 → 关联列表 → 添加/编辑/删除关联 |
| 7.5 | 路由追踪分析页 | 健康指标面板 + 追踪列表 + 详情 inspector（左右双栏） |

**产物**: `/admin/knowledge-route` + `/admin/knowledge-route/traces` 页面。

---

### Phase 8 — Admin 可观测性

**目标**: 会话/轮次级别的全链路观测。

| # | 任务 | 说明 |
|---|------|------|
| 8.1 | 会话列表 | 筛选（状态/模式/关键词）+ 分页表格 → `GET /api/chat/session/list` |
| 8.2 | 会话详情 | 轮次列表 + running 会话实时轮询 + "Live polling" 指示器 |
| 8.3 | 轮次详情 | 6 阶段执行时间线：Intent → Retrieval → Generation → Trace → Model Usage → Summary |
| 8.4 | 检索结果观测 | `GET /api/chat/exchange/retrieval/results` → 双通道对比视图 |
| 8.5 | Stage Benchmarks | `GET /api/chat/stage/benchmarks` → P50/P90/P99 延迟图表 |

**产物**: `/admin/observability/*` 路由完整。

---

### Phase 9 — 收尾打磨

**目标**: 边界情况、响应式、动效润色。

| # | 任务 | 说明 |
|---|------|------|
| 9.1 | 响应式适配 | 移动端侧边栏 slide-in overlay（1120px 断点），admin 侧边栏 drawer（1040px） |
| 9.2 | Loading / Error / Empty | 所有页面三态覆盖 |
| 9.3 | Toast 通知 | 操作反馈（成功/失败/加载中），已有 Toast 组件可复用 |
| 9.4 | 键盘快捷键 | Enter 发送、Shift+Enter 换行、Esc 关闭弹窗 |
| 9.5 | 动效一致性审查 | 所有动画 150-200ms、Y 轴平移为主、无过度动画 |

**产物**: 生产就绪。

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
