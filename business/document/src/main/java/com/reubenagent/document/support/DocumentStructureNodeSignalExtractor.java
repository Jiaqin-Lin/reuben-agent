package com.reubenagent.document.support;

import com.reubenagent.document.enums.DocumentStructureNodeSignalEnum;
import com.reubenagent.document.model.DocumentStructureNodeLogicalLine;
import com.reubenagent.document.model.DocumentStructureNodeSignal;
import com.reubenagent.document.model.DocumentStructureNodeSignalBatch;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 文档结构信号提取器 —— 将纯文本逐行分类为结构信号（HEADING / LIST_ITEM / BODY / NOISE 等）。
 *
 * <p>处理管线：拆行 → 频率统计 → 16 级优先级分类 → 输出 {@link DocumentStructureNodeSignalBatch}。</p>
 *
 * @author reuben
 * @since 2026-06-14
 */
@Slf4j
@Component
public class DocumentStructureNodeSignalExtractor {

    // ============ 正则 Pattern 常量 ============

    /**
     * 步骤/序号边界切分正则 —— 零宽正向先行断言（lookahead）匹配步骤标记的起始位置，
     * 用于将含有多步骤的压缩行切分为独立 segment。
     *
     * <h4>匹配模式</h4>
     * <ul>
     *   <li><b>中文步骤</b>：{@code 第1步}、{@code 步骤二}</li>
     *   <li><b>英文步骤</b>：{@code Step 1}、{@code STEP A}</li>
     *   <li><b>全角括号</b>：{@code （1）}、{@code （三）}</li>
     *   <li><b>半角括号</b>：{@code (1)}、{@code (a)}</li>
     *   <li><b>右括号序号</b>：{@code 1)}、{@code a)}</li>
     *   <li><b>中文序号</b>：{@code 一、}、{@code 二十三.}（前面不能是汉字）</li>
     *   <li><b>数字序号</b>：{@code 1.}、{@code 12、}（前面不能是数字或点）</li>
     *   <li><b>字母/罗马序号</b>：{@code A)}、{@code ii.}（前面不能是字母，不含 {@code 、}）</li>
     * </ul>
     *
     * <h4>前置禁止（negative lookbehind）</h4>
     * <p>中文序号前面不能是汉字，数字序号前面不能是数字或点，字母序号前面不能是字母，
     * 防止正文中的序号引用被误切分。</p>
     */
    private static final Pattern STEP_BOUNDARY_PATTERN = Pattern.compile(
            "(?=" +
                    // ── 中文步骤："第N步" / "步骤N" ──
                    "(?:第\\s*(?:[0-9]+|(?:[零一二三四五六七八九十百千万两]\\s*)+)\\s*步" +
                    "|步骤\\s*(?:[0-9]+|(?:[零一二三四五六七八九十百千万两]\\s*)+))" +
                    "\\s*[:：、.，。；;\\-—]?" +
                    "|" +
                    // ── 英文步骤："Step N" / "STEP A" ──
                    "(?:[Ss][Tt][Ee][Pp]\\s+(?:[0-9]+|[A-Za-z]+))" +
                    "\\s*[:：、.，。；;\\-—]?" +
                    "|" +
                    // ── 全角括号：（1）（三）（a） ──
                    "（\\s*(?:[0-9]+|[零一二三四五六七八九十百千万两]+|[a-zA-Z])\\s*）" +
                    "|" +
                    // ── 半角括号：(1) (a) ──
                    "\\(\\s*(?:[0-9]+|[零一二三四五六七八九十百千万两]+|[a-zA-Z])\\s*\\)" +
                    "|" +
                    // ── 右括号序号：1) a) （前面不能是左括号，防止 (2) 被切分为 ( + 2)）──
                    "(?<!\\()(?:[0-9]+|[零一二三四五六七八九十百千万两]+|[a-zA-Z]+)\\s*\\)" +
                    "|" +
                    // ── 中文序号：一、 二十三. （前面不能是汉字） ──
                    "(?<![一-鿿])[零一二三四五六七八九十百千万两]+\\s*[、.]" +
                    "|" +
                    // ── 数字序号：1. 12、 （前面不能是数字、点或版本号前缀 V/v） ──
                    "(?<![\\d.Vv])[0-9]+\\s*[、.]" +
                    "|" +
                    // ── 字母/罗马序号：A) ii. （前面不能是字母；不含 、—字母+顿号不是合法序号标记） ──
                    "(?<![a-zA-Z])[a-zA-Z]+\\s*[.)]" +
                    ")"
    );

    /** 版权/保密声明检测：匹配 "版权所有"、"confidential"、"内部使用" 等关键词 */
    private static final Pattern COPYRIGHT_PATTERN = Pattern.compile(
            ".*(?:版权所有|未经授权|内部使用|copyright|all rights reserved|保密).*",
            Pattern.CASE_INSENSITIVE);

    /** 版本/修订号检测：匹配 "V1.2.3"、"版本 2"、"Rev. 3" 等模式 */
    private static final Pattern VERSION_PATTERN = Pattern.compile(
            ".*(?:\\bV\\d+(?:\\.\\d+)*\\b|版本|修订|Rev\\.?\\s*\\d+).*",
            Pattern.CASE_INSENSITIVE);

    /** 页码检测：匹配 "第1页"、"Page 5"、"3/10" 等孤立页码行 */
    private static final Pattern PAGE_PATTERN = Pattern.compile(
            "^(?:第\\s*\\d+\\s*页|Page\\s*\\d+|\\d+\\s*/\\s*\\d+)$",
            Pattern.CASE_INSENSITIVE);

    /**
     * Markdown 标题：匹配 {@code # title} ~ {@code ###### title}。
     * <p>group(1) = # 号序列，group(2) = 标题文本</p>
     */
    private static final Pattern MARKDOWN_HEADING_PATTERN = Pattern.compile("^(#{1,6})\\s+(.+)$");

    /**
     * 多级数字编号标题：匹配 {@code 1.2.3 title}、{@code 3.1、标题}。
     * <p>group(1) = 编号序列（如 "1.2.3"），group(2) = 标题文本</p>
     */
    private static final Pattern DECIMAL_HEADING_PATTERN = Pattern.compile("^(\\d+(?:\\.\\d+)+)\\s*[、.]?\\s*(.+)$");

    /**
     * 单数字空格标题：匹配 {@code 1 系统架构设计}、{@code 12 附录说明}。
     * <p>数字后跟空格（而非 {@code 、} 或 {@code .}），需要通过上下文确认不是正文。</p>
     * <p>group(1) = 数字，group(2) = 标题文本</p>
     */
    private static final Pattern SINGLE_NUMBER_SPACE_HEADING_PATTERN = Pattern.compile("^(\\d{1,2})\\s+(.+)$");

    /**
     * 中文章节标题：匹配 {@code 第一章 概述}、{@code 第3条 说明}、{@code 第二部分 详情}。
     * <p>字符类 {@code [章节条部分]} 覆盖常见中文结构标记。group(1) = 完整编码（如 "第一章"），
     * group(2) = 数字部分（用于解析序号），group(3) = 标题文本</p>
     */
    private static final Pattern CHAPTER_PATTERN = Pattern.compile("^(第([一二三四五六七八九十百\\d]+)[章节条部分])\\s*(.+)$");

    /**
     * 附录标题：匹配 {@code 附录A}、{@code 附录 二 补充说明}。
     * <p>group(1) = 附录编码（如 "附录A"），group(2) = 字母/数字部分，group(3) = 标题文本（可选）</p>
     */
    private static final Pattern APPENDIX_PATTERN = Pattern.compile("^(附录\\s*([A-Za-z一二三四五六七八九十百\\d]+))(?:\\s+(.+))?$");

    /**
     * 显式步骤标记：匹配 {@code 第1步 操作}、{@code 步骤三：说明}、{@code Step 2 Start}。
     * <p>group(1) = "第N步" 中的 N，group(2) = "步骤N" 中的 N，group(3) = "Step N" 中的 N，group(4) = 步骤内容</p>
     */
    private static final Pattern EXPLICIT_STEP_PATTERN = Pattern.compile(
            "^(?:第\\s*([0-9一二三四五六七八九十百]+)\\s*步" +
            "|步骤\\s*([0-9一二三四五六七八九十百]+)" +
            "|[Ss][Tt][Ee][Pp]\\s+([0-9]+))" +
            "\\s*[:：、.]?\\s*(.+)$");

    /**
     * 单级数字编号：匹配 {@code 1. 标题}、{@code 12、内容}。
     * <p>group(1) = 数字，group(2) = 后续文本</p>
     */
    private static final Pattern SINGLE_LEVEL_DIGIT_PATTERN = Pattern.compile("^(\\d+)\\s*[、.]\\s*(.+)$");

    /**
     * 中文序号：匹配 {@code 一、概述}、{@code 二十三. 内容}。
     * <p>group(1) = 中文数字，group(2) = 后续文本</p>
     */
    private static final Pattern CHINESE_OUTLINE_PATTERN = Pattern.compile("^([一二三四五六七八九十百]+)[、.]\\s*(.+)$");

    /**
     * 无序列表：匹配 {@code - item}、{@code * item}、{@code + item}、{@code • item}。
     * <p>group(1) = 标记符号，group(2) = 列表项文本</p>
     */
    private static final Pattern BULLET_PATTERN = Pattern.compile("^([-*+•])\\s+(.+)$");

    /**
     * 复选框列表：匹配 {@code [ ] todo}、{@code [x] done}、{@code [X] done}。
     * <p>group(1) = 复选框后的文本</p>
     */
    private static final Pattern CHECKBOX_PATTERN = Pattern.compile("^\\[(?: |x|X)]\\s+(.+)$");

    /** 表格分隔符：用于统计管道符数量判断是否为表格行 */
    private static final Pattern TABLE_SPLIT_PATTERN = Pattern.compile("\\|");

    /** Markdown 链接中包含管道符 — 排除表格误判 */
    private static final Pattern MARKDOWN_LINK_WITH_PIPE = Pattern.compile(".*\\[.*\\]\\(.*\\|.*\\).*");

    /** 表格纯分隔线（: - 空格 |） */
    private static final Pattern TABLE_SEPARATOR_LINE = Pattern.compile("^[:\\-\\s|]+$");

    /** 半角括号编号：(1) 启动服务、(a) 配置项 */
    private static final Pattern PAREN_NUMBER_PATTERN = Pattern.compile("^\\(\\s*([0-9]+|[a-zA-Z])\\s*\\)\\s*(.+)$");

    /** 右括号编号：1) 启动服务、a) 配置项 */
    private static final Pattern RIGHT_PAREN_NUMBER_PATTERN = Pattern.compile("^([0-9]+|[a-zA-Z])\\s*\\)\\s*(.+)$");

    /** 朴素标题排除 —— 纯分隔线（=== 或 ---） */
    private static final Pattern PLAIN_HEADING_RULE = Pattern.compile("^[\\-=_]{3,}$");

    // ============ 内部类型 ============

    /**
     * 有序列表标记族 —— 区分数字序号和中文序号，用于邻接序列判定。
     */
    private enum OrderedMarkerFamily {
        /** 阿拉伯数字序号（1. 2. 3. ...） */
        ARABIC_SINGLE,
        /** 中文序号（一、二、三、...） */
        CHINESE_OUTLINE
    }

    /**
     * 逻辑行上下文 —— 当前行的前后非空行信息，用于标题/列表的上下文判定。
     *
     * @param preNotBlankLine   前一个非空白行（可能为 null）
     * @param nextNotBlankLine  后一个非空白行（可能为 null）
     * @param precededByBlank   前面是否隔着空白行
     * @param followedByBlank   后面是否隔着空白行
     */
    private record LogicalLineContext(
            DocumentStructureNodeLogicalLine preNotBlankLine,
            DocumentStructureNodeLogicalLine nextNotBlankLine,
            boolean precededByBlank,
            boolean followedByBlank
    ) { }

    // ============ 公开方法 ============

    /**
     * 从文档纯文本中提取结构信号。
     *
     * @param documentTitle 文档标题（用于去重检测）
     * @param parsedText    Tika 提取后的纯文本内容
     * @return 信号批量结果，包含全文行列表和逐行分类信号
     */
    public DocumentStructureNodeSignalBatch extract(String documentTitle, String parsedText) {
        List<DocumentStructureNodeLogicalLine> logicalLines = buildLogicalLines(parsedText);
        log.debug("Stage 1 信号提取开始: {} 个逻辑行", logicalLines.size());

        Map<String, Integer> logicalLineFrequencyMap = countLogicalLineFrequency(logicalLines);
        List<LogicalLineContext> contextList = buildLogicalLineContexts(logicalLines);

        List<DocumentStructureNodeSignal> signals = new ArrayList<>(logicalLines.size() + 1);
        // 第 0 条：虚拟的文档标题信号
        signals.add(DocumentStructureNodeSignal.builder()
                .logicalLineNo(0)
                .rawText(documentTitle)
                .trimmedText(documentTitle)
                .kind(DocumentStructureNodeSignalEnum.DOCUMENT_TITLE)
                .headingCode("")
                .title(documentTitle)
                .levelHint(0)
                .indentLevel(0)
                .sequenceNo(null)
                .confidence(1.0D)
                .build());

        for (int index = 0; index < logicalLines.size(); index++) {
            DocumentStructureNodeLogicalLine logicalLine = logicalLines.get(index);
            LogicalLineContext logicalLineContext = contextList.get(index);
            signals.add(classifySignal(documentTitle, logicalLine, logicalLineContext, logicalLineFrequencyMap));
        }

        List<String> contextLines = logicalLines.stream()
                .map(DocumentStructureNodeLogicalLine::trimmedText)
                .toList();

        long headingCount = signals.stream().filter(DocumentStructureNodeSignal::isHeadingLike).count();
        log.debug("Stage 1 信号提取完成: {} 个信号 ({} 个标题型)", signals.size(), headingCount);
        return new DocumentStructureNodeSignalBatch(contextLines, signals);
    }

    // ============ 核心分类逻辑 ============

    /**
     * 对单条逻辑行进行信号分类。
     *
     * <p>按优先级依次尝试 16 条规则，首次命中即返回。每步都构造完整的 {@link DocumentStructureNodeSignal}，
     * 包含分类理由（reasons）和置信度（confidence），供后续 LLM 二次判定使用。</p>
     *
     * @param documentTitle           文档标题（用于去重）
     * @param logicalLine             当前逻辑行
     * @param logicalLineContext      前后文上下文
     * @param logicalLineFrequencyMap 行频率统计（用于噪声检测）
     * @return 分类后的信号对象
     */
    private DocumentStructureNodeSignal classifySignal(String documentTitle,
                                                       DocumentStructureNodeLogicalLine logicalLine,
                                                       LogicalLineContext logicalLineContext,
                                                       Map<String, Integer> logicalLineFrequencyMap) {
        int logicalLineNo = logicalLine.logicalLineNo();
        String rawText = logicalLine.rawText();
        String trimmedText = logicalLine.trimmedText();
        int indent = logicalLine.indentLevel();
        int frequency = logicalLineFrequencyMap.getOrDefault(logicalLine.trimmedText(), 0);

        // (1) 空白行
        if (StringUtils.isBlank(trimmedText)) {
            return buildSignal(logicalLineNo, rawText, trimmedText,
                    DocumentStructureNodeSignalEnum.BLANK, "", null, 1.00D, List.of("blank"), 0);
        }

        // (2) 重复噪声
        if (isRepeatedNoiseOrFooter(documentTitle, trimmedText, frequency)) {
            return buildSignal(logicalLineNo, rawText, trimmedText,
                    DocumentStructureNodeSignalEnum.NOISE, "", null, 0.99D,
                    List.of("page-header-footer-copyright-version-noise"), 0);
        }

        // (3) 页码
        if (PAGE_PATTERN.matcher(trimmedText).matches()) {
            return buildSignal(logicalLineNo, rawText, trimmedText,
                    DocumentStructureNodeSignalEnum.NOISE, "", null, 0.99D,
                    List.of("page-number-noise"), 0);
        }

        // (3.5) 纯分隔线 / 格式化符号（--- === ___ 等，不含表格管道符）
        if (!trimmedText.contains("|") && PLAIN_HEADING_RULE.matcher(trimmedText).matches()) {
            return buildSignal(logicalLineNo, rawText, trimmedText,
                    DocumentStructureNodeSignalEnum.NOISE, "", null, 0.99D,
                    List.of("separator-line-noise"), 0);
        }

        // (4) Markdown 标题
        Matcher markdownHeading = MARKDOWN_HEADING_PATTERN.matcher(trimmedText);
        if (markdownHeading.matches()) {
            String title = markdownHeading.group(2).trim();
            if (isSameDocumentTitle(documentTitle, title)) {
                return buildSignal(logicalLineNo, rawText, trimmedText,
                        DocumentStructureNodeSignalEnum.NOISE, "", null, 0.99D,
                        List.of("duplicate-document-title"), 0);
            }
            String headingCode = extractHeadingCode(title);
            return buildSignal(logicalLineNo, rawText, trimmedText,
                    DocumentStructureNodeSignalEnum.HEADING,
                    headingCode, title, markdownHeading.group(1).length(),
                    0.98D, List.of("markdown-heading"),
                    extractNumericPath(headingCode), null, indent);
        }

        // (5) 显式步骤标记
        Matcher explicitStep = EXPLICIT_STEP_PATTERN.matcher(trimmedText);
        if (explicitStep.matches()) {
            String rawOrdinal = explicitStep.group(1) != null ? explicitStep.group(1)
                    : explicitStep.group(2) != null ? explicitStep.group(2)
                    : explicitStep.group(3);
            Integer sequenceNo = parseOrdinal(rawOrdinal);
            return buildSignal(logicalLineNo, rawText, trimmedText,
                    DocumentStructureNodeSignalEnum.STEP_ITEM,
                    "", explicitStep.group(4).trim(), null, 0.96D,
                    List.of("explicit-step"), null, sequenceNo, indent);
        }

        // (6) 中文章节
        Matcher chapter = CHAPTER_PATTERN.matcher(trimmedText);
        if (chapter.matches()) {
            String code = chapter.group(1).trim();
            String title = chapter.group(3).trim();
            if (isSameDocumentTitle(documentTitle, title)) {
                return buildSignal(logicalLineNo, rawText, trimmedText,
                        DocumentStructureNodeSignalEnum.NOISE, "", null, 0.99D,
                        List.of("duplicate-document-title"), 0);
            }
            Integer chapterNo = parseOrdinal(chapter.group(2));
            List<Integer> numericPath = (chapterNo != null && chapterNo > 0)
                    ? List.of(chapterNo) : null;
            return buildSignal(logicalLineNo, rawText, trimmedText,
                    DocumentStructureNodeSignalEnum.HEADING,
                    code, title, 1, 0.96D,
                    List.of("chapter-heading"), numericPath, null, indent);
        }

        // (7) 附录标题
        Matcher appendix = APPENDIX_PATTERN.matcher(trimmedText);
        if (appendix.matches()) {
            String code = appendix.group(1).trim();
            String title = appendix.group(3) != null ? appendix.group(3).trim() : code;
            return buildSignal(logicalLineNo, rawText, trimmedText,
                    DocumentStructureNodeSignalEnum.HEADING,
                    code, title, 1, 0.92D,
                    List.of("appendix-heading"), null, null, indent);
        }

        // (8) 数字多级编号
        Matcher decimal = DECIMAL_HEADING_PATTERN.matcher(trimmedText);
        if (decimal.matches()) {
            String code = decimal.group(1).trim();
            String title = decimal.group(2).trim();
            // 防御：句末标点结尾 → 正文句（如 "1.0.0 是一个正常的版本引用。"）
            if (title.endsWith("。") || title.endsWith("！") || title.endsWith("？")) {
                return buildSignal(logicalLineNo, rawText, trimmedText,
                        DocumentStructureNodeSignalEnum.BODY,
                        "", null, 1.0D, List.of("body"), indent);
            }
            List<Integer> numericPath = extractNumericPath(code);
            int levelHint = Math.max(1, code.split("\\.").length);
            return buildSignal(logicalLineNo, rawText, trimmedText,
                    DocumentStructureNodeSignalEnum.HEADING,
                    code, title, levelHint, 0.95D,
                    List.of("decimal-heading"), numericPath, null, indent);
        }

        // (8.5) 单数字空格标题 —— "1 系统架构设计"、"2 核心模块设计"
        // 在 decimal-heading 之后、table-row 之前检测，避免表行/序号列表误匹配
        Matcher singleNumSpace = SINGLE_NUMBER_SPACE_HEADING_PATTERN.matcher(trimmedText);
        if (singleNumSpace.matches()) {
            String num = singleNumSpace.group(1);
            String title = singleNumSpace.group(2).trim();
            // 必须满足标题上下文：孤立短行、不像列表项、不是正文句
            if (looksLikePlainHeading(trimmedText, logicalLineContext)) {
                int levelHint = inferPlainHeadingLevel(logicalLineContext);
                return buildSignal(logicalLineNo, rawText, trimmedText,
                        DocumentStructureNodeSignalEnum.HEADING,
                        num, title, levelHint, 0.82D,
                        List.of("single-number-space-heading"),
                        List.of(Integer.parseInt(num)), null, indent);
            }
        }

        // (9) 表格行
        if (isTableRow(trimmedText)) {
            return buildSignal(logicalLineNo, rawText, trimmedText,
                    DocumentStructureNodeSignalEnum.TABLE_ROW,
                    "", null, 0.90D, List.of("table-row"), indent);
        }

        // (10) 引用行
        if (trimmedText.startsWith(">")) {
            return buildSignal(logicalLineNo, rawText, trimmedText,
                    DocumentStructureNodeSignalEnum.QUOTE,
                    "", null, 0.88D, List.of("quote"), indent);
        }

        // (11) 复选框
        Matcher checkbox = CHECKBOX_PATTERN.matcher(trimmedText);
        if (checkbox.matches()) {
            return buildSignal(logicalLineNo, rawText, trimmedText,
                    DocumentStructureNodeSignalEnum.LIST_ITEM,
                    "", checkbox.group(1).trim(), null, 0.92D,
                    List.of("checkbox-list"), null, null, indent);
        }

        // (11.5) 半角括号编号：(1) xxx、(a) xxx — 几乎不会是标题
        Matcher parenNum = PAREN_NUMBER_PATTERN.matcher(trimmedText);
        if (parenNum.matches()) {
            Integer sequenceNo = parseOrdinal(parenNum.group(1));
            return buildSignal(logicalLineNo, rawText, trimmedText,
                    DocumentStructureNodeSignalEnum.LIST_ITEM,
                    "", parenNum.group(2).trim(), null, 0.88D,
                    List.of("paren-number-list"), null, sequenceNo, indent);
        }

        // (11.6) 右括号编号：1) xxx、a) xxx — 通常是列表项
        Matcher rightParenNum = RIGHT_PAREN_NUMBER_PATTERN.matcher(trimmedText);
        if (rightParenNum.matches()) {
            Integer sequenceNo = parseOrdinal(rightParenNum.group(1));
            return buildSignal(logicalLineNo, rawText, trimmedText,
                    DocumentStructureNodeSignalEnum.LIST_ITEM,
                    "", rightParenNum.group(2).trim(), null, 0.85D,
                    List.of("right-paren-number-list"), null, sequenceNo, indent);
        }

        // (12) 无序列表
        Matcher bullet = BULLET_PATTERN.matcher(trimmedText);
        if (bullet.matches()) {
            return buildSignal(logicalLineNo, rawText, trimmedText,
                    DocumentStructureNodeSignalEnum.LIST_ITEM,
                    "", bullet.group(2).trim(), null, 0.90D,
                    List.of("bullet-list"), null, null, indent);
        }

        // (13) 数字单级编号 —— 模糊判断标题 vs 列表
        Matcher singleDigit = SINGLE_LEVEL_DIGIT_PATTERN.matcher(trimmedText);
        if (singleDigit.matches()) {
            return classifySingleLevelMarker(logicalLineNo, rawText, trimmedText,
                    singleDigit.group(1).trim(), singleDigit.group(2).trim(),
                    OrderedMarkerFamily.ARABIC_SINGLE, logicalLineContext, indent);
        }

        // (14) 中文序号 —— 模糊判断标题 vs 列表
        Matcher chineseOutline = CHINESE_OUTLINE_PATTERN.matcher(trimmedText);
        if (chineseOutline.matches()) {
            return classifySingleLevelMarker(logicalLineNo, rawText, trimmedText,
                    chineseOutline.group(1).trim(), chineseOutline.group(2).trim(),
                    OrderedMarkerFamily.CHINESE_OUTLINE, logicalLineContext, indent);
        }

        // (15) 兜底朴素标题判断
        if (looksLikePlainHeading(trimmedText, logicalLineContext)) {
            int levelHint = inferPlainHeadingLevel(logicalLineContext);
            return buildSignal(logicalLineNo, rawText, trimmedText,
                    DocumentStructureNodeSignalEnum.HEADING_CANDIDATE,
                    "", trimmedText, levelHint, 0.58D,
                    List.of("plain-heading-candidate"), null, null, indent);
        }

        // (16) 以上全没命中 —— 普通正文
        return buildSignal(logicalLineNo, rawText, trimmedText,
                DocumentStructureNodeSignalEnum.BODY,
                "", null, 1.0D, List.of("body"), indent);
    }

    // ============ 构造器辅助 ============

    /** 精简版：无 title / numericPath / sequenceNo，均传 null */
    private DocumentStructureNodeSignal buildSignal(int logicalLineNo, String rawText, String trimmedText,
                                                     DocumentStructureNodeSignalEnum kind,
                                                     String headingCode, Integer levelHint, double confidence,
                                                     List<String> reasons, int indentLevel) {
        return buildSignal(logicalLineNo, rawText, trimmedText, kind, headingCode, null, levelHint, confidence, reasons, null, null, indentLevel);
    }

    /** 完整参数 —— 所有 call site 均可直接使用此方法 */
    private DocumentStructureNodeSignal buildSignal(int logicalLineNo, String rawText, String trimmedText,
                                                     DocumentStructureNodeSignalEnum kind,
                                                     String headingCode, String title, Integer levelHint, double confidence,
                                                     List<String> reasons, List<Integer> numericPath, Integer sequenceNo,
                                                     int indentLevel) {
        return new DocumentStructureNodeSignal(logicalLineNo, rawText, trimmedText, kind,
                headingCode, sequenceNo, numericPath, title, levelHint, indentLevel, reasons, confidence);
    }

    // ============ 单级编号模糊分类 ============

    /**
     * 对单级编号（数字/中文）进行模糊分类：根据上下文判断是标题候选还是列表项。
     *
     * <p>判定逻辑：如果满足以下任一条件则为 LIST_ITEM，否则为 HEADING_CANDIDATE：</p>
     * <ul>
     *   <li>前后存在连续序号（邻接检测）</li>
     *   <li>前一行以冒号结尾（引导列表）</li>
     *   <li>不具备朴素标题特征</li>
     * </ul>
     */
    private DocumentStructureNodeSignal classifySingleLevelMarker(int logicalLineNo, String rawText,
                                                                   String trimmedText, String marker, String title,
                                                                   OrderedMarkerFamily family,
                                                                   LogicalLineContext logicalLineContext,
                                                                   int indentLevel) {
        Integer sequenceNo = parseOrdinal(marker);
        boolean sequential = isNeighborSequence(sequenceNo, family, logicalLineContext);
        boolean introducedByLeadIn = previousIntroducesList(logicalLineContext.preNotBlankLine());
        boolean headingLike = !sequential
                && !introducedByLeadIn
                && looksLikePlainHeading(title, logicalLineContext);

        String reasonKey = family == OrderedMarkerFamily.ARABIC_SINGLE
                ? "single-digit" : "chinese-outline";
        double confidence = headingLike
                ? (family == OrderedMarkerFamily.ARABIC_SINGLE ? 0.62D : 0.60D)
                : (sequential || introducedByLeadIn
                        ? (family == OrderedMarkerFamily.ARABIC_SINGLE ? 0.93D : 0.92D)
                        : (family == OrderedMarkerFamily.ARABIC_SINGLE ? 0.88D : 0.86D));

        return buildSignal(logicalLineNo, rawText, trimmedText,
                headingLike ? DocumentStructureNodeSignalEnum.HEADING_CANDIDATE : DocumentStructureNodeSignalEnum.LIST_ITEM,
                marker, title, headingLike ? (Integer) 1 : null, confidence,
                List.of(headingLike ? reasonKey + "-ambiguous-heading"
                        : sequential ? reasonKey + "-sequence-list" : reasonKey + "-list"),
                headingLike ? List.of(sequenceNo) : null,
                headingLike ? null : sequenceNo,
                indentLevel);
    }

    // ============ 标题编码解析 ============

    /**
     * 从标题编码字符串中提取数字路径。
     *
     * <ul>
     *   <li>{@code "1.2.3"} → [1, 2, 3]</li>
     *   <li>{@code "第一章"} → [1]（通过中文章节模式解析）</li>
     *   <li>无法解析 → 空列表</li>
     * </ul>
     */
    private List<Integer> extractNumericPath(String headingCode) {
        String normalized = headingCode.trim();
        if (StringUtils.isBlank(normalized)) {
            return List.of();
        }
        // 多级数字编码
        if (headingCode.contains(".")) {
            List<Integer> numericPath = new ArrayList<>();
            for (String segment : headingCode.split("\\.")) {
                if (!segment.chars().allMatch(Character::isDigit)) {
                    return List.of();
                }
                numericPath.add(Integer.parseInt(segment));
            }
            return numericPath;
        }
        // 中文章节编码
        Matcher matcher = CHAPTER_PATTERN.matcher(headingCode + "标题");
        if (matcher.matches()) {
            Integer chapterNo = parseOrdinal(matcher.group(2));
            if (chapterNo != null && chapterNo > 0) {
                return List.of(chapterNo);
            }
        }
        return List.of();
    }

    // ============ 中文/数字序号解析 ============

    /**
     * 解析中文或阿拉伯数字序号为整数。
     *
     * <p>支持范围：0~99（中文：零~九十九；阿拉伯：0~99）。</p>
     * <ul>
     *   <li>阿拉伯数字：{@code "3"} → 3</li>
     *   <li>纯中文数字：{@code "五"} → 5</li>
     *   <li>{@code "十"} → 10, {@code "十一"} → 11, {@code "二十"} → 20, {@code "二十三"} → 23</li>
     *   <li>{@code "零"} → 0</li>
     * </ul>
     *
     * @param rawOrdinal 原始序号字符串
     * @return 解析后的整数，无法解析时返回 null
     */
    private Integer parseOrdinal(String rawOrdinal) {
        String normalized = rawOrdinal.trim();
        if (StringUtils.isBlank(normalized)) {
            return null;
        }
        // 纯阿拉伯数字
        if (normalized.chars().allMatch(Character::isDigit)) {
            return Integer.parseInt(normalized);
        }
        // 中文字符映射
        Map<Character, Integer> digitMap = Map.of(
                '零', 0, '一', 1, '二', 2, '三', 3, '四', 4, '五', 5,
                '六', 6, '七', 7, '八', 8, '九', 9
        );
        // 纯"十"
        if ("十".equals(normalized)) {
            return 10;
        }
        // 十一~十九
        if (normalized.startsWith("十") && normalized.length() == 2) {
            return 10 + digitMap.getOrDefault(normalized.charAt(1), 0);
        }
        // 二十~九十
        if (normalized.endsWith("十") && normalized.length() == 2) {
            return digitMap.getOrDefault(normalized.charAt(0), 0) * 10;
        }
        // 二十一~九十九
        if (normalized.contains("十") && normalized.length() == 3) {
            return digitMap.getOrDefault(normalized.charAt(0), 0) * 10
                    + digitMap.getOrDefault(normalized.charAt(2), 0);
        }
        // 单字（零~九）或未覆盖的字符
        Integer result = digitMap.get(normalized.charAt(0));
        if (result == null) {
            log.warn("无法解析的中文序号: '{}'，当前仅支持 0~99", normalized);
        }
        return result;
    }

    // ============ 标题编码提取 ============

    /**
     * 从标题文本中提取标准化编码。
     *
     * <p>依次尝试 DECIMAL、CHAPTER、APPENDIX 三种模式，返回匹配到的编码部分。</p>
     */
    private String extractHeadingCode(String heading) {
        Matcher decimal = DECIMAL_HEADING_PATTERN.matcher(heading);
        if (decimal.matches()) {
            return decimal.group(1).trim();
        }
        Matcher chapter = CHAPTER_PATTERN.matcher(heading);
        if (chapter.matches()) {
            return chapter.group(1).trim();
        }
        Matcher appendix = APPENDIX_PATTERN.matcher(heading);
        if (appendix.matches()) {
            return appendix.group(1).trim();
        }
        return "";
    }

    // ============ 表格行判定 ============

    /**
     * 判断当前行是否为表格行。
     *
     * <p>满足以下任一条件即视为表格行：</p>
     * <ul>
     *   <li>以 | 开头且以 | 结尾</li>
     *   <li>包含 \t 制表符</li>
     *   <li>按 | 拆分后 ≥ 3 段（排除含 Markdown 链接 {@code [text](url)} 的干扰）</li>
     *   <li>纯分隔线（由 : - 空格 | 组成）</li>
     * </ul>
     */
    private boolean isTableRow(String trimmedText) {
        if (trimmedText.startsWith("|") && trimmedText.endsWith("|")) {
            return true;
        }
        if (trimmedText.contains("\t")) {
            return true;
        }
        // 含有 | 且拆分 ≥ 3 段，但排除 Markdown 链接中的 | 干扰
        if (trimmedText.contains("|") && TABLE_SPLIT_PATTERN.split(trimmedText).length >= 3) {
            // 排除 "[text](url)" 模式 —— 链接中的 | 不应当判为表格
            if (!MARKDOWN_LINK_WITH_PIPE.matcher(trimmedText).matches()) {
                return true;
            }
        }
        // 纯分隔线
        return TABLE_SEPARATOR_LINE.matcher(trimmedText).matches();
    }

    // ============ 朴素标题启发式 ============

    /**
     * 朴素标题启发式判定 —— 对未命中任何正则的短行进行"是否像标题"的综合判断。
     *
     * <h4>排除条件（任一命中则返回 false）</h4>
     * <ul>
     *   <li>空白或超长（>50 字符）</li>
     *   <li>以句末标点结尾（。！？；.!?;）</li>
     *   <li>包含 URL</li>
     *   <li>以 | 开头或结尾（表格行）</li>
     *   <li>纯分隔线</li>
     * </ul>
     *
     * <h4>准入条件（全部满足才返回 true）</h4>
     * <ul>
     *   <li>前后有空白行隔离（isolated）</li>
     *   <li>下一行看起来像内容（非分隔线/表格）</li>
     *   <li>看起来像名词短语（不含中文逗号、分号、句号、冒号）</li>
     * </ul>
     */
    private boolean looksLikePlainHeading(String text, LogicalLineContext context) {
        // 基础排除
        String normalized = text.trim();
        if (normalized.isBlank() || normalized.length() > 50) {
            return false;
        }
        if (endsWithSentencePunctuation(normalized)) {
            return false;
        }
        // URL 排除（统合了 contains("http") 检查，不需要再单独判断 startsWith("http")）
        if (normalized.contains("http://") || normalized.contains("https://")) {
            return false;
        }
        if (normalized.startsWith("|") || normalized.endsWith("|")) {
            return false;
        }
        if (PLAIN_HEADING_RULE.matcher(normalized).matches()) {
            return false;
        }
        // 至少包含一个字母、数字或 CJK 字符（排除纯符号行如 "("、")"）
        if (!normalized.matches(".*[\\p{L}\\p{N}].*")) {
            return false;
        }
        // Markdown 斜体/粗体包裹的元注释行（如 *文档结束*、_免责声明_）
        if ((normalized.startsWith("*") && normalized.endsWith("*") && !normalized.startsWith("* "))
                || (normalized.startsWith("_") && normalized.endsWith("_") && !normalized.startsWith("_ "))) {
            return false;
        }

        // 上下文准入
        if (context == null) {
            return false;
        }
        boolean isolated = context.precededByBlank() || context.followedByBlank();
        boolean nextLooksContent = context.nextNotBlankLine() != null
                && StringUtils.isNotBlank(context.nextNotBlankLine().trimmedText())
                && !context.nextNotBlankLine().trimmedText().matches("^[:\\-\\s|]+$");

        // 名词短语特征
        boolean nounLike = !normalized.contains("，")
                && !normalized.contains("；")
                && !normalized.contains("。")
                && !normalized.contains("：");

        return isolated && nextLooksContent && nounLike;
    }

    /**
     * 推断朴素标题的层级。
     *
     * @return 前面有空白行 → 1（高层级），否则 → 2（次层级）
     */
    private int inferPlainHeadingLevel(LogicalLineContext context) {
        if (context == null || context.precededByBlank()) {
            return 1;
        }
        return 2;
    }

    // ============ 标点与引导检测 ============

    /** 检测文本是否以句末标点结尾。 */
    private boolean endsWithSentencePunctuation(String text) {
        return text.endsWith("。") || text.endsWith("！") || text.endsWith("？") || text.endsWith("；")
                || text.endsWith(".") || text.endsWith("!") || text.endsWith("?") || text.endsWith(";");
    }

    /**
     * 检测前一行是否以冒号结尾（常用于引导列表）。
     */
    private boolean previousIntroducesList(DocumentStructureNodeLogicalLine preNotBlank) {
        if (preNotBlank == null) {
            return false;
        }
        String text = preNotBlank.trimmedText().trim();
        return text.endsWith("：") || text.endsWith(":");
    }

    // ============ 邻接序列检测 ============

    /**
     * 检测当前序号在上下文中是否构成连续序列（前后邻接）。
     */
    private boolean isNeighborSequence(Integer sequenceNo, OrderedMarkerFamily family, LogicalLineContext context) {
        if (sequenceNo == null || family == null) {
            return false;
        }
        return isSequenceNeighbor(context.preNotBlankLine(), sequenceNo, family, -1)
                || isSequenceNeighbor(context.nextNotBlankLine(), sequenceNo, family, 1);
    }

    /**
     * 检测候选行是否是目标序号的邻居（偏移量匹配）。
     */
    private boolean isSequenceNeighbor(DocumentStructureNodeLogicalLine candidate, Integer sequenceNo,
                                       OrderedMarkerFamily family, int offset) {
        if (candidate == null || sequenceNo == null) {
            return false;
        }
        Integer candidateIndex = resolveOrderedIndex(candidate.trimmedText(), family);
        return candidateIndex != null && candidateIndex.intValue() == sequenceNo.intValue() + offset;
    }

    /**
     * 从文本中提取有序列表的序号索引，按标记族类型匹配对应正则。
     */
    private Integer resolveOrderedIndex(String text, OrderedMarkerFamily family) {
        String normalized = text.trim();
        if (normalized.isBlank()) {
            return null;
        }
        return switch (family) {
            case ARABIC_SINGLE -> {
                Matcher matcher = SINGLE_LEVEL_DIGIT_PATTERN.matcher(normalized);
                yield matcher.matches() ? parseOrdinal(matcher.group(1)) : null;
            }
            case CHINESE_OUTLINE -> {
                Matcher matcher = CHINESE_OUTLINE_PATTERN.matcher(normalized);
                yield matcher.matches() ? parseOrdinal(matcher.group(1)) : null;
            }
        };
    }

    // ============ 噪声检测 ============

    /**
     * 检测重复出现的噪声行（页眉页脚、版权声明、版本号、多余表格线等）。
     *
     * <h4>判定规则</h4>
     * <ul>
     *   <li>匹配版权/保密关键词 且 行短 ≤ 80 字符 → 版权噪声（即使只出现一次）</li>
     *   <li>出现 ≥ 2 次且匹配文档标题 → 标题重复噪声</li>
     *   <li>出现 ≥ 3 次、长度 ≤ 120 且 (匹配版本号模式 或 是表格行) → 重复页眉页脚噪声</li>
     * </ul>
     *
     * @param documentTitle 文档标题
     * @param trimmedText   当前行 trimmed 文本
     * @param frequency     该行在全文中的出现次数
     * @return true 如果是可丢弃的噪声行
     */
    private boolean isRepeatedNoiseOrFooter(String documentTitle, String trimmedText, int frequency) {
        // 版权/保密声明 — 即使只出现一次也是噪声（限制短行，避免 "copyright law protects..." 类正文句误杀）
        if (COPYRIGHT_PATTERN.matcher(trimmedText).matches() && trimmedText.length() <= 80) {
            return true;
        }
        if (frequency < 2) {
            return false;
        }
        // 标题重复
        if (isSameDocumentTitle(documentTitle, trimmedText)) {
            return true;
        }
        // 版权/保密关键词
        if (COPYRIGHT_PATTERN.matcher(trimmedText).matches()) {
            return true;
        }
        // 高频噪声（≥3 次出现）：版本号或表格线
        if (frequency >= 3 && trimmedText.length() <= 120) {
            if (VERSION_PATTERN.matcher(trimmedText).matches()) {
                return true;
            }
            if (trimmedText.contains("|")) {
                return true;
            }
        }
        return false;
    }

    // ============ 标题去重 ============

    /** 标准化标题 —— 去除 Markdown # 前导 */
    private static final Pattern TITLE_NORMALIZE_MARKDOWN = Pattern.compile("^#+\\s*");
    /** 标准化标题 —— 去除文件扩展名（防御性，调用方应已剥离） */
    private static final Pattern TITLE_NORMALIZE_EXT = Pattern.compile("\\.[A-Za-z0-9]{1,6}$");
    /** 标准化标题 —— 合并所有空白 */
    private static final Pattern TITLE_NORMALIZE_SPACE = Pattern.compile("\\s+");

    /** 检测 trimmedText 是否是文档标题的重复出现。 */
    private boolean isSameDocumentTitle(String documentTitle, String trimmedText) {
        String left = normalizeComparableTitle(documentTitle);
        String right = normalizeComparableTitle(trimmedText);
        return right.equals(left);
    }

    /**
     * 标准化标题用于比对 —— 去 Markdown # 标记、去文件扩展名、去空格、转小写。
     */
    private String normalizeComparableTitle(String title) {
        return TITLE_NORMALIZE_SPACE
                .matcher(TITLE_NORMALIZE_EXT
                        .matcher(TITLE_NORMALIZE_MARKDOWN
                                .matcher(title).replaceAll(""))
                        .replaceAll(""))
                .replaceAll("")
                .toLowerCase(Locale.ROOT);
    }

    // ============ 逻辑行上下文构建 ============

    /**
     * 为所有逻辑行批量预计算前后文上下文 —— O(n) 正向+反向两趟扫描。
     *
     * <p>替代原先每行独立 O(n) 扫描（总计 O(n²)）的实现。</p>
     */
    private List<LogicalLineContext> buildLogicalLineContexts(List<DocumentStructureNodeLogicalLine> logicalLines) {
        int size = logicalLines.size();
        // 正向扫描：前驱 + 是否跨空白
        DocumentStructureNodeLogicalLine[] preNotBlank = new DocumentStructureNodeLogicalLine[size];
        boolean[] precededByBlank = new boolean[size];
        DocumentStructureNodeLogicalLine lastNonBlank = null;
        boolean blankSince = false;
        for (int i = 0; i < size; i++) {
            preNotBlank[i] = lastNonBlank;
            precededByBlank[i] = blankSince;
            if (StringUtils.isBlank(logicalLines.get(i).trimmedText())) {
                blankSince = true;
            } else {
                lastNonBlank = logicalLines.get(i);
                blankSince = false;
            }
        }
        // 反向扫描：后继 + 是否跨空白
        DocumentStructureNodeLogicalLine[] nextNotBlank = new DocumentStructureNodeLogicalLine[size];
        boolean[] followedByBlank = new boolean[size];
        DocumentStructureNodeLogicalLine nextNonBlankLine = null;
        boolean blankAhead = false;
        for (int i = size - 1; i >= 0; i--) {
            nextNotBlank[i] = nextNonBlankLine;
            followedByBlank[i] = blankAhead;
            if (StringUtils.isBlank(logicalLines.get(i).trimmedText())) {
                blankAhead = true;
            } else {
                nextNonBlankLine = logicalLines.get(i);
                blankAhead = false;
            }
        }
        // 组装
        List<LogicalLineContext> contexts = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            contexts.add(new LogicalLineContext(preNotBlank[i], nextNotBlank[i], precededByBlank[i], followedByBlank[i]));
        }
        return contexts;
    }

    // ============ 逻辑行构建 ============

    /**
     * 将原始纯文本解析为逻辑行列表。
     *
     * <p>流程：按 \n 拆行 → 每行用 {@link #splitRawLineSegments} 拆分压缩多步骤行 → 计算缩进 → 产出逻辑行。</p>
     */
    private List<DocumentStructureNodeLogicalLine> buildLogicalLines(String parsedText) {
        String[] rawLines = StringUtils.defaultIfBlank(parsedText, "").split("\n", -1);
        List<DocumentStructureNodeLogicalLine> logicalLines = new ArrayList<>(rawLines.length);
        int logicalLineNo = 1;
        for (int index = 0; index < rawLines.length; index++) {
            String rawLine = StringUtils.defaultIfBlank(rawLines[index], "");
            List<String> segments = splitRawLineSegments(rawLine);
            if (segments.isEmpty()) {
                logicalLines.add(new DocumentStructureNodeLogicalLine(
                        logicalLineNo++, index + 1, 1, 0, rawLine, rawLine.trim()));
                continue;
            }
            for (int segmentIndex = 0; segmentIndex < segments.size(); segmentIndex++) {
                String segment = segments.get(segmentIndex);
                logicalLines.add(new DocumentStructureNodeLogicalLine(
                        logicalLineNo++, index + 1, segmentIndex + 1,
                        calculateIndent(segment), segment, segment.trim()));
            }
        }
        return logicalLines;
    }

    /**
     * 将单行原始文本切分为多个 segment（处理在同一行内压缩多个步骤标记的情况）。
     *
     * <p>使用 {@link #STEP_BOUNDARY_PATTERN} 零宽断言定位切分点，
     * 保留标题（#）、表格（|）、引用（>）、分隔线不拆分。</p>
     */
    private List<String> splitRawLineSegments(String rawLine) {
        if (StringUtils.isBlank(rawLine)) {
            return List.of();
        }
        String trimmedRawLine = rawLine.trim();
        // 标题/表格/引用/分隔线/Tab分隔数据不拆分
        if (trimmedRawLine.startsWith("#")
                || trimmedRawLine.startsWith("|")
                || trimmedRawLine.startsWith(">")
                || trimmedRawLine.contains("\t")
                || TABLE_SEPARATOR_LINE.matcher(trimmedRawLine).matches()) {
            return List.of(rawLine);
        }

        List<Integer> boundaries = new ArrayList<>();
        boundaries.add(0);
        Matcher matcher = STEP_BOUNDARY_PATTERN.matcher(rawLine);
        while (matcher.find()) {
            if (matcher.start() > 0) {
                boundaries.add(matcher.start());
            }
        }
        if (boundaries.size() == 1) {
            return List.of(rawLine);
        }
        List<String> segments = new ArrayList<>();
        for (int index = 0; index < boundaries.size(); index++) {
            int start = boundaries.get(index);
            int end = index == boundaries.size() - 1 ? rawLine.length() : boundaries.get(index + 1);
            String segment = rawLine.substring(start, end).trim();
            if (StringUtils.isNotBlank(segment)) {
                segments.add(segment);
            }
        }
        return segments.isEmpty() ? List.of(rawLine) : segments;
    }

    /**
     * 计算缩进层级 —— 统计前导空格（tab 按 4 空格计）。
     */
    private int calculateIndent(String text) {
        if (text.isBlank()) {
            return 0;
        }
        int indent = 0;
        for (int index = 0; index < text.length(); index++) {
            char cur = text.charAt(index);
            if (cur == ' ') {
                indent++;
                continue;
            }
            if (cur == '\t') {
                indent += 4;
                continue;
            }
            break;
        }
        return indent;
    }

    /**
     * 统计每条逻辑行 trimmed 文本的出现次数，用于噪声去重。
     */
    private Map<String, Integer> countLogicalLineFrequency(List<DocumentStructureNodeLogicalLine> logicalLines) {
        Map<String, Integer> frequencyMap = new HashMap<>();
        for (DocumentStructureNodeLogicalLine logicalLine : logicalLines) {
            frequencyMap.merge(logicalLine.trimmedText(), 1, Integer::sum);
        }
        return frequencyMap;
    }

}