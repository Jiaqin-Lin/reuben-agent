package com.reubenagent.document.support;

import com.reubenagent.document.enums.DocumentStructureNodeSignalEnum;
import com.reubenagent.document.model.DocumentStructureNodeSignal;
import com.reubenagent.document.model.DocumentStructureNodeSignalBatch;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 文档结构信号提取器展示测试 —— 遍历 test-documents/ 下所有测试文档，
 * 运行 {@link DocumentStructureNodeSignalExtractor} 并以可读格式输出分类结果。
 *
 * <p>运行方式：</p>
 * <pre>{@code
 *   mvn test -pl business/document -am -Dtest=DocumentSignalExtractorDisplayTest
 * }</pre>
 *
 * @author reuben
 * @since 2026-06-18
 */
@DisplayName("文档结构信号提取器 - 分类结果展示")
class DocumentSignalExtractorDisplayTest {

    private static final DocumentStructureNodeSignalExtractor extractor =
            new DocumentStructureNodeSignalExtractor();

    /** 颜色常量 */
    private static final String RESET = "[0m";
    private static final String BOLD = "[1m";
    private static final String CYAN = "[36m";
    private static final String GREEN = "[32m";
    private static final String YELLOW = "[33m";
    private static final String RED = "[31m";
    private static final String BLUE = "[34m";
    private static final String MAGENTA = "[35m";
    private static final String GRAY = "[90m";
    private static final String DIM = "[2m";

    /** 信号类型 → ANSI 色映射 */
    private static final Map<DocumentStructureNodeSignalEnum, String> KIND_COLOR = Map.of(
            DocumentStructureNodeSignalEnum.DOCUMENT_TITLE, MAGENTA,
            DocumentStructureNodeSignalEnum.HEADING, GREEN,
            DocumentStructureNodeSignalEnum.HEADING_CANDIDATE, YELLOW,
            DocumentStructureNodeSignalEnum.STEP_ITEM, CYAN,
            DocumentStructureNodeSignalEnum.LIST_ITEM, BLUE,
            DocumentStructureNodeSignalEnum.TABLE_ROW, GRAY,
            DocumentStructureNodeSignalEnum.QUOTE, GRAY,
            DocumentStructureNodeSignalEnum.BODY, RESET,
            DocumentStructureNodeSignalEnum.BLANK, DIM,
            DocumentStructureNodeSignalEnum.NOISE, RED
    );

    // ============ 主测试：遍历所有文档并输出 ============

    /**
     * 自动发现 test-documents/ 下所有 .md / .txt 文件并逐一测试。
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("testDocumentPaths")
    @DisplayName("分类结果展示")
    void classifyAndDisplay(Path docPath) throws IOException {
        String fileName = docPath.getFileName().toString();
        String rawContent = Files.readString(docPath, StandardCharsets.UTF_8);

        // 从文件名推导文档标题（去掉扩展名）
        String documentTitle = fileName.contains(".")
                ? fileName.substring(0, fileName.lastIndexOf('.'))
                : fileName;

        // ─────────────────────────────────────────────
        //  执行分类
        // ─────────────────────────────────────────────
        DocumentStructureNodeSignalBatch batch = extractor.extract(documentTitle, rawContent);
        List<DocumentStructureNodeSignal> signals = batch.signals();

        // ─────────────────────────────────────────────
        //  打印报告头部
        // ─────────────────────────────────────────────
        printSeparator('═', 100);
        System.out.println(BOLD + CYAN + "  文档: " + fileName + RESET);
        System.out.println(DIM + "  标题: " + documentTitle
                + "  |  信号总数: " + signals.size()
                + "  |  逻辑行数: " + batch.contextLines().size() + RESET);
        printSeparator('─', 100);

        // ─────────────────────────────────────────────
        //  打印信号表格（│ 分隔 + CJK 宽字对齐）
        // ─────────────────────────────────────────────
        final int W_LINE = 6, W_KIND = 18, W_CONF = 6, W_LEVEL = 5,
                  W_INDENT = 5, W_CODE = 10, W_SEQ = 5;
        final int[] COL_W = {W_LINE, W_KIND, W_CONF, W_LEVEL, W_INDENT, W_CODE, W_SEQ};
        final String SEP = DIM + "│" + RESET;

        // 分隔线生成
        System.out.println("  " + borderLine('┌', '┬', '┐', COL_W));
        // 表头
        System.out.println("  " + SEP +
                fixedPad("行号", W_LINE) + SEP +
                fixedPad("类型", W_KIND) + SEP +
                fixedPad("置信度", W_CONF) + SEP +
                fixedPad("层级", W_LEVEL) + SEP +
                fixedPad("缩进", W_INDENT) + SEP +
                fixedPad("编码", W_CODE) + SEP +
                fixedPad("序号", W_SEQ) + SEP +
                " 内容");
        System.out.println("  " + borderLine('├', '┼', '┤', COL_W));

        int headingCount = 0, headingCandidateCount = 0, stepCount = 0,
                listCount = 0, tableCount = 0, quoteCount = 0,
                bodyCount = 0, blankCount = 0, noiseCount = 0;

        for (DocumentStructureNodeSignal sig : signals) {
            DocumentStructureNodeSignalEnum kind = sig.getKind();
            String color = KIND_COLOR.getOrDefault(kind, RESET);

            // 计数
            switch (kind) {
                case HEADING -> headingCount++;
                case HEADING_CANDIDATE -> headingCandidateCount++;
                case STEP_ITEM -> stepCount++;
                case LIST_ITEM -> listCount++;
                case TABLE_ROW -> tableCount++;
                case QUOTE -> quoteCount++;
                case BODY -> bodyCount++;
                case BLANK -> blankCount++;
                case NOISE -> noiseCount++;
            }

            // 裁剪内容
            String content = sig.getTrimmedText();
            if (content == null) content = "";
            if (content.length() > 48) content = content.substring(0, 45) + "...";

            String lineNo = sig.getLogicalLineNo() == 0 ? "TITLE" : String.valueOf(sig.getLogicalLineNo());
            boolean isHeading = kind == DocumentStructureNodeSignalEnum.HEADING
                    || kind == DocumentStructureNodeSignalEnum.HEADING_CANDIDATE;

            System.out.println("  " + SEP +
                    colorPad(lineNo, W_LINE, color, false) + SEP +
                    colorPad(kind.name(), W_KIND, color + (isHeading ? BOLD : ""), false) + SEP +
                    colorPad(fmtConfidence(sig.getConfidence()), W_CONF, color, false) + SEP +
                    colorPad(fmtLevel(sig.getLevelHint()), W_LEVEL, color, false) + SEP +
                    colorPad(fmtIndent(sig.getIndentLevel()), W_INDENT, color, false) + SEP +
                    colorPad(fmtCode(sig.getHeadingCode()), W_CODE, color, false) + SEP +
                    colorPad(fmtSeq(sig.getSequenceNo()), W_SEQ, color, false) + SEP +
                    " " + RESET + content);
        }

        System.out.println("  " + borderLine('└', '┴', '┘', COL_W));

        // ─────────────────────────────────────────────
        //  统计摘要
        // ─────────────────────────────────────────────
        printSeparator('─', 100);
        System.out.print("  " + BOLD + "统计:" + RESET);
        if (headingCount > 0) System.out.print("  " + GREEN + "HEADING=" + headingCount + RESET);
        if (headingCandidateCount > 0) System.out.print("  " + YELLOW + "HEADING_CANDIDATE=" + headingCandidateCount + RESET);
        if (stepCount > 0) System.out.print("  " + CYAN + "STEP=" + stepCount + RESET);
        if (listCount > 0) System.out.print("  " + BLUE + "LIST=" + listCount + RESET);
        if (tableCount > 0) System.out.print("  " + GRAY + "TABLE=" + tableCount + RESET);
        if (quoteCount > 0) System.out.print("  " + GRAY + "QUOTE=" + quoteCount + RESET);
        if (bodyCount > 0) System.out.print("  BODY=" + bodyCount);
        if (blankCount > 0) System.out.print("  " + DIM + "BLANK=" + blankCount + RESET);
        if (noiseCount > 0) System.out.print("  " + RED + "NOISE=" + noiseCount + RESET);
        System.out.println();

        // ─────────────────────────────────────────────
        //  关键断言
        // ─────────────────────────────────────────────
        // 确保总有 DOCUMENT_TITLE 虚拟信号
        assertEquals(DocumentStructureNodeSignalEnum.DOCUMENT_TITLE, signals.get(0).getKind(),
                "第一条信号必须是 DOCUMENT_TITLE");
        assertEquals(0, signals.get(0).getLogicalLineNo(), "DOCUMENT_TITLE 的 logicalLineNo 必须为 0");

        // 确保信号数量 = 逻辑行数 + 1（DOCUMENT_TITLE）
        assertEquals(batch.contextLines().size() + 1, signals.size(),
                "信号数应为 contextLines + 1 条（含 DOCUMENT_TITLE）");

        // 确保每个信号有置信度
        for (DocumentStructureNodeSignal sig : signals) {
            assertTrue(sig.getConfidence() > 0, "每条信号应有正置信度");
            assertNotNull(sig.getKind(), "每条信号应有类型");
        }

        printSeparator('═', 100);
        System.out.println();
    }

    // ============ 快速验证测试 ============

    @Test
    @DisplayName("Markdown 标题应被正确分类为 HEADING")
    void markdownHeadingsShouldBeClassifiedAsHeading() {
        String text = """
                # 第一章 概述
                这是正文内容。
                ## 1.1 背景
                更多正文。
                ### 三级标题
                详细内容。""";

        DocumentStructureNodeSignalBatch batch = extractor.extract("测试文档", text);
        List<DocumentStructureNodeSignal> signals = batch.signals();

        // 查找 HEADING 信号
        List<DocumentStructureNodeSignal> headings = signals.stream()
                .filter(s -> s.getKind() == DocumentStructureNodeSignalEnum.HEADING)
                .toList();
        assertEquals(3, headings.size(), "应有 3 个 Markdown 标题");
        assertEquals("第一章 概述", headings.get(0).getTitle());
        assertEquals(1, headings.get(0).getLevelHint()); // #
        assertEquals(2, headings.get(1).getLevelHint()); // ##
        assertEquals(3, headings.get(2).getLevelHint()); // ###
    }

    @Test
    @DisplayName("中文序号连续行应被分类为 LIST_ITEM 而非 HEADING_CANDIDATE")
    void sequentialChineseNumbersShouldBeListItems() {
        String text = """
                一、项目启动
                二、需求分析
                三、系统设计
                四、开发实现""";

        DocumentStructureNodeSignalBatch batch = extractor.extract("顺序测试", text);
        List<DocumentStructureNodeSignal> items = batch.signals().stream()
                .filter(s -> s.getKind() == DocumentStructureNodeSignalEnum.LIST_ITEM)
                .toList();
        assertEquals(4, items.size(), "连续中文序号应全部分类为 LIST_ITEM");
    }

    @Test
    @DisplayName("中文章节应被分类为 HEADING")
    void chineseChapterShouldBeHeading() {
        String text = """
                第一章 总则
                这是总则的内容。
                第二章 权利与义务
                权利与义务的详细描述。""";

        DocumentStructureNodeSignalBatch batch = extractor.extract("章程", text);
        List<DocumentStructureNodeSignal> headings = batch.signals().stream()
                .filter(s -> s.getKind() == DocumentStructureNodeSignalEnum.HEADING)
                .filter(s -> s.getTitle() != null && s.getTitle().contains("总则") || s.getTitle() != null && s.getTitle().contains("权利"))
                .toList();
        assertEquals(2, headings.size(), "中文章节应识别为 HEADING");
    }

    @Test
    @DisplayName("空白行应被分类为 BLANK")
    void blankLinesShouldBeBlank() {
        String text = """
                段落一

                段落二

                段落三""";

        DocumentStructureNodeSignalBatch batch = extractor.extract("空白测试", text);
        long blankCount = batch.signals().stream()
                .filter(s -> s.getKind() == DocumentStructureNodeSignalEnum.BLANK)
                .count();
        assertEquals(2, blankCount, "应有 2 个空白行");
    }

    // ============ 辅助方法 ============

    /** 发现 test-documents/ 下的所有测试文档 */
    static Stream<Path> testDocumentPaths() throws IOException, URISyntaxException {
        // 尝试从 classpath 加载
        var classLoader = DocumentSignalExtractorDisplayTest.class.getClassLoader();
        var resource = classLoader.getResource("test-documents");
        if (resource != null) {
            Path dir = Paths.get(resource.toURI());
            try (var files = Files.list(dir)) {
                return files
                        .filter(p -> p.getFileName().toString().endsWith(".md")
                                || p.getFileName().toString().endsWith(".txt"))
                        .sorted()
                        .toList().stream();
            }
        }
        // 回退：直接从项目根目录找
        Path fallback = Paths.get("business/document/src/test/resources/test-documents");
        if (Files.isDirectory(fallback)) {
            try (var files = Files.list(fallback)) {
                return files
                        .filter(p -> p.getFileName().toString().endsWith(".md")
                                || p.getFileName().toString().endsWith(".txt"))
                        .sorted()
                        .toList().stream();
            }
        }
        return Stream.empty();
    }

    // ─── 格式化辅助 ───

    private static String fmtConfidence(double c) {
        if (c >= 0.95) return String.format("%.0f%%", c * 100);
        if (c >= 0.80) return String.format("%.0f%%", c * 100);
        return String.format("%.0f%%", c * 100);
    }

    private static String fmtLevel(Integer l) {
        return l == null ? "-" : String.valueOf(l);
    }

    private static String fmtIndent(Integer i) {
        return i == null || i == 0 ? "-" : String.valueOf(i);
    }

    private static String fmtCode(String c) {
        return c == null || c.isEmpty() ? "-" : c;
    }

    private static String fmtSeq(Integer s) {
        return s == null ? "-" : String.valueOf(s);
    }

    private static void printSeparator(char ch, int len) {
        System.out.println(DIM + String.valueOf(ch).repeat(len) + RESET);
    }

    /** 生成带交叉点的横线，用于表格边框 */
    private static String borderLine(char left, char cross, char right, int[] widths) {
        StringBuilder sb = new StringBuilder();
        sb.append(left);
        for (int i = 0; i < widths.length; i++) {
            sb.append("─".repeat(widths[i]));
            if (i < widths.length - 1) sb.append(cross);
        }
        sb.append(right);
        return DIM + sb.toString() + RESET;
    }

    // ─── CJK 宽字符安全对齐 ───

    /** 去除 ANSI 转义序列 */
    private static final java.util.regex.Pattern ANSI_ESCAPE =
            java.util.regex.Pattern.compile("\\u001B\\[[;\\d]*m");

    /** 计算字符串在终端中的可见列宽（CJK / 全角字符 = 2，ASCII = 1） */
    private static int terminalWidth(String s) {
        if (s == null || s.isEmpty()) return 0;
        int w = 0;
        String clean = ANSI_ESCAPE.matcher(s).replaceAll("");
        for (int i = 0; i < clean.length(); i++) {
            w += isCjkOrFullWidth(clean.charAt(i)) ? 2 : 1;
        }
        return w;
    }

    private static boolean isCjkOrFullWidth(int cp) {
        // CJK 统一表意文字
        if (cp >= 0x4E00 && cp <= 0x9FFF) return true;
        // CJK 扩展 A
        if (cp >= 0x3400 && cp <= 0x4DBF) return true;
        // CJK 兼容表意文字
        if (cp >= 0xF900 && cp <= 0xFAFF) return true;
        // 全角 ASCII / 半角片假名 → 全角
        if (cp >= 0xFF01 && cp <= 0xFF60) return true;
        // 全角空格等
        if (cp >= 0xFFE0 && cp <= 0xFFE6) return true;
        // 中文标点 (、。！？等)
        if (cp >= 0x3000 && cp <= 0x303F) return true;
        // 日文平假名
        if (cp >= 0x3040 && cp <= 0x309F) return true;
        // 日文片假名
        if (cp >= 0x30A0 && cp <= 0x30FF) return true;
        // 韩文
        if (cp >= 0xAC00 && cp <= 0xD7AF) return true;
        return false;
    }

    /** 纯文本左对齐填充到指定终端列宽 */
    private static String fixedPad(String text, int targetWidth) {
        int w = terminalWidth(text);
        if (w >= targetWidth) return text + " ";
        return text + " ".repeat(targetWidth - w + 1);
    }

    /** 带颜色的文本左对齐填充到指定终端列宽（颜色码不计宽） */
    private static String colorPad(String text, int targetWidth, String color, boolean bold) {
        String inner = bold ? (color + BOLD + text) : (color + text);
        int w = terminalWidth(inner);
        if (w >= targetWidth) return inner + " ";
        return inner + " ".repeat(targetWidth - w + 1);
    }
}
