package com.reubenagent.document.support;

import com.reubenagent.document.enums.DocumentStructureNodeTypeEnum;
import com.reubenagent.document.model.DocumentIntermediateStructureNode;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 文档结构树验证器 —— Stage 4：对层级解析器产出的草稿节点进行验证、修复和规范化。
 *
 * <p>文档结构分析流水线的最后一步组件。负责对 {@link DocumentStructureHierarchyResolver}
 * 生成的节点草稿列表进行全面验证和修复，产出可直接用于切片策略和索引构建的最终结构树。</p>
 *
 * <h3>在流水线中的角色</h3>
 * <p>位于层级解析之后，是结构分析流水线的终点：</p>
 * <pre>
 *   DocumentStructureHierarchyResolver → DocumentStructureTreeValidator → List&lt;DocumentIntermediateStructureNode&gt;
 * </pre>
 *
 * <h3>六大修复步骤（按执行顺序，不可乱）</h3>
 * <ol>
 *   <li><b>折叠重复标题节点</b>（{@link #collapseSyntheticTitleSection}）：
 *       检测并移除与文档标题重复的一级章节节点，将其子节点直接挂到根节点下</li>
 *   <li><b>修复数字层级关系</b>（{@link #repairNumberedHierarchy}）：
 *       根据节点的 numericPath（如 [1,2,3]）重建父子关系，纠正层级解析
 *       阶段可能出现的层级错位</li>
 *   <li><b>修复无效父节点</b>（{@link #repairInvalidParents}）：
 *       检测父节点不存在的情况（悬空节点），将其重新挂到根节点；
 *       同时修复"章节节点挂在列表节点下"的不合理结构</li>
 *   <li><b>重新计算深度</b>（{@link #recomputeDepths}）：
 *       在修复父子关系后，从根节点开始重新计算每个节点的实际深度</li>
 *   <li><b>重建路径</b>（{@link #rebuildPaths}）：
 *       为每个节点计算 canonicalPath（如 /document/section-1/item-2）
 *       和 sectionPath（如 "第一章 > 1.1 背景"）</li>
 *   <li><b>重建兄弟链接</b>（{@link #rebuildSiblingLinks}）：
 *       为同一父节点下的所有子节点建立双向链表关系
 *       （prevSiblingNodeNo / nextSiblingNodeNo）</li>
 * </ol>
 *
 * <h3>最终输出</h3>
 * <p>经过以上六步处理后，每个节点草稿被修复为层级关系正确、路径完整、兄弟链接齐全的
 * 最终结构节点，可直接用于切片策略计算和索引构建。</p>
 *
 * <h3>与 super-agent 的关键差异</h3>
 * <ul>
 *   <li>无独立 Candidate 类 —— 直接复用 {@link DocumentIntermediateStructureNode}，消除转换步骤</li>
 *   <li>兄弟哨兵值使用 0（而非 null）—— 语义更清晰</li>
 *   <li>O(1) 节点查找 —— 通过 {@code Map<Integer, DocumentIntermediateStructureNode>}，与 Stage 3 一致</li>
 * </ul>
 *
 * @author reuben
 * @since 2026-06-19
 * @see DocumentStructureHierarchyResolver
 * @see DocumentIntermediateStructureNode
 */
@Slf4j
@Component
public class DocumentStructureTreeValidator {

    // ========================================================================
    // 常量
    // ========================================================================

    private static final String ROOT_CANONICAL_PATH = "/document";
    private static final String PATH_SEPARATOR = "/";
    private static final String SECTION_PATH_SEPARATOR = " > ";
    private static final String ITEM_PREFIX = "item-";
    private static final String NODE_FALLBACK = "node";

    /** slug 化：空白字符 → 连字符 */
    private static final String SLUG_WHITESPACE_RE = "\\s+";
    /** slug 化：保留字符集（CJK、字母、数字、下划线、连字符、点号） */
    private static final String SLUG_KEEP_RE = "[^\\p{IsHan}A-Za-z0-9_.-]";
    /** 标题规范化：去除 Markdown # 前缀 */
    private static final String NORMALIZE_STRIP_MARKDOWN_RE = "^#+\\s*";
    /** 标题规范化：去除文件扩展名 */
    private static final String NORMALIZE_STRIP_EXT_RE = "\\.[A-Za-z0-9]{1,6}$";
    /** 标题规范化：压缩空白 */
    private static final String NORMALIZE_COLLAPSE_SPACE_RE = "\\s+";

    // ========================================================================
    // 入口
    // ========================================================================

    /**
     * 树验证与构建入口 —— 六步修复流水线。
     *
     * <p>每一步都可能修改节点间的父子关系、深度或路径，顺序不能乱：</p>
     * <ol>
     *   <li>{@link #collapseSyntheticTitleSection} —— 删除与文档标题重复的一级章节，子节点上移</li>
     *   <li>{@link #repairNumberedHierarchy} —— 按 numericPath [1,2,3] 纠正多级编号的父子关系</li>
     *   <li>{@link #repairInvalidParents} —— 修复悬空节点（父不存在→挂根），修复章节挂在列表下的错位</li>
     *   <li>{@link #recomputeDepths} —— 在父子关系修复后重新计算每个节点的真实深度</li>
     *   <li>{@link #rebuildPaths} —— 生成 canonicalPath 和 sectionPath</li>
     *   <li>{@link #rebuildSiblingLinks} —— 同父节点下按 logicalLineNo 排序建立兄弟双向链表</li>
     * </ol>
     *
     * @param documentTitle 文档标题（已剥离扩展名）
     * @param drafts        Stage 3 产出的草稿节点列表
     * @return 经过验证和修复的最终节点列表（按 nodeNo 升序），drafts 为 null 或空时返回空列表
     */
    public List<DocumentIntermediateStructureNode> validateAndBuild(
            String documentTitle,
            List<DocumentIntermediateStructureNode> drafts) {

        if (drafts == null || drafts.isEmpty()) {
            return List.of();
        }

        // 构建 nodeNo → 节点 的映射表，后续所有修复步骤直接操作此 Map
        Map<Integer, DocumentIntermediateStructureNode> draftMap = new LinkedHashMap<>();
        for (DocumentIntermediateStructureNode draft : drafts) {
            if (draft != null && draft.getNodeNo() != null) {
                draftMap.put(draft.getNodeNo(), draft);
            }
        }

        if (draftMap.isEmpty()) {
            return List.of();
        }

        // ① 折叠重复标题：如果文档名和某个一级章节标题相同，删掉该章节，子节点直接挂根
        collapseSyntheticTitleSection(documentTitle, draftMap);

        // ② 按数字路径修复："1.2.3" 的父必须是 "1.2"，避免层级错位
        repairNumberedHierarchy(draftMap);

        // ③ 修复无效父节点：悬空节点挂根，章节挂在列表下则上提
        repairInvalidParents(draftMap);

        // ④ 重新计算深度：在父子关系确定后，从根开始递推真实 depth
        recomputeDepths(draftMap);

        // ⑤ 重建路径：生成 canonicalPath 和 sectionPath
        rebuildPaths(documentTitle, draftMap);

        // ⑥ 重建兄弟链表：同父节点下按 logicalLineNo 排序，设置 prev/nextSiblingNodeNo
        rebuildSiblingLinks(draftMap);

        // 按 nodeNo 升序排列
        return draftMap.values().stream()
                .sorted(Comparator.comparingInt(DocumentIntermediateStructureNode::getNodeNo))
                .toList();
    }

    // ========================================================================
    // ① 折叠重复标题
    // ========================================================================

    /**
     * 折叠与文档标题重复的章节节点。
     *
     * <p>PDF/Word 解析后，文档标题常被重复识别为第一个 H1 章节。
     * 检测根节点直接子节点中的无编号 section，如果标题与文档名一致 → 删除该节点，
     * 其子节点直接挂到根节点下。</p>
     */
    private void collapseSyntheticTitleSection(String documentTitle,
                                                Map<Integer, DocumentIntermediateStructureNode> draftMap) {
        String normalizedTitle = normalizeComparableTitle(documentTitle);
        if (normalizedTitle.isEmpty()) {
            return;
        }

        // 查找与文档标题重复的无编号一级章节
        Integer duplicateNodeNo = null;
        for (DocumentIntermediateStructureNode node : draftMap.values()) {
            if (node == null
                    || node.getNodeNo() == null
                    || node.getNodeNo() == 1
                    || !node.isSection()
                    || !Objects.equals(node.getParentNodeNo(), 1)
                    || StringUtils.isNotBlank(node.getNodeCode())) {
                continue;
            }
            if (normalizedTitle.equals(normalizeComparableTitle(node.getTitle()))) {
                duplicateNodeNo = node.getNodeNo();
                break;
            }
        }

        if (duplicateNodeNo == null) {
            return;
        }

        // 将重复节点的子节点直接挂到根节点
        for (DocumentIntermediateStructureNode node : draftMap.values()) {
            if (node != null && Objects.equals(node.getParentNodeNo(), duplicateNodeNo)) {
                node.setParentNodeNo(1);
            }
        }

        draftMap.remove(duplicateNodeNo);
        log.debug("折叠重复标题: 移除 nodeNo={}（标题与文档名重复），子节点上移到根", duplicateNodeNo);
    }

    // ========================================================================
    // ② 数字层级修复
    // ========================================================================

    /**
     * 按 numericPath 修复层级：如 "1.2.3" 的父必须是 "1.2"，"1.2" 的父必须是 "1"。
     * 先建立 numericPath → nodeNo 的全局索引，再遍历所有 section 纠正 parentNodeNo。
     */
    private void repairNumberedHierarchy(Map<Integer, DocumentIntermediateStructureNode> draftMap) {
        // 建立 numericPath → nodeNo 索引
        Map<String, Integer> numericPathMap = new LinkedHashMap<>();
        for (DocumentIntermediateStructureNode node : draftMap.values()) {
            if (node == null || !node.isSection()) {
                continue;
            }
            String key = numericKey(node.getNumericPath());
            if (StringUtils.isNotBlank(key)) {
                numericPathMap.putIfAbsent(key, node.getNodeNo());
            }
        }

        if (numericPathMap.isEmpty()) {
            return;
        }

        int repairCount = 0;
        for (DocumentIntermediateStructureNode node : draftMap.values()) {
            if (node == null || !node.isSection()) {
                continue;
            }
            List<Integer> numericPath = node.getNumericPath();
            if (numericPath == null || numericPath.isEmpty()) {
                continue;
            }

            // 单级编号直接挂根
            if (numericPath.size() == 1) {
                if (!Objects.equals(node.getParentNodeNo(), 1)) {
                    node.setParentNodeNo(1);
                    repairCount++;
                }
                continue;
            }

            // 找直接父标题（如 "1.2.3" → "1.2"）
            String directParentKey = numericKey(numericPath.subList(0, numericPath.size() - 1));
            Integer directParent = numericPathMap.get(directParentKey);
            if (directParent != null) {
                if (!Objects.equals(node.getParentNodeNo(), directParent)) {
                    node.setParentNodeNo(directParent);
                    repairCount++;
                }
                continue;
            }

            // 回退到章节级父标题（如 "1.2.3" → "1"）
            String chapterParentKey = numericKey(List.of(numericPath.get(0)));
            Integer chapterParent = numericPathMap.get(chapterParentKey);
            if (chapterParent != null && !Objects.equals(node.getParentNodeNo(), chapterParent)) {
                node.setParentNodeNo(chapterParent);
                repairCount++;
            }
        }

        if (repairCount > 0) {
            log.debug("数字层级修复: 纠正了 {} 个节点的父子关系", repairCount);
        }
    }

    // ========================================================================
    // ③ 无效父节点修复
    // ========================================================================

    /**
     * 修复无效父节点：
     * <ul>
     *   <li>父节点不存在（悬空节点）→ 重新挂到根节点</li>
     *   <li>章节节点挂在列表项下（不合理的层级关系）→ 上提到列表项的父节点</li>
     * </ul>
     */
    private void repairInvalidParents(Map<Integer, DocumentIntermediateStructureNode> draftMap) {
        int danglingCount = 0;
        int promotedCount = 0;

        for (DocumentIntermediateStructureNode node : draftMap.values()) {
            if (node == null || node.getNodeNo() == 1) {
                continue;
            }

            // 悬空节点：父节点不存在
            DocumentIntermediateStructureNode parent =
                    node.getParentNodeNo() == null ? null : draftMap.get(node.getParentNodeNo());
            if (parent == null) {
                node.setParentNodeNo(1);
                danglingCount++;
                continue;
            }

            // 章节挂在列表下：上提到列表的父节点
            if (node.isSection() && parent.isListLike()) {
                Integer grandParentNo = parent.getParentNodeNo();
                node.setParentNodeNo(grandParentNo == null ? 1 : grandParentNo);
                promotedCount++;
            }
        }

        if (danglingCount > 0 || promotedCount > 0) {
            log.debug("无效父修复: {} 个悬空节点挂根, {} 个章节从列表下上提", danglingCount, promotedCount);
        }
    }

    // ========================================================================
    // ④ 深度重算
    // ========================================================================

    /**
     * 从根节点开始重新计算每个节点的真实深度。
     * 按 nodeNo 升序遍历（保证父节点先于子节点处理），depth = parent.depth + 1。
     */
    private void recomputeDepths(Map<Integer, DocumentIntermediateStructureNode> draftMap) {
        DocumentIntermediateStructureNode root = draftMap.get(1);
        if (root == null) {
            return;
        }
        root.setDepth(0);

        List<DocumentIntermediateStructureNode> ordered = draftMap.values().stream()
                .sorted(Comparator.comparingInt(DocumentIntermediateStructureNode::getNodeNo))
                .toList();

        for (DocumentIntermediateStructureNode node : ordered) {
            if (node == null || node.getNodeNo() == 1) {
                continue;
            }
            DocumentIntermediateStructureNode parent = draftMap.get(node.getParentNodeNo());
            int newDepth = parent == null ? 1 : parent.getDepth() + 1;
            node.setDepth(newDepth);
        }
    }

    // ========================================================================
    // ⑤ 路径重建
    // ========================================================================

    /**
     * 重建每个节点的规范路径和章节路径：
     * <ul>
     *   <li><b>canonicalPath</b>：父路径 + "/" + pathSegment（如 /document/h1/item-3）</li>
     *   <li><b>sectionPath</b>：章节节点拼接 " &gt; " 面包屑（如 "第一章 &gt; 1.1 背景"），
     *       非章节节点继承父 sectionPath</li>
     * </ul>
     */
    private void rebuildPaths(String documentTitle,
                               Map<Integer, DocumentIntermediateStructureNode> draftMap) {
        for (DocumentIntermediateStructureNode node : draftMap.values()) {
            if (node == null) {
                continue;
            }

            // 根节点
            if (node.getNodeNo() == 1) {
                node.setCanonicalPath(ROOT_CANONICAL_PATH);
                node.setSectionPath("");
                continue;
            }

            DocumentIntermediateStructureNode parent = draftMap.get(node.getParentNodeNo());
            String parentCanonicalPath = parent == null
                    ? ROOT_CANONICAL_PATH
                    : StringUtils.defaultIfBlank(parent.getCanonicalPath(), ROOT_CANONICAL_PATH);
            String parentSectionPath = parent == null
                    ? ""
                    : StringUtils.defaultIfBlank(parent.getSectionPath(), "");

            String segment = buildPathSegment(node);
            node.setCanonicalPath(parentCanonicalPath + PATH_SEPARATOR + segment);

            if (node.isSection()) {
                node.setSectionPath(joinSectionPath(parentSectionPath, displayTitle(node)));
            } else {
                node.setSectionPath(parentSectionPath);
            }
        }
    }

    // ========================================================================
    // ⑥ 兄弟链接重建
    // ========================================================================

    /**
     * 重建兄弟双向链表：将节点按 parentNodeNo 分组，每组内按 logicalLineNo 排序，
     * 为每个节点设置 prevSiblingNodeNo（前一个兄弟）和 nextSiblingNodeNo（后一个兄弟），
     * 首尾分别置 0。同父同深度节点形成双向链表。
     */
    private void rebuildSiblingLinks(Map<Integer, DocumentIntermediateStructureNode> draftMap) {
        // 根节点无兄弟，统一置 0
        DocumentIntermediateStructureNode root = draftMap.get(1);
        if (root != null) {
            root.setPrevSiblingNodeNo(0);
            root.setNextSiblingNodeNo(0);
        }

        // 按 parentNodeNo 分组（跳过根节点）
        Map<Integer, List<DocumentIntermediateStructureNode>> childrenByParent = new LinkedHashMap<>();
        for (DocumentIntermediateStructureNode node : draftMap.values()) {
            if (node == null || node.getNodeNo() == 1) {
                continue;
            }
            Integer parentKey = node.getParentNodeNo() == null ? 1 : node.getParentNodeNo();
            childrenByParent.computeIfAbsent(parentKey, ignored -> new ArrayList<>()).add(node);
        }

        for (List<DocumentIntermediateStructureNode> siblings : childrenByParent.values()) {
            // 按 logicalLineNo 排序（null 排最前）
            siblings.sort(Comparator.comparingInt(
                    n -> n.getLogicalLineNo() == null ? 0 : n.getLogicalLineNo()));

            for (int index = 0; index < siblings.size(); index++) {
                DocumentIntermediateStructureNode current = siblings.get(index);
                current.setPrevSiblingNodeNo(
                        index == 0 ? 0 : siblings.get(index - 1).getNodeNo());
                current.setNextSiblingNodeNo(
                        index == siblings.size() - 1 ? 0 : siblings.get(index + 1).getNodeNo());
            }
        }

        log.debug("兄弟链接重建: {} 个父节点分组", childrenByParent.size());
    }

    // ========================================================================
    // 辅助方法
    // ========================================================================

    /**
     * 标题规范化（用于比较）：去 Markdown # 前缀 → 去扩展名 → 压缩空格 → 小写。
     */
    private static String normalizeComparableTitle(String text) {
        String normalized = StringUtils.defaultIfBlank(text, "").trim();
        if (normalized.isEmpty()) {
            return "";
        }
        return normalized
                .replaceAll(NORMALIZE_STRIP_MARKDOWN_RE, "")
                .replaceAll(NORMALIZE_STRIP_EXT_RE, "")
                .replaceAll(NORMALIZE_COLLAPSE_SPACE_RE, "")
                .toLowerCase();
    }

    /**
     * 数字路径 → 字符串 "1.2.3"。
     */
    private static String numericKey(List<Integer> numericPath) {
        if (numericPath == null || numericPath.isEmpty()) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        for (int index = 0; index < numericPath.size(); index++) {
            if (index > 0) {
                builder.append('.');
            }
            Integer segment = numericPath.get(index);
            if (segment != null) {
                builder.append(segment);
            }
        }
        return builder.toString();
    }

    /**
     * 构建 URL 安全的路径段（保证同父节点下唯一）：
     * <ul>
     *   <li>列表/步骤节点：{@code item-{sequenceNo}}，无序号的用 {@code item-{nodeNo}}</li>
     *   <li>章节节点（有编号）：{@code {slug(code)}-{nodeNo}}</li>
     *   <li>章节节点（无编号）：{@code section-{nodeNo}}</li>
     * </ul>
     * <p>所有路径段天然唯一 —— 章节通过 nodeNo 后缀，列表通过 sequenceNo / nodeNo。</p>
     */
    private String buildPathSegment(DocumentIntermediateStructureNode node) {
        if (node == null) {
            return NODE_FALLBACK + "-0";
        }
        // 列表/步骤：item-{sequenceNo}，无序号回退到 item-{nodeNo}
        if (node.isListLike()) {
            if (node.getSequenceNo() != null && node.getSequenceNo() > 0) {
                return ITEM_PREFIX + node.getSequenceNo();
            }
            return ITEM_PREFIX + node.getNodeNo();
        }
        // 章节：code slug + nodeNo 后缀，保证同父下绝对唯一
        String code = StringUtils.defaultIfBlank(node.getNodeCode(), "").trim();
        if (StringUtils.isNotBlank(code)) {
            return slug(code) + "-" + node.getNodeNo();
        }
        return "section-" + node.getNodeNo();
    }

    /**
     * 组装人类可读的展示标题：nodeCode + title。
     * 如果 title 已包含 code 前缀则不重复拼接。
     */
    private String displayTitle(DocumentIntermediateStructureNode node) {
        String code = StringUtils.defaultIfBlank(node.getNodeCode(), "").trim();
        String title = StringUtils.defaultIfBlank(node.getTitle(), "").trim();
        if (code.isEmpty()) {
            return title;
        }
        if (title.startsWith(code)) {
            return title;
        }
        return code + " " + title;
    }

    /**
     * URL 安全 slug 化：空白→连字符，过滤非法字符，兜底返回 "node"。
     */
    private String slug(String value) {
        String normalized = StringUtils.defaultIfBlank(value, "").trim();
        if (normalized.isEmpty()) {
            return NODE_FALLBACK;
        }
        String result = normalized
                .replaceAll(SLUG_WHITESPACE_RE, "-")
                .replaceAll(SLUG_KEEP_RE, "");
        return result.isEmpty() ? NODE_FALLBACK : result;
    }

    /**
     * 拼接章节路径面包屑。
     */
    private static String joinSectionPath(String parentSectionPath, String currentTitle) {
        if (StringUtils.isBlank(parentSectionPath)) {
            return StringUtils.defaultIfBlank(currentTitle, "");
        }
        if (StringUtils.isBlank(currentTitle)) {
            return parentSectionPath;
        }
        return parentSectionPath + SECTION_PATH_SEPARATOR + currentTitle;
    }
}
