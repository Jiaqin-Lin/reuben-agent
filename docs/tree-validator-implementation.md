# DocumentStructureTreeValidator 实现计划

## 背景

### 问题域

Stage 3（`DocumentStructureHierarchyResolver`）将扁平信号归并为树形草稿后，节点的 `parentNodeNo`、`depth`、`canonicalPath`、`sectionPath` 均为**暂定值**，且 `prevSiblingNodeNo` / `nextSiblingNodeNo` 尚未设置。Stage 4 需要对草稿进行**全面验证与修复**：

```
Stage 3 产出（暂定值）:
  - parentNodeNo: Stage 3 推测，可能存在错位（悬空节点、章节挂在列表下）
  - depth: Stage 3 推测，parentNodeNo 修复后需重算
  - canonicalPath: 仅根节点设为 "/document"，其余为空
  - sectionPath: 仅根节点设为 ""，其余为空
  - prevSiblingNodeNo / nextSiblingNodeNo: 全为 null

Stage 4 修复后:
  - parentNodeNo: 经数字路径校验 + 无效父修复
  - depth: 从根节点递推重算
  - canonicalPath: 规范化机器路径（如 /document/h1/item-3）
  - sectionPath: 人类可读面包屑（如 "第一章 > 1.1 概述"）
  - prevSiblingNodeNo / nextSiblingNodeNo: 同父节点下双向链表
```

### 参考实现

super-agent（同目录 `../super-agent`）在 `DocumentStructureTreeValidator.validateAndBuild()` 中实现六步修复流水线：

```
Stage 1: signalExtractor.extract()             → ✅ 规则引擎逐行分类
Stage 2: ambiguityResolver.resolve()           → ✅ LLM 二次判定模糊信号
Stage 3: hierarchyResolver.resolve()           → ✅ 信号→结构树
Stage 4: treeValidator.validateAndBuild()      → ★ 校验修复  ← 本次实现范围
```

### reuben-agent 当前状态

- `DocumentStructureNodeSignalExtractor` — ✅ Stage 1
- `DocumentStructureNodeAmbiguityResolver` — ✅ Stage 2
- `DocumentStructureHierarchyResolver` — ✅ Stage 3
- `DocumentStructureNodeExtractor` — ⚠️ Stage 1-3 已集成，Stage 4 为 TODO（直接返回 Stage 3 草稿）
- `DocumentIntermediateStructureNode` — ✅ 已包含所有必需字段

### 设计原则

不要求与 super-agent 完全对齐。以下都可以按更好的思路写：
- 字段/方法命名（遵循 reuben-agent 项目约定）
- 不复用 super-agent 的 `DocumentStructureNodeCandidate` 独立类 — `DocumentIntermediateStructureNode` 直接作为最终输出
- 注释风格与详细程度
- 使用 `StringUtils`（commons-lang3）而非 Hutool `StrUtil`
- 防御性编程：所有 map 查找、null 字段访问均有守卫
- 常量提取：避免魔法字符串散落

---

## 实现范围

**仅实现 `treeValidator.validateAndBuild(normalizedTitle, drafts)` 完整链路**，将 Stage 3 草稿节点列表修复为最终结构树。不涉及上下游的 Stage 1-3 修改。

`DocumentStructureNodeExtractor.extract()` 在本次实现后调用完整的四阶段管线。

---

## 功能点拆解

### 1. 核心类 — `DocumentStructureTreeValidator`

**文件**: 新建 `business/document/src/main/java/com/reubenagent/document/support/DocumentStructureTreeValidator.java`

#### 1.1 类结构

```java
@Slf4j
@Component
public class DocumentStructureTreeValidator {

    /**
     * 树验证与构建入口 —— 六步修复流水线。
     *
     * @param documentTitle 文档标题（已剥离扩展名）
     * @param drafts        Stage 3 产出的草稿节点列表
     * @return 经过验证和修复的最终节点列表（按 nodeNo 升序）
     */
    public List<DocumentIntermediateStructureNode> validateAndBuild(
            String documentTitle,
            List<DocumentIntermediateStructureNode> drafts)
}
```

无构造器注入依赖（纯计算组件）。

#### 1.2 六步修复流水线

按执行顺序（不可乱）：

| 步骤 | 方法 | 功能 |
|------|------|------|
| ① | `collapseSyntheticTitleSection` | 删除与文档标题重复的一级章节，子节点上移到根 |
| ② | `repairNumberedHierarchy` | 按 numericPath（如 `[1,2,3]`）纠正多级编号的父子关系 |
| ③ | `repairInvalidParents` | 修复悬空节点（父不存在→挂根）+ 章节挂在列表下则上提 |
| ④ | `recomputeDepths` | 父子关系确定后从根递推真实深度 |
| ⑤ | `rebuildPaths` | 生成 canonicalPath 和 sectionPath |
| ⑥ | `rebuildSiblingLinks` | 同父节点下按 logicalLineNo 排序建立兄弟双向链表 |

#### 1.3 步骤详解

##### ① collapseSyntheticTitleSection

PDF/Word 解析后，文档标题常被重复识别为第一个 H1 章节。检测根节点的直接子节点中 **无编号**（nodeCode 为空）的章节，如果标题与文档名一致 → 删除该节点，其子节点直接挂到根节点。

```
修复前:                          修复后:
ROOT                            ROOT
├── CHAPTER "文档标题" (重复)    ├── CHAPTER "1.1 xxx"
│   ├── CHAPTER "1.1 xxx"       ├── CHAPTER "1.2 xxx"
│   └── CHAPTER "1.2 xxx"       └── LIST_ITEM "..."
└── LIST_ITEM "..."
```

##### ② repairNumberedHierarchy

如 `"1.2.3"` 的父必须是 `"1.2"`，`"1.2"` 的父必须是 `"1"`。先建立 `numericPath → nodeNo` 的全局索引，再遍历所有 section 纠正 parentNodeNo。

```
修复前:                          修复后:
ROOT                            ROOT
├── CHAPTER "1"                 ├── CHAPTER "1"
├── CHAPTER "1.2" (parent=1?)   │   ├── CHAPTER "1.1"
├── CHAPTER "1.1" (parent=1?)   │   └── CHAPTER "1.2"
└── CHAPTER "1.2.3" (parent=?)  │       └── CHAPTER "1.2.3"
```

##### ③ repairInvalidParents

- 父节点不存在（悬空节点）→ 重新挂到根节点
- 章节节点挂在列表项下（不合理的层级关系）→ 上提到列表项的父节点

##### ④ recomputeDepths

节点按 nodeNo 升序遍历（保证父节点先于子节点处理），depth = parent.depth + 1。

##### ⑤ rebuildPaths

- **canonicalPath**: 父路径 + "/" + pathSegment（如 `/document/h1/item-3`）
  - 章节节点：nodeCode slug 化
  - 列表节点：`item-{sequenceNo}`
- **sectionPath**: 章节节点拼接 " > " 面包屑（如 "第一章 > 1.1 背景"），非章节节点继承父 sectionPath

##### ⑥ rebuildSiblingLinks

将节点按 parentNodeNo 分组，每组内按 logicalLineNo 排序，为每个节点设置 `prevSiblingNodeNo` / `nextSiblingNodeNo`。首尾分别置 0。

#### 1.4 辅助方法

| 方法 | 可见性 | 说明 |
|------|--------|------|
| `normalizeComparableTitle(String)` | private static | 去 `#` / 扩展名 / 空格 / 小写，用于标题比较 |
| `numericKey(List<Integer>)` | private static | 数字路径 → 字符串 "1.2.3" |
| `buildPathSegment(DocumentIntermediateStructureNode)` | private | 构建 URL 安全路径段 |
| `displayTitle(DocumentIntermediateStructureNode)` | private | nodeCode + title 组合 |
| `slug(String)` | private | URL 安全 slug 化 |
| `joinSectionPath(String, String)` | private static | 父路径 + " > " + 当前标题 |

#### 1.5 与 super-agent 的关键差异

| 维度 | super-agent | reuben-agent（本次实现） |
|------|-------------|--------------------------|
| 输入类型 | `List<DocumentStructureNodeDraft>` | `List<DocumentIntermediateStructureNode>` |
| 输出类型 | `List<DocumentStructureNodeCandidate>`（独立类） | `List<DocumentIntermediateStructureNode>`（复用） |
| 无 toCandidate() | 最后一步转换 Draft→Candidate | 无需转换，直接返回修复后的节点 |
| 节点类型枚举 | `DOCUMENT` / `SECTION` | `ROOT` / `CHAPTER` |
| 列表序号字段 | `itemIndex` | `sequenceNo` |
| 行号字段 | `lineNo` | `logicalLineNo` |
| 字符工具 | `cn.hutool.StrUtil` | `org.apache.commons.lang3.StringUtils` |
| 兄弟哨兵值 | null | 0（首位/末位 sibling 统一为 0） |
| `isSection()` 判断 | `SECTION` | `CHAPTER` |
| sibling normalize | `normalizeSibling(null→0)` | 无需，直接设 0 |

#### 1.6 防御性编程

- drafts 为 null 或空 → 返回空列表
- draftMap 中 nodeNo=1（根节点）不存在 → 跳过深度重算和路径重建
- numericPath 为 null 或空 → 跳过数字层级修复
- parentNodeNo 指向不存在的节点 → repairInvalidParents 挂根
- nodeCode / title 为 null → StringUtils.defaultIfBlank 兜底

- [ ] 1.1 实现 `validateAndBuild()` 入口（六步流水线编排 + 防御守卫）
- [ ] 1.2 实现 ① `collapseSyntheticTitleSection()`
- [ ] 1.3 实现 ② `repairNumberedHierarchy()`
- [ ] 1.4 实现 ③ `repairInvalidParents()`
- [ ] 1.5 实现 ④ `recomputeDepths()`
- [ ] 1.6 实现 ⑤ `rebuildPaths()`
- [ ] 1.7 实现 ⑥ `rebuildSiblingLinks()`
- [ ] 1.8 实现辅助方法（normalizeComparableTitle, numericKey, buildPathSegment, displayTitle, slug, joinSectionPath）
- [ ] 1.9 添加完整 Javadoc（类 + 每个方法，中文注释）

---

### 2. 管线集成 — 修改 `DocumentStructureNodeExtractor`

**文件**: `business/document/src/main/java/com/reubenagent/document/support/DocumentStructureNodeExtractor.java`

在 Stage 3 `hierarchyResolver.resolve()` 之后插入 Stage 4：

```java
// Stage 3: 层级解析 → 信号列表归并为树形草稿
List<DocumentIntermediateStructureNode> drafts =
        hierarchyResolver.resolve(normalizedTitle, resolvedSignals);

// Stage 4: 树验证修复
List<DocumentIntermediateStructureNode> validated =
        treeValidator.validateAndBuild(normalizedTitle, drafts);

return validated;
```

- [ ] 2.1 注入 `DocumentStructureTreeValidator`
- [ ] 2.2 在 `extract()` 中调用 `validateAndBuild()`
- [ ] 2.3 更新类 Javadoc，标注 Stage 4 已实现
- [ ] 2.4 移除 Stage 4 TODO 注释

---

### 3. 展示测试

**文件**: 新建 `business/document/src/test/java/com/reubenagent/document/support/DocumentStructureTreeValidatorDisplayTest.java`

#### 3.1 测试设计

与 `DocumentStructureHierarchyResolverDisplayTest` 风格一致：
- 遍历 `test-documents/` 下所有测试文档
- 运行完整四阶段管线
- 彩色终端输出，可视化展示验证前后的差异

#### 3.2 输出内容

| 区域 | 内容 |
|------|------|
| **Stage 1-3 树（修复前）** | 展示 Stage 3 产出的草稿树，标注暂定 depth/parent |
| **Stage 4 修复详情** | 列出每一步修复的内容（如"折叠了重复标题"、"修复了 N 个父节点"、"重建了兄弟链接"） |
| **Stage 4 树（修复后）** | 展示最终结构树，含 canonicalPath、sectionPath、兄弟链接 |
| **断言** | 根节点校验、depth 一致性、sibling 双向链表完整性、canonicalPath 唯一性 |

#### 3.3 测试场景覆盖

| 场景 | 验证点 |
|------|--------|
| **空草稿列表** | 返回空列表，不 NPE |
| **仅根节点** | 根节点 depth=0，canonicalPath="/document" |
| **重复标题折叠** | 与文档名相同的无编号章节被移除，子节点上移 |
| **数字层级修复** | "1.2.3" 的父正确为 "1.2" |
| **无效父修复** | 悬空节点挂根，章节不在列表下 |
| **深度重算** | 修复后 depth 与父节点一致 |
| **路径重建** | canonicalPath / sectionPath 正确生成 |
| **兄弟链接** | prev/next 双向链表完整，首尾为 0 |

- [ ] 3.1 编写展示测试类（含彩色输出、树形可视化）
- [ ] 3.2 编写纯逻辑单元测试（覆盖上述所有场景）
- [ ] 3.3 所有测试通过

---

### 4. 编译验证

- [ ] 4.1 `mvn compile -pl business/document -am` 通过
- [ ] 4.2 `mvn test -pl business/document -am` 通过（含已有测试 + 新增测试）

---

## 优化点（相比 super-agent）

1. **无独立 Candidate 类**：不复用 super-agent 的 `DocumentStructureNodeCandidate`，`DocumentIntermediateStructureNode` 直接作为最终输出。减少类数量，消除 Draft→Candidate 转换步骤。

2. **兄弟哨兵值统一为 0**：super-agent 中兄弟首尾为 null，通过 `normalizeSibling(null→0)` 转换。reuben-agent 直接设 0，语义更清晰（"无兄弟"即 0）。

3. **O(1) 节点查找**：通过 `Map<Integer, DocumentIntermediateStructureNode>` 查找，而非遍历 list。与 Stage 3 HierarchyResolver 的优化保持一致。

4. **常量提取**：slug 中的正则、路径分隔符等提取为 `static final` 常量。

5. **防御性日志**：每一步修复后输出 debug 日志，记录修复数量（如 "修复了 3 个悬空节点"），便于排查问题。

---

## 进度

| 功能点 | 状态 |
|--------|------|
| 1. DocumentStructureTreeValidator | ✅ |
| 2. DocumentStructureNodeExtractor 集成 | ✅ |
| 3. 展示测试 | ✅ |
| 4. 编译验证 | ✅ |
