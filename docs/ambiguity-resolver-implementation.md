# DocumentStructureNodeAmbiguityResolver 实现计划

## 背景

### 问题域

文档结构解析管线中，第一阶段的 `DocumentStructureNodeSignalExtractor`（规则引擎）在处理单级数字编号（`1. xxx`、`12、xxx`）和中文序号（`一、xxx`、`二十三. xxx`）时，无法 100% 确定该行是**标题**还是**列表项**。

规则引擎能做的是：
- 检查前后邻接行是否构成连续序号（1 → 2 → 3）→ 偏向列表
- 检查前一行是否以冒号结尾（引导列表）→ 偏向列表
- 检查当前行是否"长得像标题"（短行、空白隔离、无句末标点、名词短语特征）→ 偏向标题

但这些启发式有天花板。比如：
```
1. 系统架构设计      ← 规则引擎给 HEADING_CANDIDATE（置信度 0.62），但 LLM 能从语义判断这是标题
2. 用户管理模块      ← 同上
3. 权限控制          ← 同上

1. 准备材料          ← 规则引擎给 LIST_ITEM（置信度 0.93，因为邻接序列），实际确实是列表
2. 配置环境          ←
3. 启动服务          ←
```

当规则引擎无法确定（输出 `HEADING_CANDIDATE`，置信度 0.58~0.62），需要 LLM 根据**语义 + 局部上下文**做二次判定。

### 参考实现

super-agent（同目录 `../super-agent`）在 `DocumentStructureNodeExtractor.extract()` 的四阶段管线中，第二阶段即 `ambiguityResolver.resolve()`：

```
Stage 1: signalExtractor.extract()        → 规则引擎逐行分类
Stage 2: ambiguityResolver.resolve()      → ★ LLM 二次判定模糊信号  ← 本次实现范围
Stage 3: hierarchyResolver.resolve()      → 信号 → 结构树
Stage 4: treeValidator.validateAndBuild() → 校验修复
```

### reuben-agent 当前状态

- `DocumentStructureNodeSignalExtractor` — ✅ 已实现，产出 `DocumentStructureNodeSignalBatch`（含 `HEADING_CANDIDATE` 信号）
- `DocumentStructureNodeExtractor` — ⚠️ Stub，仅调用了 signalExtractor，Stage 2/3/4 均为 TODO
- `DocumentProperties` — ⚠️ 仅有 MinIO 配置，缺少 `StructureParsing` 配置
- 无 prompt 模板文件
- 无 prompt 渲染服务
- 已有 `spring-ai-starter-model-openai` 依赖（提供 `ChatModel` / `ChatClient`）

### 设计原则

不要求与 super-agent 完全对齐。以下都可以按更好的思路写：
- 类名、方法名、字段名
- 注释风格与详细程度
- 日志级别与内容
- 防御性代码的粒度
- 模板引擎选型（StringTemplate vs 其他）
- JSON 解析方式（FastJSON vs Jackson vs ObjectMapper）
- 异常处理策略

但要保持与 reuben-agent 项目既有约定的兼容（见 CLAUDE.md）。

---

## 实现范围

仅实现 `ambiguityResolver.resolve(normalizedTitle, allLines, rawSignals)` 完整链路，不涉及 Stage 3/4。

---

## 功能点拆解

### 1. 配置属性 — `DocumentProperties.StructureParsing`

**文件**: `business/document/src/main/java/com/reubenagent/document/config/DocumentProperties.java`

新增内部类 `StructureParsing`，配置项前缀 `reuben.document.structure-parsing`：

| 属性 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| `llm-disambiguation-enabled` | `Boolean` | `true` | LLM 歧义消解总开关 |
| `max-ambiguous-signals-per-call` | `Integer` | `8` | 单次 LLM 调用最多处理多少个模糊行 |
| `context-window-lines` | `Integer` | `2` | 每个模糊行前后各取多少行作为上下文 |
| `ambiguity-confidence-floor` | `Double` | `0.45` | 模糊信号置信度下限（低于此值不送 LLM） |
| `ambiguity-confidence-ceil` | `Double` | `0.80` | 模糊信号置信度上限（高于此值不送 LLM） |

```yaml
# application.yml 示例
reuben:
  document:
    structure-parsing:
      llm-disambiguation-enabled: true
      max-ambiguous-signals-per-call: 8
      context-window-lines: 2
      ambiguity-confidence-floor: 0.45
      ambiguity-confidence-ceil: 0.80
```

- [ ] 1.1 在 `DocumentProperties` 中新增 `StructureParsing` 内部类及字段
- [ ] 1.2 添加 Javadoc（中文注释，与项目风格一致）
- [ ] 1.3 确认 `@ConfigurationProperties` 绑定正确

---

### 2. Prompt 模板

**目录**: `business/document/src/main/resources/prompt/`

super-agent 使用 StringTemplate（`.st`）格式，通过 Spring AI 的 `StTemplateRenderer` 渲染。reuben-agent 可选方案：

| 方案 | 优点 | 缺点 |
|------|------|------|
| **A. StringTemplate `.st`** | 与参考实现一致；Spring AI 原生支持 | 需确认 `StTemplateRenderer` 在 Spring AI 1.1.0 中可用 |
| **B. Java Text Block + `MessageFormat`** | 零额外依赖；模板和渲染逻辑在一起，调试直观 | 模板字符串较长时不够整洁 |
| **C. Mustache / Thymeleaf** | Spring Boot 原生支持 | 重，prompt 模板不需要 HTML 转义 |

**建议方案 A**（StringTemplate），因为 Spring AI 1.1.0 包含 `spring-ai-template-st` 模块。如果不可用则退到方案 B。

#### 2.1 主模板 `document-structure-ambiguity.st`

定义 system 指令 + 输出格式约束：

```
你是文档结构判歧助手。
你的任务是判断若干低置信度文本行，在当前上下文中更像：
- HEADING：章节/小节标题
- LIST_ITEM：普通列表项
- BODY：普通正文

请严格返回 JSON 数组，不要附加解释：
[
  {"line_no": 12, "resolved_kind": "HEADING | LIST_ITEM | BODY", "level_hint": 1}
]

规则：
1. 只有在非常像章节标题时才输出 HEADING。
2. 连续出现的编号项、步骤项、清单项优先判断为 LIST_ITEM。
3. 表格说明行、引用行、解释性句子优先判断为 BODY。
4. level_hint 只有 resolved_kind=HEADING 时才填写；没有把握时填 null。
5. 不要脑补目录结构，只依据提供的局部上下文判断。

文档标题：
<documentTitle>

<candidateBlocks>
```

#### 2.2 候选行模板 `document-structure-ambiguity-candidate.st`

单个模糊行的上下文渲染：

```
### 候选行 <lineNo>
<contextLines>
初始判断：<initialKind>
初始标题：<initialTitle>
初始编码：<initialCode>
```

- [ ] 2.1 创建 `prompt/` 目录结构
- [ ] 2.2 编写主 prompt 模板（可根据实际需要调整措辞）
- [ ] 2.3 编写 candidate 子模板
- [ ] 2.4 （可选）增加 few-shot 示例提升 LLM 准确率

---

### 3. Prompt 模板渲染服务 — `PromptTemplateService`

**文件**: 新建 `business/document/src/main/java/com/reubenagent/document/support/PromptTemplateService.java`

封装模板加载 + 缓存 + 变量替换：

- 从 classpath 加载 `.st` 模板文件
- 使用 `StTemplateRenderer`（或 `MessageFormat`）渲染
- 模板内容缓存（`ConcurrentHashMap`），避免重复 I/O
- 变量为 null 时替换为空字符串（防御）

如果采用方案 B（Java Text Block），此项可省略，模板直接内联在 Resolver 中。

- [ ] 3.1 实现 `PromptTemplateService` 类（如采用方案 A）
- [ ] 3.2 模板加载 + LRU 缓存
- [ ] 3.3 null-safe 变量替换

---

### 4. 核心类 — `DocumentStructureNodeAmbiguityResolver`

**文件**: 新建 `business/document/src/main/java/com/reubenagent/document/support/DocumentStructureNodeAmbiguityResolver.java`

#### 4.1 类结构

```java
@Slf4j
@Component
@AllArgsConstructor  // 构造器注入（遵循项目约定）
public class DocumentStructureNodeAmbiguityResolver {

    private final DocumentProperties properties;
    private final ObjectProvider<ChatModel> chatModelProvider;  // 懒加载，没有 ChatModel Bean 时优雅降级
    // + PromptTemplateService（如采用方案 A）

    /**
     * LLM 歧义消解入口。
     *
     * @param documentTitle 文档标题（用于 prompt 上下文）
     * @param allLines      全文各行 trimmed 文本（用于构建 LLM 上下文窗口）
     * @param sourceSignals  signalExtractor 产出的信号列表
     * @return 经过 LLM 判定后的信号列表
     */
    public List<DocumentStructureNodeSignal> resolve(
            String documentTitle,
            List<String> allLines,
            List<DocumentStructureNodeSignal> sourceSignals) {
        // ...
    }
}
```

#### 4.2 三道前置检查

```
1. properties.getStructureParsing().getLlmDisambiguationEnabled() == true
2. chatModelProvider.getIfAvailable() != null
3. sourceSignals 中存在 HEADING_CANDIDATE 且 confidence ∈ [floor, ceil]
```

任一失败 → 直接返回 `sourceSignals`（透传，不修改）。

#### 4.3 筛选模糊信号

- `kind == HEADING_CANDIDATE`
- `confidence >= floor && confidence <= ceil`
- 最多取 `maxAmbiguousSignalsPerCall` 个
- 使用 `Stream.limit()` 截断

#### 4.4 构建 Prompt

参照 super-agent 的 `buildCandidateBlocks`：
- 遍历筛选出的模糊信号
- 对每个信号，从 `allLines` 中取 `[lineNo - 1 - window, lineNo - 1 + window]` 范围的行
- 目标行前缀 `>>`，上下文行前缀 `   `
- 行号从 1 开始（与 LLM 交互一致）
- 渲染 candidate 子模板，拼接到主模板的 `<candidateBlocks>` 位置

⚠️ **reuben-agent 差异**: 信号的行号字段是 `logicalLineNo`（super-agent 是 `lineNo`），需要在映射时注意。

#### 4.5 调用 LLM

```java
String response = ChatClient.builder(chatModel)
    .build()
    .prompt()
    .user(prompt)
    .call()
    .content();
```

- 使用 Spring AI 的 `ChatClient`（非阻塞 fluent API）
- 不指定具体 model —— 由 Spring 上下文中的 `ChatModel` Bean 决定

#### 4.6 解析 LLM 响应

- 从 response 中提取 `[` ... `]` 之间的 JSON 数组
- 反序列化为 `List<Map<String, Object>>`
- 提取 `line_no`（Integer）、`resolved_kind`（String）、`level_hint`（Integer/null）
- 映射到内部 record `DisambiguationResult`

JSON 解析方式：
- super-agent 用 Jackson `ObjectMapper`
- reuben-agent 有 FastJSON，也可以用 Spring Boot 自动配置的 `ObjectMapper`
- **建议**：用 `ObjectMapper`（Spring Boot 已自动配置，不需要额外依赖），或者直接用 FastJSON 的 `JSON.parseArray()` 更简洁

#### 4.7 应用判定结果

对每个原始信号：
- 如果在 LLM 返回结果中 → 更新 `kind`、`levelHint`（仅 HEADING 时）、`confidence`（boost 到 ≥0.88）、追加 `"llm-disambiguated"` 到 `reasons`
- 如果不在 → 保持原样

Kind 映射：
- `"HEADING"` → `DocumentStructureNodeSignalEnum.HEADING`
- `"LIST_ITEM"` → `DocumentStructureNodeSignalEnum.LIST_ITEM`
- 其他 → `DocumentStructureNodeSignalEnum.BODY`

#### 4.8 异常处理（优雅降级）

```java
try {
    // LLM 调用 + JSON 解析
} catch (Exception e) {
    log.warn("LLM 歧义消解失败，回退到规则引擎结果: {}", e.getMessage());
    return sourceSignals;  // ⚠️ 返回原始列表，不中断管线
}
```

不区分异常类型（网络超时、JSON 解析失败、模型不可用等），统一降级。

- [ ] 4.1 实现 `resolve()` 入口方法
- [ ] 4.2 实现三道前置检查
- [ ] 4.3 实现模糊信号筛选（Stream + limit）
- [ ] 4.4 实现 `buildPrompt()` + `buildCandidateBlocks()`
- [ ] 4.5 实现 LLM 调用（ChatClient）
- [ ] 4.6 实现 `parse()` JSON 响应解析
- [ ] 4.7 实现 `applyResult()` 判定结果应用
- [ ] 4.8 实现 try/catch 优雅降级
- [ ] 4.9 添加完整 Javadoc（类 + 每个方法）
- [ ] 4.10 添加 `@Slf4j` 关键路径日志（debug: 筛选了多少条模糊信号; info: LLM 调用成功; warn: 降级）

---

### 5. 管线集成 — 修改 `DocumentStructureNodeExtractor`

**文件**: `business/document/src/main/java/com/reubenagent/document/support/DocumentStructureNodeExtractor.java`

在 `signalExtractor.extract()` 之后插入 `ambiguityResolver.resolve()`：

```java
// Stage 1: 规则引擎信号提取
DocumentStructureNodeSignalBatch signalBatch =
        documentStructureNodeSignalExtractor.extract(documentTitle, parsedText);

// Stage 2: LLM 歧义消解（NEW）
List<DocumentStructureNodeSignal> resolvedSignals = ambiguityResolver.resolve(
        normalizedTitle,
        signalBatch.contextLines(),
        signalBatch.signals());

// TODO Stage 3/4: hierarchyResolver + treeValidator（后续实现）
```

- [ ] 5.1 注入 `DocumentStructureNodeAmbiguityResolver`
- [ ] 5.2 在 `extract()` 中调用 `resolve()`
- [ ] 5.3 更新类 Javadoc，移除"当前为 stub"描述，标注 Stage 2 已实现

---

### 6. 单元测试 & 集成测试

#### 6.1 单元测试: `DocumentStructureNodeAmbiguityResolverTest`

- Mock `ChatModel`，返回预设的 JSON 响应
- 验证三道前置检查（开关关闭 / 无 ChatModel / 无模糊信号 → 透传）
- 验证 LLM 返回 HEADING → kind 正确更新
- 验证 LLM 返回 LIST_ITEM → kind 正确更新
- 验证 LLM 返回 BODY → kind 正确更新
- 验证 confidence boost（≥0.88）
- 验证 `"llm-disambiguated"` 追加到 reasons
- 验证异常时透传原始信号
- 验证 `maxAmbiguousSignalsPerCall` 截断

#### 6.2 集成测试

- 真实 `ChatModel` Bean + 测试文档
- 验证端到端管线（signalExtractor → ambiguityResolver）
- 或者用 WireMock 模拟 OpenAI API

- [ ] 6.1 编写单元测试（Mock ChatModel）
- [ ] 6.2 编写集成测试（可选，视测试基础设施而定）
- [ ] 6.3 所有测试通过

---

### 7. 文档 & 清理

- [ ] 7.1 更新 `CLAUDE.md` 中 Document Module 管线描述，标注 Stage 2 已实现
- [ ] 7.2 确保代码通过 `mvn compile -pl business/document -am`
- [ ] 7.3 确保测试通过 `mvn test -pl business/document -am`

---

## reuben-agent vs super-agent 关键差异对照

| 维度 | super-agent | reuben-agent（本次实现） |
|------|-------------|--------------------------|
| 歧义解析器 | `DocumentStructureAmbiguityResolver` | `DocumentStructureNodeAmbiguityResolver` |
| 信号类 | `DocumentStructureSignal` | `DocumentStructureNodeSignal` |
| 行号字段 | `getLineNo()` | `getLogicalLineNo()` |
| 信号类型枚举 | `DocumentStructureSignalKind` | `DocumentStructureNodeSignalEnum` |
| 标题编码字段 | `getNodeCode()` | `getHeadingCode()` |
| 字符工具 | `cn.hutool.StrUtil` | `org.apache.commons.lang3.StringUtils` |
| JSON 解析 | Jackson `ObjectMapper` | FastJSON 或 ObjectMapper |
| 配置前缀 | `app.manage.structure-parsing` | `reuben.document.structure-parsing` |
| Prompt 模板 | StringTemplate `.st` + `StTemplateRenderer` | 待定（建议同上） |
| 依赖注入 | 构造器（手动） | `@AllArgsConstructor`（Lombok） |
| 实体可变性 | `@Data` — setter 可变 | `@Data` — setter 可变 |

---

## 进度

| 功能点 | 状态 |
|--------|------|
| 1. 配置属性 StructureParsing | ⬜ |
| 2. Prompt 模板文件 | ⬜ |
| 3. PromptTemplateService | ⬜ |
| 4. DocumentStructureNodeAmbiguityResolver | ⬜ |
| 5. DocumentStructureNodeExtractor 集成 | ⬜ |
| 6. 测试 | ⬜ |
| 7. 文档 & 编译验证 | ⬜ |
