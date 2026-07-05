package com.reubenagent.document.support;

import org.apache.tika.Tika;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 文档文本提取对比测试 —— 遍历 docs/doc-to-test/ 下所有文档，
 * 模拟 {@code DocumentParseResultServiceImpl} 的提取逻辑：
 * TXT/MD 直接 UTF-8 解码，PDF/DOCX/HTML 走 Apache Tika，
 * 输出提取效果对比（字符数、耗时、前 500 字预览）。
 *
 * <p>运行方式：</p>
 * <pre>{@code
 *   mvn test -pl business/document -am -Dtest=DocumentParseResultDisplayTest -Dsurefire.failIfNoSpecifiedTests=false
 * }</pre>
 *
 * @author reuben
 * @since 2026-07-04
 */
@DisplayName("文档文本提取效果对比 (UTF-8 vs Tika)")
class DocumentParseResultDisplayTest {

    private static final Tika TIKA = new Tika();

    /**
     * 测试文档目录 —— 自动从模块目录向上查找项目根目录。
     * <p>Maven 在子模块执行时 CWD 为 business/document/，需要回退两级到项目根。</p>
     */
    private static final Path DOC_DIR = findProjectRoot().resolve("docs/doc-to-test");

    private static Path findProjectRoot() {
        Path dir = Paths.get("").toAbsolutePath();
        while (dir != null && !Files.exists(dir.resolve("docs/doc-to-test"))) {
            dir = dir.getParent();
        }
        return dir != null ? dir : Paths.get("").toAbsolutePath();
    }

    /** 已知文本类格式（直接 UTF-8 解码） */
    private static final List<String> TEXT_FORMATS = List.of("txt", "md");

    // ========================================================================
    // 配色常量
    // ========================================================================

    private static final String R = "[0m";
    private static final String B = "[1m";
    private static final String D = "[2m";
    private static final String CYAN = "[36m";
    private static final String GREEN = "[32m";
    private static final String YELLOW = "[33m";
    private static final String RED = "[31m";
    private static final String BLUE = "[34m";
    private static final String MAGENTA = "[35m";

    // ========================================================================
    // 测试数据源
    // ========================================================================

    static Stream<Path> documentFiles() throws IOException {
        if (!Files.isDirectory(DOC_DIR)) {
            fail("测试文档目录不存在: " + DOC_DIR.toAbsolutePath() + "\n请确保在项目根目录运行测试");
        }
        return Files.list(DOC_DIR)
                .filter(Files::isRegularFile)
                .filter(p -> {
                    String name = p.getFileName().toString().toLowerCase(Locale.ROOT);
                    return name.endsWith(".txt") || name.endsWith(".md")
                            || name.endsWith(".pdf") || name.endsWith(".docx")
                            || name.endsWith(".html") || name.endsWith(".htm");
                })
                .sorted(Comparator.comparing((Path p) -> {
                    // 按格式分组排序：TXT → MD → HTML → DOCX → PDF
                    String n = p.getFileName().toString().toLowerCase(Locale.ROOT);
                    if (n.endsWith(".txt")) return 0;
                    if (n.endsWith(".md")) return 1;
                    if (n.endsWith(".html") || n.endsWith(".htm")) return 2;
                    if (n.endsWith(".docx")) return 3;
                    return 4;
                }).thenComparing(p -> p.getFileName().toString()));
    }

    // ========================================================================
    // 参数化测试
    // ========================================================================

    @ParameterizedTest
    @MethodSource("documentFiles")
    @DisplayName("提取所有文档格式并对比效果")
    void testExtractAllFormats(Path filePath) throws IOException {
        String fileName = filePath.getFileName().toString();
        String ext = extension(fileName);
        long fileSize = Files.size(filePath);
        byte[] bytes = Files.readAllBytes(filePath);

        // 判断提取方式
        boolean isTextFormat = TEXT_FORMATS.contains(ext);
        String method = isTextFormat ? "UTF-8 解码" : "Apache Tika";

        // 提取 + 计时
        long start = System.nanoTime();
        String rawText;
        String errorMsg = null;

        try {
            rawText = isTextFormat
                    ? new String(bytes, StandardCharsets.UTF_8)
                    : TIKA.parseToString(new ByteArrayInputStream(bytes));
        } catch (Exception e) {
            rawText = "";
            errorMsg = e.getClass().getSimpleName() + ": " + e.getMessage();
        }

        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        // 清洗（与 DocumentParseResultServiceImpl.cleanupText 一致）
        String cleanedText = cleanupText(rawText);

        // 乱码率
        long brokenChars = cleanedText.chars().filter(c -> c == '�').count();
        double brokenRatio = cleanedText.isEmpty() ? 0 : (double) brokenChars / cleanedText.length();

        // 输出
        String color = errorMsg != null ? RED
                : brokenRatio > 0.02 ? RED
                : brokenRatio > 0.005 ? YELLOW
                : GREEN;

        System.out.println();
        System.out.println(B + color + "━━━ " + fileName + " ━━━" + R);
        System.out.printf("  %s%-12s%s  %s%-10s%s  %s%8s%s  %s%6d ms%s  %s%8d chars%s  %s乱码 %.2f%%%s%n",
                CYAN, ext.toUpperCase(), R,
                BLUE, method, R,
                D, formatSize(fileSize), R,
                YELLOW, elapsedMs, R,
                MAGENTA, cleanedText.length(), R,
                brokenRatio > 0.005 ? RED : GREEN, brokenRatio * 100, R);

        if (errorMsg != null) {
            System.out.println("  " + RED + "✗ 提取失败: " + errorMsg + R);
        } else {
            // 前 500 字符预览
            String preview = cleanedText.length() > 500
                    ? cleanedText.substring(0, 500) + "..."
                    : cleanedText;
            System.out.println("  " + D + preview.replace("\n", "\n  ") + R);
        }

        // 断言
        assertNotNull(rawText, "提取结果不应为 null");
        if (errorMsg == null) {
            assertFalse(rawText.isEmpty() || cleanedText.isEmpty(),
                    fileName + " 提取结果不应为空（文件 " + formatSize(fileSize) + "）");
        }
    }

    // ========================================================================
    // 汇总报告（单独测试，汇总所有结果）
    // ========================================================================

    @Test
    @DisplayName("汇总报告 —— 所有文档的提取效果一览")
    void summaryReport() throws IOException {
        List<Path> files;
        try (Stream<Path> s = documentFiles()) {
            files = s.toList();
        }

        System.out.println();
        System.out.println(B + "══════════════════════════════════════════════════════════════════════════════" + R);
        System.out.println(B + "  文档文本提取汇总报告 (" + files.size() + " 个文件)" + R);
        System.out.println(B + "══════════════════════════════════════════════════════════════════════════════" + R);
        System.out.printf("  %-45s %5s %10s %8s %7s %7s %6s%n",
                "文件", "格式", "原文件大小", "方式", "耗时ms", "提取字符", "乱码%");
        System.out.println("  " + "─".repeat(95));

        long totalOriginalSize = 0;
        long totalExtractedChars = 0;
        long totalTimeMs = 0;
        int successCount = 0;
        int failCount = 0;

        for (Path filePath : files) {
            String fileName = filePath.getFileName().toString();
            String ext = extension(fileName);
            long fileSize = Files.size(filePath);
            byte[] bytes = Files.readAllBytes(filePath);

            boolean isTextFormat = TEXT_FORMATS.contains(ext);
            String method = isTextFormat ? "UTF-8" : "Tika";

            long start = System.nanoTime();
            String rawText;
            String errorFlag;
            try {
                rawText = isTextFormat
                        ? new String(bytes, StandardCharsets.UTF_8)
                        : TIKA.parseToString(new ByteArrayInputStream(bytes));
                errorFlag = null;
            } catch (Exception e) {
                rawText = "";
                errorFlag = e.getClass().getSimpleName();
            }
            long elapsedMs = (System.nanoTime() - start) / 1_000_000;

            String cleanedText = cleanupText(rawText);
            long brokenChars = cleanedText.chars().filter(c -> c == '�').count();
            double brokenRatio = cleanedText.isEmpty() ? 0 : (double) brokenChars / cleanedText.length();

            totalOriginalSize += fileSize;
            totalExtractedChars += cleanedText.length();
            totalTimeMs += elapsedMs;
            if (errorFlag == null) successCount++;
            else failCount++;

            String color = errorFlag != null ? RED
                    : brokenRatio > 0.02 ? RED
                    : brokenRatio > 0.005 ? YELLOW
                    : GREEN;

            String shortName = fileName.length() > 43 ? fileName.substring(0, 40) + "..." : fileName;
            System.out.printf("  %s%-45s%s %5s %10s %8s %6d %s%7d%s %s%5.1f%%%s%n",
                    color, shortName, R,
                    ext.toUpperCase(),
                    formatSize(fileSize),
                    method,
                    elapsedMs,
                    color, cleanedText.length(), R,
                    brokenRatio > 0.005 ? RED : (brokenRatio > 0 ? YELLOW : GREEN), brokenRatio * 100, R);

            if (errorFlag != null) {
                System.out.printf("    %s✗ 异常: %s%s%n", RED, errorFlag, R);
            }
        }

        System.out.println("  " + "─".repeat(95));
        System.out.printf("  %s合计%s: 成功 %d/%d  |  原文件 %s  |  提取文本 %s  |  总耗时 %d ms  |  文本/原文件 %.1f%%%n",
                B, R,
                successCount, files.size(),
                formatSize(totalOriginalSize),
                formatSize(totalExtractedChars),
                totalTimeMs,
                totalOriginalSize > 0 ? (double) totalExtractedChars / totalOriginalSize * 100 : 0);
        System.out.println(B + "══════════════════════════════════════════════════════════════════════════════" + R);

        assertEquals(files.size(), successCount,
                "所有文档均应提取成功，失败 " + failCount + " 个");
    }

    // ========================================================================
    // 工具方法（与 DocumentParseResultServiceImpl 保持一致）
    // ========================================================================

    /**
     * 文本清洗 —— 与 {@code DocumentParseResultServiceImpl.cleanupText} 逻辑一致。
     */
    static String cleanupText(String rawText) {
        if (rawText == null) return "";
        return rawText
                .replace("\r\n", "\n")
                .replace('\r', '\n')
                .replace('\0', ' ')
                .replaceAll("[\\t\\x0B\\f]+", " ")
                .replaceAll("\\n{3,}", "\n\n")
                .replaceAll("[ ]{2,}", " ")
                .trim();
    }

    static String extension(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot < 0 ? "" : fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    static String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        return String.format("%.1f MB", bytes / (1024.0 * 1024.0));
    }
}
