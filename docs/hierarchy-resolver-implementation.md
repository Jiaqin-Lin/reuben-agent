# DocumentStructureHierarchyResolver 实现计划

## 背景

### 问题域

文档结构解析管线中，Stage 1（规则引擎）产出扁平信号列表，Stage 2（LLM 歧义消解）将模糊的 `HEADING_CANDIDATE` 解析为确定类型。Stage 3 需要将这些**扁平信号转化为具有父子层级关系的树形结构**：

```
解析前（平铺信号）:
  HEADING   "第一章 概述"       (lineNo=1)
  BODY      "本章介绍..."       (lineNo=2)
  HEADING   "1.1 背景"         (lineNo=3)
  BODY      "在当今..."        (lineNo=4)
  LIST_ITEM "需求分析"         (lineNo=5)
  LIST_ITEM "方案设计"         (lineNo=6)
  HEADING   "1.2 目标"         (lineNo=7)

解析后（树形草稿）:
  ROOT (nodeNo=1, depth=0)
  ├── CHAPTER "第一章 概述" (nodeNo=2, depth=1, parent=1)
  │   ├── BODY "本章介绍..."
  │   ├── CHAPTER "1.1 背景" (nodeNo=3, depth=2, parent=2)
  │   │   ├── BODY "在当今..."
  │   │   ├── LIST_ITEM "需求分析" (nodeNo=4, depth=3, parent=3)
  │   │   └── LIST_ITEM "方案设计" (nodeNo=5, depth=3, parent=3)
  │   └── CHAPTER "1.2 目标" (nodeNo=6, depth=2, parent=2)
```

核心挑战：
1. **标题层级**：不同编号体系（Markdown `#`、中文"第X章"、数字"1.2.3"、附录）的标题需要统一解析深度和父节点
2. **列表嵌套**：通过缩进级别判断列表项的父子关系
3. **正文归并**：BODY/TABLE/QUOTE 信号需追加到对应章节/列表项的 contentText 中

### 参考实现

super-agent（同目录 `../super-agent`）在 `DocumentStructureNodeExtractor.extract()` 的四阶段管线中，Stage 3 即 `hierarchyResolver.resolve()`：

```
Stage 1: signalExtractor.extract()             → ✅ 规则引擎逐行分类
Stage 2: ambiguityResolver.resolve()           → ✅ LLM 二次判定模糊信号
Stage 3: hierarchyResolver.resolve()           → ★ 信号→结构树  ← 本次实现范围
Stage 4: treeValidator.validateAndBuild()      → TODO（下次实现）
```

### reuben-agent 当前状态

- `DocumentStructureNodeSignalExtractor` — ✅ Stage 1，产出信号列表
- `DocumentStructureNodeAmbiguityResolver` — ✅ Stage 2，LLM 消歧
- `DocumentStructureNodeExtractor` — ⚠️ Stage 1+2 已集成，Stage 3 为 TODO（返回空列表）
- `DocumentIntermediateStructureNode` — ✅ 已有最终节点模型，但缺少 draft 阶段所需的部分字段（`numericPath`、`sourceFamily`、`confidence`）和增量构建方法（`appendLine`）

### 设计原则

不要求与 super-agent 完全对齐。以下都可以按更好的思路写：
- 字段/方法命名（遵循 reuben-agent 项目约定）
- O(n²) 查找优化为 O(1) Map 查找
- 注释风格与详细程度
- 避免不必要的类/字段 — 不单独创建 Draft 类，复用 `DocumentIntermediateStructureNode`
- 使用 `StringUtils`（commons-lang3，项目已有）而非 Hutool `StrUtil`

---

## 实现范围

**仅实现 `hierarchyResolver.resolve(normalizedTitle, resolvedSignals)` 完整链路**，产出 `List<DocumentIntermediateStructureNode>`（草稿节点列表，含暂定 parentNodeNo 和 depth）。不涉及 Stage 4 treeValidator。

`DocumentStructureNodeExtractor.extract()` 在本次实现后直接返回 hierarchyResolver 的产出（跳过 treeValidator，下个 PR 补验证修复）。

---

## 功能点拆解

### 1. 增强 `DocumentIntermediateStructureNode` 模型

**文件**: `business/document/src/main/java/com/reubenagent/document/model/DocumentIntermediateStructureNode.java`

新增以下字段和方法以支持 draft 阶段：

#### 1.1 新增字段

| 字段 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| `lineNo` | `Integer` | `null` | 源文本行号，用于 treeValidator 兄弟排序 |
| `numericPath` | `List<Integer>` | `new ArrayList<>()` | 数字路径如 `[1,2,3]`，用于精确父标题匹配 |
| `sourceFamily` | `String` | `null` | 标题来源体系：`markdown` / `chapter` / `appendix` / `decimal` / `plain` |
| `confidence` | `double` | `0.0` | 置信度 0.0~1.0，从 signal 继承 |

#### 1.2 新增方法

```java
/**
 * 逐行追加正文内容。用于 hierarchyResolver 构建阶段增量拼接 body text。
 * 与 super-agent ContentHolder 不同：直接操作 contentText 字段，零额外对象。
 */
public void appendLine(String line)

/**
 * 是否为章节/标题节点。
 */
public boolean isSection()

/**
 * 是否为列表/步骤节点。
 */
public boolean isListLike()
```

#### 1.3 构造函数适配

- 保持 `@AllArgsConstructor` 不变（新字段需加入，否则 Lombok 生成的构造器不含它们）
- `@Builder.Default` 标注 `numericPath`

- [ ] 1.1 添加 `lineNo`、`numericPath`、`sourceFamily`、`confidence` 字段
- [ ] 1.2 添加 `appendLine(String)`、`isSection()`、`isListLike()` 方法
- [ ] 1.3 更新 `@AllArgsConstructor` 包含新字段
- [ ] 1.4 更新 `DocumentStructureNodeExtractor.extract()` 空文本兜底构造器调用

---

### 2. 核心类 — `DocumentStructureHierarchyResolver`

**文件**: 新建 `business/document/src/main/java/com/reubenagent/document/support/DocumentStructureHierarchyResolver.java`

#### 2.1 类结构

```java
@Slf4j
@Component
public class DocumentStructureHierarchyResolver {

    /**
     * 层级解析入口 —— 把平铺信号列表组装成树形草稿。
     *
     * @param documentTitle 文档标题
     * @param signals       Stage 2 消歧后的确定信号列表
     * @return 带暂定 parentNodeNo 和 depth 的节点列表（按 nodeNo 升序）
     */
    public List<DocumentIntermediateStructureNode> resolve(
            String documentTitle,
            List<DocumentStructureNodeSignal> signals)
}
```

无构造器注入依赖（纯计算组件）。

#### 2.2 核心数据结构

参照 super-agent 但优化查找效率：

| 变量 | 类型 | 说明 |
|------|------|------|
| `drafts` | `List<DocumentIntermediateStructureNode>` | 产出列表 |
| `nodeMap` | `Map<Integer, DocumentIntermediateStructureNode>` | **新增** — nodeNo→节点映射，O(1) 查找，消除 super-agent 的 O(n) `findByNodeNo` |
| `nextNodeNo` | `int` | 下一个节点编号（从 2 开始，1 留给根节点） |
| `currentSection` | `DocumentIntermediateStructureNode` | 当前所在章节节点 |
| `currentListItem` | `DocumentIntermediateStructureNode` | 当前所在列表项节点 |
| `listStack` | `Deque<ListContext>` | 列表缩进栈 |
| `latestHeadingByDepth` | `Map<Integer, Integer>` | 各深度最近标题的 nodeNo |
| `latestHeadingByNumericPath` | `Map<String, Integer>` | 数字路径 → nodeNo 映射 |

#### 2.3 信号分派逻辑

```
for each signal:
  BLANK              → 清空 currentListItem + listStack（空白行分隔列表组）
  NOISE              → 跳过
  TABLE_ROW/QUOTE/BODY → appendBody() 追加到 currentListItem（优先）或 currentSection
  STEP_ITEM/LIST_ITEM  → resolveListParent() + buildListNode() + 压入 listStack
  HEADING/HEADING_CANDIDATE → buildHeadingNode() + 更新 currentSection + 清空列表状态
  default            → appendBody() 兜底
```

#### 2.4 标题层级解析策略

通过 `resolveHeadingFamily(signal)` 从 signal.reasons 解析标题体系：

| reasons 包含 | family | 深度策略 | 父节点策略 |
|-------------|--------|---------|-----------|
| `markdown-heading` | `markdown` | levelHint 即 # 数量 | 按 depth-1 找最近标题 |
| `chapter-heading` | `chapter` | 固定 1 | 根节点 (nodeNo=1) |
| `appendix-heading` | `appendix` | 固定 1 | 根节点 (nodeNo=1) |
| `decimal-heading` / `single-digit-ambiguous-heading` | `decimal` | 父标题 depth+1，fallback 到 numericPath.size() | numericPath 父路径精确匹配，fallback 按 depth |
| 其他 | `plain` | levelHint 或 1 | 按 depth-1 找最近标题 |

#### 2.5 列表层级处理

通过 `Deque<ListContext>` 管理嵌套列表：
- 当前缩进 > 栈顶缩进 → 嵌套子列表，父=栈顶节点
- 当前缩进 ≤ 栈顶缩进 → 弹栈直到找到合适父级
- 弹空或缩进相等 → 同级新列表，父=currentSection

#### 2.6 与 super-agent 的关键差异

| 维度 | super-agent | reuben-agent（本次实现） |
|------|-------------|--------------------------|
| 草稿模型 | `DocumentStructureNodeDraft`（独立类） | `DocumentIntermediateStructureNode`（复用最终模型） |
| 正文增量构建 | `ContentHolder` 内部类 + StringBuilder | 直接操作 `contentText` 字段 |
| 节点查找 | `findByNodeNo` O(n) 线性扫描 | `nodeMap` O(1) HashMap 查找 |
| 信号 getter | `getLineNo()`, `getNormalizedText()`, `getNodeCode()`, `getItemIndex()` | `getLogicalLineNo()`, `getTrimmedText()`, `getHeadingCode()`, `getSequenceNo()` |
| 节点类型枚举 | `DocumentStructureNodeTypeEnum.DOCUMENT` / `SECTION` | `DocumentStructureNodeTypeEnum.ROOT` / `CHAPTER` |
| 字符工具 | `cn.hutool.StrUtil` | `org.apache.commons.lang3.StringUtils` |
| 信号枚举 | `DocumentStructureSignalKind` | `DocumentStructureNodeSignalEnum` |

#### 2.7 方法清单

| 方法 | 可见性 | 说明 |
|------|--------|------|
| `resolve(String, List)` | public | 入口 |
| `appendBody(...)` | private | 正文追加（同时写 listItem + section） |
| `buildListNode(...)` | private | 构建列表/步骤节点 |
| `resolveListParent(...)` | private | 按缩进栈确定列表父节点 |
| `registerListContext(...)` | private | 压入列表缩进栈 |
| `buildHeadingNode(...)` | private | 构建标题节点 + 更新 latestHeading 映射 |
| `resolveHeadingDepth(...)` | private | 按体系解析标题深度 |
| `resolveHeadingParentNodeNo(...)` | private | 按体系解析标题父节点 |
| `findNearestParentByDepth(...)` | private | 按 depth 向上找最近标题父节点 |
| `resolveHeadingFamily(...)` | private | 从 reasons 解析标题体系 |
| `buildHeadingAnchorText(...)` | private | 组装 nodeCode + title |
| `numericKey(List<Integer>)` | private static | 数字路径→字符串 "1.2.3" |
| `safeLevel(Integer, int)` | private static | levelHint 防御 |
| `safeIndentLevel(DocumentStructureNodeSignal)` | private static | indentLevel 防御 |

- [ ] 2.1 实现 `resolve()` 入口方法（根节点创建 + 信号遍历分派）
- [ ] 2.2 实现 `appendBody()`（双写策略：listItem 优先 → section 兜底 → root 保底）
- [ ] 2.3 实现 `buildListNode()` + `resolveListParent()` + `registerListContext()`
- [ ] 2.4 实现 `buildHeadingNode()` + `resolveHeadingDepth()` + `resolveHeadingParentNodeNo()`
- [ ] 2.5 实现 `resolveHeadingFamily()`（reasons 字符串匹配 → family）
- [ ] 2.6 实现辅助方法（`numericKey`, `safeLevel`, `safeIndentLevel`, `findNearestParentByDepth`, `buildHeadingAnchorText`）
- [ ] 2.7 添加完整 Javadoc（类 + 每个方法，中文注释）
- [ ] 2.8 `@Slf4j` trace 日志（每处理一个信号打印分派动作）

---

### 3. 管线集成 — 修改 `DocumentStructureNodeExtractor`

**文件**: `business/document/src/main/java/com/reubenagent/document/support/DocumentStructureNodeExtractor.java`

在 Stage 2 `ambiguityResolver.resolve()` 之后插入 Stage 3：

```java
// Stage 3: 层级解析 → 信号列表归并为树形草稿
List<DocumentIntermediateStructureNode> drafts =
        hierarchyResolver.resolve(normalizedTitle, resolvedSignals);

// TODO Stage 4: treeValidator.validateAndBuild()（后续实现）
// 当前直接返回草稿，缺少：siblingLinks / canonicalPath / sectionPath 重建 / 重复标题折叠

return drafts;
```

- [ ] 3.1 注入 `DocumentStructureHierarchyResolver`
- [ ] 3.2 在 `extract()` 中调用 `resolve()`，替代 `return new ArrayList<>()`
- [ ] 3.3 更新类 Javadoc，标注 Stage 3 已实现
- [ ] 3.4 移除或更新 Stage 3/4 的内联 TODO 注释

---

### 4. 单元测试

**文件**: 新建 `business/document/src/test/java/com/reubenagent/document/support/DocumentStructureHierarchyResolverTest.java`

#### 4.1 测试场景

| 场景 | 输入 | 预期 |
|------|------|------|
| **空信号列表** | `signals=[]` | 返回仅含根节点的列表 (size=1, nodeNo=1, depth=0) |
| **纯正文** | 3 条 BODY 信号 | 根节点 contentText 包含所有正文行 |
| **单标题+正文** | HEADING + 2 BODY | 1 ROOT + 1 CHAPTER，正文挂在 CHAPTER 下 |
| **多级标题** | HEADING(depth=1) + HEADING(depth=2) + HEADING(depth=3) | 三级嵌套，父子关系正确 |
| **数字多级标题(1.2.3)** | `1. xxx` → `1.1 xxx` → `1.1.1 xxx` → `1.2 xxx` | numericPath 父匹配正确，`1.1.1` 挂在 `1.1` 下，`1.2` 挂在 `1` 下 |
| **Markdown 标题** | `# H1` + `## H2` + `### H3` + `## H2` | markdown family，层级正确 |
| **列表嵌套** | LIST_ITEM(indent=0) + LIST_ITEM(indent=2) + LIST_ITEM(indent=0) | 第三个与第一个同级，第二个是第一个的子项 |
| **空白行分隔列表** | LIST_ITEM + BLANK + LIST_ITEM | 两个列表项是同级（非父子） |
| **标题打断列表** | LIST_ITEM + HEADING | 标题后列表状态清空 |
| **噪声跳过** | HEADING + NOISE + BODY + NOISE + HEADING | NOISE 不产生节点，正文正确归并 |
| **STEP_ITEM** | STEP_ITEM + BODY | STEP 节点创建，正文挂在 STEP 下 |
| **混合场景** | 完整的多章节文档模拟 | 节点层级、正文归并、列表嵌套全部正确 |
| **含 null 信号** | signals 含 null 元素 | 跳过 null，不 NPE |
| **DOCUMENT_TITLE 跳过** | 首条为 DOCUMENT_TITLE (lineNo=0) | 跳过，不产生额外节点 |

#### 4.2 测试基础设施

- 纯 JUnit 5 + AssertJ（无需 Spring 容器、无需 Mock）
- 手动构造 `DocumentStructureNodeSignal` 测试数据
- `@DisplayName` 中文描述

- [ ] 4.1 编写上述所有测试场景
- [ ] 4.2 所有测试通过

---

### 5. 编译验证

- [ ] 5.1 `mvn compile -pl business/document -am` 通过
- [ ] 5.2 `mvn test -pl business/document -am` 通过（含已有测试 + 新增测试）

---

## 优化点（相比 super-agent）

1. **O(1) 节点查找**：新增 `nodeMap`（`Map<Integer, DocumentIntermediateStructureNode>`），替代 super-agent 的 `findByNodeNo` O(n) 线性扫描。在 `resolveHeadingDepth` 中查找父节点时从 O(n²) 降至 O(n)。

2. **无独立 Draft 类**：不创建 `DocumentStructureNodeDraft`，直接复用 `DocumentIntermediateStructureNode`。减少类数量，避免 Draft→Node 的转换步骤。

3. **正文构建简化**：直接操作 `contentText` 字段进行增量拼接，不引入 `ContentHolder` 内部类。对于文档量级（<10MB 文本），String 拼接开销可忽略。

4. **常量提取**：heading family 字符串（`"markdown-heading"`、`"chapter-heading"` 等）提取为类常量，避免魔法字符串散落。

---

## 进度

| 功能点 | 状态 |
|--------|------|
| 1. 增强 DocumentIntermediateStructureNode | ✅ |
| 2. DocumentStructureHierarchyResolver | ✅ |
| 3. DocumentStructureNodeExtractor 集成 | ✅ |
| 4. 展示测试 | ✅ |
| 5. 编译验证 | ⬜（待用户运行） |
