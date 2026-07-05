package com.reubenagent.document.support;

import org.apache.tika.Tika;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Locale;

/**
 * 直接看 extractRawText + cleanupText 的效果。
 * 就是这几行代码，不搞别的。
 *
 * <pre>{@code
 *   mvn test -pl business/document -am -Dtest=DocumentRawTextDisplayTest -Dsurefire.failIfNoSpecifiedTests=false
 * }</pre>
 */
@DisplayName("原始文本提取效果")
class DocumentRawTextDisplayTest {

    private static final Tika TIKA = new Tika();

    /** 代表每种格式各选一个 */
    private static final List<String> FILES = List.of(
            "深度学习与RAG技术.txt",
            "RAG系统技术指南.md",
            "wikipedia-Kafka.html",
            "RAG系统技术白皮书.docx",
            "RAG综述论文.pdf"
    );

    @Test
    @DisplayName("展示每种格式的提取结果")
    void showRawText() throws Exception {
        Path dir = findProjectRoot().resolve("docs/doc-to-test");

        for (String name : FILES) {
            Path file = dir.resolve(name);
            byte[] bytes = Files.readAllBytes(file);
            String ext = extension(name);

            // ---- 就是这两行 ----
            String raw = (ext.equals("txt") || ext.equals("md"))
                    ? new String(bytes, StandardCharsets.UTF_8)        // TXT/MD: UTF-8
                    : TIKA.parseToString(new ByteArrayInputStream(bytes)); // 其他: Tika

            String cleaned = cleanup(raw);
            // -------------------

            System.out.println();
            System.out.println("═══════════════════════════════════════════════════════");
            System.out.println("  " + name + "  (" + ext.toUpperCase() + ", " + formatSize(bytes.length) + " → " + cleaned.length() + " chars)");
            System.out.println("═══════════════════════════════════════════════════════");
            System.out.println(cleaned.length() > 800
                    ? cleaned.substring(0, 800) + "\n  ...(truncated, total " + cleaned.length() + " chars)"
                    : cleaned);
        }
    }

    /** 与 DocumentParseResultServiceImpl.cleanupText 一致 */
    private static String cleanup(String s) {
        if (s == null) return "";
        return s.replace("\r\n", "\n").replace('\r', '\n').replace('\0', ' ')
                .replaceAll("[\\t\\x0B\\f]+", " ")
                .replaceAll("\\n{3,}", "\n\n")
                .replaceAll("[ ]{2,}", " ").trim();
    }

    private static Path findProjectRoot() {
        Path d = Paths.get("").toAbsolutePath();
        while (d != null && !Files.exists(d.resolve("docs/doc-to-test"))) d = d.getParent();
        return d != null ? d : Paths.get("").toAbsolutePath();
    }

    private static String extension(String n) {
        int dot = n.lastIndexOf('.'); return dot < 0 ? "" : n.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private static String formatSize(long b) {
        if (b < 1024) return b + "B";
        if (b < 1024 * 1024) return String.format("%.1fKB", b / 1024.0);
        return String.format("%.1fMB", b / (1024.0 * 1024.0));
    }
}
