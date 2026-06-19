package com.reubenagent.document.support;

import com.reubenagent.document.enums.DocumentStructureNodeSignalEnum;
import com.reubenagent.document.enums.DocumentStructureNodeTypeEnum;
import com.reubenagent.document.model.DocumentIntermediateStructureNode;
import com.reubenagent.document.model.DocumentStructureNodeSignal;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 文档结构层级解析器 —— Stage 3：将扁平信号列表转化为具有父子层级关系的树形草稿。
 *
 * <p>位于 LLM 歧义消解（Stage 2）之后，树验证（Stage 4）之前：</p>
 * <pre>
 *   DocumentStructureNodeAmbiguityResolver → DocumentStructureHierarchyResolver → DocumentStructureTreeValidator
 * </pre>
 *
 * <h3>核心数据结构</h3>
 * <ul>
 *   <li>{@code currentSection} —— 当前所在的章节节点（生命周期内永不为 null，初始=root），正文追加到这里</li>
 *   <li>{@code currentListItem} —— 当前所在的列表项节点，正文同时追加到列表项和它所属的 section</li>
 *   <li>{@code listStack} —— 列表缩进栈（{@code Deque<ListContext>}），通过缩进级别判断嵌套列表的父子关系</li>
 *   <li>{@code latestHeadingByDepth} —— 各深度最近遇到的标题 nodeNo，用于确定后续同级标题的父节点</li>
 *   <li>{@code latestHeadingByNumericPath} —— 数字路径（如 "1.2.3"）→ nodeNo 的映射，用于精确匹配父标题</li>
 *   <li>{@code nodeMap} —— nodeNo → 节点映射，O(1) 查找（优化 super-agent 的 O(n) {@code findByNodeNo}）</li>
 * </ul>
 *
 * <h3>信号分派逻辑</h3>
 * <pre>
 *   BLANK          → 清空列表上下文栈（空白行分隔列表组）
 *   NOISE          → 跳过
 *   BODY/TABLE/QUOTE → 追加到 currentListItem（优先）或 currentSection
 *   LIST_ITEM/STEP → 创建列表节点，按缩进栈确定父节点
 *   HEADING        → 创建章节节点，按数字路径/Markdown层级/深度确定父节点
 * </pre>
 *
 * <h3>标题层级解析策略</h3>
 * <p>标题的深度和父节点通过以下策略确定：</p>
 * <ul>
 *   <li><b>Markdown 体系</b>：直接使用 # 的数量作为层级深度</li>
 *   <li><b>中文章节/附录体系</b>：固定为深度1，父节点为根节点</li>
 *   <li><b>数字多级体系</b>（如 1.2.3）：通过 numericPath 查找父节点（如 "1.2.3" 的父为 "1.2"）</li>
 *   <li><b>其他体系</b>：使用 signal 的 levelHint 或默认为1</li>
 * </ul>
 *
 * @author reuben
 * @since 2026-06-19
 */
@Slf4j
@Component
public class DocumentStructureHierarchyResolver {

    // ========================================================================
    // 标题体系常量（signal.reasons 中的识别标记）
    // ========================================================================

    static final String REASON_MARKDOWN = "markdown-heading";
    static final String REASON_CHAPTER = "chapter-heading";
    static final String REASON_APPENDIX = "appendix-heading";
    static final String REASON_DECIMAL = "decimal-heading";
    static final String REASON_SINGLE_DIGIT = "single-digit-ambiguous-heading";

    static final String FAMILY_MARKDOWN = "markdown";
    static final String FAMILY_CHAPTER = "chapter";
    static final String FAMILY_APPENDIX = "appendix";
    static final String FAMILY_DECIMAL = "decimal";
    static final String FAMILY_PLAIN = "plain";
    static final String FAMILY_STEP = "step";
    static final String FAMILY_LIST = "list";
    static final String FAMILY_DOCUMENT = "document";

    // ========================================================================
    // 入口
    // ========================================================================

    /**
     * 层级解析入口 —— 把平铺信号列表组装成树形草稿。
     *
     * @param documentTitle 文档标题
     * @param signals       Stage 2 消歧后的确定信号列表（不含 DOCUMENT_TITLE 虚拟信号）
     * @return 带暂定 parentNodeNo 和 depth 的节点列表（按 nodeNo 升序）
     */
    public List<DocumentIntermediateStructureNode> resolve(
            String documentTitle,
            List<DocumentStructureNodeSignal> signals) {

        log.debug("Stage 3 层级解析开始: {} 个信号", signals.size());
        List<DocumentIntermediateStructureNode> drafts = new ArrayList<>();
        Map<Integer, DocumentIntermediateStructureNode> nodeMap = new LinkedHashMap<>();

        // —— 根节点：nodeNo=1, depth=0 ——
        DocumentIntermediateStructureNode root = DocumentIntermediateStructureNode.builder()
                .nodeNo(1)
                .nodeType(DocumentStructureNodeTypeEnum.ROOT.getCode())
                .depth(0)
                .nodeCode("")
                .title(StringUtils.defaultIfBlank(documentTitle, "文档"))
                .anchorText(StringUtils.defaultIfBlank(documentTitle, "文档"))
                .canonicalPath("/document")
                .sectionPath("")
                .sourceFamily(FAMILY_DOCUMENT)
                .confidence(1.0D)
                .build();
        drafts.add(root);
        nodeMap.put(1, root);

        int nextNodeNo = 2;
        // currentSection 生命周期内永不为 null（初始=root，后续仅被 HEADING 信号更新）
        DocumentIntermediateStructureNode currentSection = root;
        DocumentIntermediateStructureNode currentListItem = null;
        Deque<ListContext> listStack = new ArrayDeque<>();
        Map<Integer, Integer> latestHeadingByDepth = new LinkedHashMap<>();
        Map<String, Integer> latestHeadingByNumericPath = new LinkedHashMap<>();

        for (DocumentStructureNodeSignal signal : signals) {
            if (signal == null || signal.getLogicalLineNo() == 0) {
                continue;
            }
            DocumentStructureNodeSignalEnum kind = signal.getKind();

            if (kind == DocumentStructureNodeSignalEnum.BLANK) {
                currentListItem = null;
                listStack.clear();

            } else if (kind == DocumentStructureNodeSignalEnum.NOISE) {
                // 噪声行跳过

            } else if (kind == DocumentStructureNodeSignalEnum.TABLE_ROW
                    || kind == DocumentStructureNodeSignalEnum.QUOTE
                    || kind == DocumentStructureNodeSignalEnum.BODY) {
                appendBody(signal, currentSection, currentListItem);

            } else if (kind == DocumentStructureNodeSignalEnum.STEP_ITEM
                    || kind == DocumentStructureNodeSignalEnum.LIST_ITEM) {
                DocumentIntermediateStructureNode listParent =
                        resolveListParent(signal, currentSection, listStack);
                DocumentIntermediateStructureNode listNode =
                        buildListNode(signal, nextNodeNo++, listParent);
                drafts.add(listNode);
                nodeMap.put(listNode.getNodeNo(), listNode);
                currentListItem = listNode;
                registerListContext(signal, listNode, listStack);
                currentSection.appendLine(signal.getTrimmedText());

            } else if (kind == DocumentStructureNodeSignalEnum.HEADING
                    || kind == DocumentStructureNodeSignalEnum.HEADING_CANDIDATE) {
                DocumentIntermediateStructureNode headingNode = buildHeadingNode(
                        signal, nextNodeNo++, nodeMap, latestHeadingByDepth, latestHeadingByNumericPath);
                drafts.add(headingNode);
                nodeMap.put(headingNode.getNodeNo(), headingNode);
                currentSection = headingNode;
                currentListItem = null;
                listStack.clear();

            } else {
                appendBody(signal, currentSection, currentListItem);
            }
        }

        drafts.sort(Comparator.comparingInt(DocumentIntermediateStructureNode::getNodeNo));
        log.debug("层级解析完成: {} 个信号 → {} 个节点", signals.size(), drafts.size());
        return drafts;
    }

    // ========================================================================
    // 正文追加
    // ========================================================================

    /**
     * 正文追加策略：currentListItem（优先）→ currentSection。
     * 列表项正文同时追加到所属 section，确保章节 contentText 包含子列表内容。
     */
    private void appendBody(DocumentStructureNodeSignal signal,
                            DocumentIntermediateStructureNode currentSection,
                            DocumentIntermediateStructureNode currentListItem) {
        String line = signal == null ? "" : signal.getTrimmedText();
        if (StringUtils.isBlank(line)) {
            return;
        }
        DocumentIntermediateStructureNode target =
                currentListItem != null ? currentListItem : currentSection;
        target.appendLine(line);

        // 列表项正文同步写入所属 section
        if (currentListItem != null
                && !currentSection.getNodeNo().equals(currentListItem.getNodeNo())) {
            currentSection.appendLine(line);
        }
    }

    // ========================================================================
    // 列表节点构建
    // ========================================================================

    private DocumentIntermediateStructureNode buildListNode(
            DocumentStructureNodeSignal signal,
            int nodeNo,
            DocumentIntermediateStructureNode parent) {

        boolean isStep = signal.getKind() == DocumentStructureNodeSignalEnum.STEP_ITEM;
        DocumentIntermediateStructureNode node = DocumentIntermediateStructureNode.builder()
                .nodeNo(nodeNo)
                .logicalLineNo(signal.getLogicalLineNo())
                .nodeType(isStep
                        ? DocumentStructureNodeTypeEnum.STEP.getCode()
                        : DocumentStructureNodeTypeEnum.LIST_ITEM.getCode())
                .parentNodeNo(parent == null ? 1 : parent.getNodeNo())
                .depth((parent == null ? 0 : parent.getDepth()) + 1)
                .nodeCode(StringUtils.defaultIfBlank(signal.getHeadingCode(),
                        signal.getSequenceNo() == null ? "" : String.valueOf(signal.getSequenceNo())))
                .title(signal.getTitle())
                .anchorText(StringUtils.defaultIfBlank(signal.getTrimmedText(), signal.getTitle()))
                .sequenceNo(signal.getSequenceNo())
                .sourceFamily(isStep ? FAMILY_STEP : FAMILY_LIST)
                .confidence(signal.getConfidence())
                .build();
        node.appendLine(signal.getTrimmedText());
        return node;
    }

    /**
     * 按缩进栈确定列表项父节点：弹栈直到栈顶缩进 &lt; 当前缩进 →
     * 栈不空且缩进更深 → 父=栈顶（嵌套子列表）；否则 → 父=当前 section（同级新列表）。
     */
    private DocumentIntermediateStructureNode resolveListParent(
            DocumentStructureNodeSignal signal,
            DocumentIntermediateStructureNode currentSection,
            Deque<ListContext> listStack) {

        int indentLevel = safeIndentLevel(signal);
        while (!listStack.isEmpty() && listStack.peekLast().indentLevel() >= indentLevel) {
            listStack.removeLast();
        }
        if (!listStack.isEmpty() && indentLevel > listStack.peekLast().indentLevel()) {
            return listStack.peekLast().node();
        }
        return currentSection;
    }

    private void registerListContext(DocumentStructureNodeSignal signal,
                                     DocumentIntermediateStructureNode listNode,
                                     Deque<ListContext> listStack) {
        int indentLevel = safeIndentLevel(signal);
        while (!listStack.isEmpty() && listStack.peekLast().indentLevel() >= indentLevel) {
            listStack.removeLast();
        }
        listStack.addLast(new ListContext(listNode, indentLevel));
    }

    // ========================================================================
    // 标题节点构建
    // ========================================================================

    /**
     * 构建标题节点草稿 —— 按标题体系确定深度和父节点，更新 latestHeading 映射表。
     */
    private DocumentIntermediateStructureNode buildHeadingNode(
            DocumentStructureNodeSignal signal,
            int nodeNo,
            Map<Integer, DocumentIntermediateStructureNode> nodeMap,
            Map<Integer, Integer> latestHeadingByDepth,
            Map<String, Integer> latestHeadingByNumericPath) {

        int depth = resolveHeadingDepth(signal, nodeMap, latestHeadingByDepth, latestHeadingByNumericPath);
        Integer parentNodeNo = resolveHeadingParentNodeNo(
                signal, depth, nodeMap, latestHeadingByDepth, latestHeadingByNumericPath);

        DocumentIntermediateStructureNode node = DocumentIntermediateStructureNode.builder()
                .nodeNo(nodeNo)
                .logicalLineNo(signal.getLogicalLineNo())
                .nodeType(DocumentStructureNodeTypeEnum.CHAPTER.getCode())
                .parentNodeNo(parentNodeNo)
                .depth(depth)
                .nodeCode(StringUtils.defaultIfBlank(signal.getHeadingCode(), ""))
                .title(signal.getTitle())
                .anchorText(buildHeadingAnchorText(signal))
                .numericPath(signal.getNumericPath() == null
                        ? new ArrayList<>() : new ArrayList<>(signal.getNumericPath()))
                .sourceFamily(resolveHeadingFamily(signal))
                .confidence(signal.getConfidence())
                .build();
        node.appendLine(signal.getTrimmedText());

        // 更新 latestHeadingByDepth：清除 >= 当前深度的旧记录，写入新记录
        latestHeadingByDepth.entrySet().removeIf(e -> e.getKey() >= depth);
        latestHeadingByDepth.put(depth, nodeNo);

        // 更新 latestHeadingByNumericPath
        String numericKey = numericKey(node.getNumericPath());
        if (StringUtils.isNotBlank(numericKey)) {
            latestHeadingByNumericPath.put(numericKey, nodeNo);
        }
        return node;
    }

    private int resolveHeadingDepth(
            DocumentStructureNodeSignal signal,
            Map<Integer, DocumentIntermediateStructureNode> nodeMap,
            Map<Integer, Integer> latestHeadingByDepth,
            Map<String, Integer> latestHeadingByNumericPath) {

        String family = resolveHeadingFamily(signal);
        List<Integer> numericPath = signal.getNumericPath() == null
                ? List.of() : signal.getNumericPath();

        if (FAMILY_MARKDOWN.equals(family)) {
            return Math.max(1, safeLevel(signal.getLevelHint(), 1));
        }
        if (FAMILY_CHAPTER.equals(family) || FAMILY_APPENDIX.equals(family)) {
            return 1;
        }
        if (FAMILY_DECIMAL.equals(family)) {
            if (numericPath.size() <= 1) {
                return 1;
            }
            // 尝试找到直接父标题（如 "1.2.3" → "1.2"）
            String parentKey = numericKey(numericPath.subList(0, numericPath.size() - 1));
            Integer parentNodeNo = latestHeadingByNumericPath.get(parentKey);
            if (parentNodeNo != null) {
                DocumentIntermediateStructureNode parent = nodeMap.get(parentNodeNo);
                if (parent != null) {
                    return parent.getDepth() + 1;
                }
            }
            // 回退到章节级父标题（如 "1.2.3" → "1"）
            String chapterKey = numericKey(List.of(numericPath.get(0)));
            Integer chapterParentNo = latestHeadingByNumericPath.get(chapterKey);
            if (chapterParentNo != null) {
                DocumentIntermediateStructureNode parent = nodeMap.get(chapterParentNo);
                if (parent != null) {
                    return parent.getDepth() + 1;
                }
            }
            return numericPath.size();
        }
        // plain 等兜底
        return Math.max(1, safeLevel(signal.getLevelHint(), 1));
    }

    private Integer resolveHeadingParentNodeNo(
            DocumentStructureNodeSignal signal,
            int depth,
            Map<Integer, DocumentIntermediateStructureNode> nodeMap,
            Map<Integer, Integer> latestHeadingByDepth,
            Map<String, Integer> latestHeadingByNumericPath) {

        String family = resolveHeadingFamily(signal);
        List<Integer> numericPath = signal.getNumericPath() == null
                ? List.of() : signal.getNumericPath();

        // 章节/附录直接挂根
        if (FAMILY_CHAPTER.equals(family) || FAMILY_APPENDIX.equals(family)) {
            return 1;
        }
        // 数字多级体系：尝试精确父匹配
        if (FAMILY_DECIMAL.equals(family) && numericPath.size() > 1) {
            String exactParentKey = numericKey(numericPath.subList(0, numericPath.size() - 1));
            Integer exactParent = latestHeadingByNumericPath.get(exactParentKey);
            if (exactParent != null) {
                return exactParent;
            }
            String chapterKey = numericKey(List.of(numericPath.get(0)));
            Integer chapterParent = latestHeadingByNumericPath.get(chapterKey);
            if (chapterParent != null) {
                return chapterParent;
            }
        }
        // 通用回退：按深度向上找最近标题
        return findNearestParentByDepth(depth, latestHeadingByDepth);
    }

    /**
     * 从当前深度向上找最近的标题节点作为父节点。
     * 从 depth-1 递减到 1，返回第一个匹配的 nodeNo；都没有则返回根节点(1)。
     */
    private Integer findNearestParentByDepth(int depth,
                                              Map<Integer, Integer> latestHeadingByDepth) {
        for (int candidateDepth = depth - 1; candidateDepth >= 1; candidateDepth--) {
            Integer parentNodeNo = latestHeadingByDepth.get(candidateDepth);
            if (parentNodeNo != null) {
                return parentNodeNo;
            }
        }
        return 1;
    }

    // ========================================================================
    // 标题体系判断
    // ========================================================================

    /**
     * 从 signal.reasons 解析标题体系。
     *
     * <p>各类 reason 由 {@code DocumentStructureNodeSignalExtractor} 设置：</p>
     * <ul>
     *   <li>{@code markdown-heading} → markdown</li>
     *   <li>{@code chapter-heading} → chapter</li>
     *   <li>{@code appendix-heading} → appendix</li>
     *   <li>{@code decimal-heading} / {@code single-digit-ambiguous-heading} → decimal</li>
     *   <li>其他 → plain</li>
     * </ul>
     */
    private String resolveHeadingFamily(DocumentStructureNodeSignal signal) {
        if (signal == null || signal.getReasons() == null) {
            return FAMILY_PLAIN;
        }
        List<String> reasons = signal.getReasons();
        if (reasons.contains(REASON_MARKDOWN)) {
            return FAMILY_MARKDOWN;
        }
        if (reasons.contains(REASON_CHAPTER)) {
            return FAMILY_CHAPTER;
        }
        if (reasons.contains(REASON_APPENDIX)) {
            return FAMILY_APPENDIX;
        }
        if (reasons.contains(REASON_DECIMAL) || reasons.contains(REASON_SINGLE_DIGIT)) {
            return FAMILY_DECIMAL;
        }
        return FAMILY_PLAIN;
    }

    /**
     * 组装锚点文本：nodeCode + title。
     * 如果 title 已包含 code 前缀则不重复拼接。
     */
    private String buildHeadingAnchorText(DocumentStructureNodeSignal signal) {
        String code = StringUtils.defaultIfBlank(signal.getHeadingCode(), "").trim();
        String title = StringUtils.defaultIfBlank(signal.getTitle(), "").trim();
        if (code.isEmpty()) {
            return title;
        }
        if (title.startsWith(code)) {
            return title;
        }
        return code + " " + title;
    }

    // ========================================================================
    // 工具方法
    // ========================================================================

    /** 数字路径 → 字符串 "1.2.3" */
    static String numericKey(List<Integer> numericPath) {
        if (numericPath == null || numericPath.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < numericPath.size(); i++) {
            if (i > 0) {
                sb.append('.');
            }
            Integer segment = numericPath.get(i);
            if (segment != null) {
                sb.append(segment);
            }
        }
        return sb.toString();
    }

    private static int safeLevel(Integer levelHint, int defaultValue) {
        return levelHint == null || levelHint <= 0 ? defaultValue : levelHint;
    }

    private static int safeIndentLevel(DocumentStructureNodeSignal signal) {
        if (signal == null || signal.getIndentLevel() == null || signal.getIndentLevel() < 0) {
            return 0;
        }
        return signal.getIndentLevel();
    }

    // ========================================================================
    // 列表缩进栈记录
    // ========================================================================

    /**
     * 列表缩进上下文 —— 记录一个列表项节点及其缩进级别。
     */
    private record ListContext(
            DocumentIntermediateStructureNode node,
            int indentLevel
    ) {
    }
}
