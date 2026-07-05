package com.reubenagent.document.support;

import com.reubenagent.document.config.DocumentProperties;
import com.reubenagent.document.enums.DocumentFileTypeEnum;
import com.reubenagent.document.model.DocumentIntermediateStructureNode;
import com.reubenagent.document.model.DocumentParseResult;
import com.reubenagent.document.service.IDocumentParseResultService;
import com.reubenagent.document.service.impl.DocumentParseResultServiceImpl;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.ObjectProvider;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 直接调用 {@link DocumentParseResultServiceImpl#parse} 展示完整解析结果。
 *
 * <p>手动构造四阶段管线组件，跳过 LLM 歧义消解（无 ChatModel），
 * 覆盖 TXT / MD / HTML / DOCX / PDF 全部格式。</p>
 *
 * <p>运行方式：</p>
 * <pre>{@code
 *   mvn test -pl business/document -am \
 *     -Dtest=DocumentParseResultDisplayTest#testFullParse \
 *     -Dsurefire.failIfNoSpecifiedTests=false
 * }</pre>
 *
 * @author reuben
 * @since 2026-07-04
 */
@DisplayName("完整 parse() 调用效果展示")
class DocumentParseResultFullDisplayTest {

    private static IDocumentParseResultService parseResultService;

    private static final Path DOC_DIR = findProjectRoot().resolve("docs/doc-to-test");

    // ========================================================================
    // 配色
    // ========================================================================

    private static final String R = "\033[0m";
    private static final String B = "\033[1m";
    private static final String D = "\033[2m";
    private static final String CYAN = "\033[36m";
    private static final String GREEN = "\033[32m";
    private static final String YELLOW = "\033[33m";
    private static final String MAGENTA = "\033[35m";
    private static final String BLUE = "\033[34m";
    private static final String RED = "\033[31m";

    // 节点类型对应图标/颜色
    private static final String[] NODE_ICONS = {"", "📄", "📑", "📋", "📌"}; // ROOT, CHAPTER, STEP, LIST_ITEM, BODY
    private static final String[] NODE_COLORS = {D, GREEN, CYAN, BLUE, D};

    // ========================================================================
    // 初始化
    // ========================================================================

    @BeforeAll
    static void setUp() {
        DocumentProperties properties = new DocumentProperties();
        // LLM 歧义消解关闭，不会去取 ChatModel
        properties.setStructureParsing(new DocumentProperties.StructureParsing());
        properties.getStructureParsing().setLlmDisambiguationEnabled(false);

        // 手动装配四阶段管线
        DocumentStructureNodeSignalExtractor signalExtractor = new DocumentStructureNodeSignalExtractor();
        DocumentStructureNodeAmbiguityResolver ambiguityResolver =
                new DocumentStructureNodeAmbiguityResolver(properties, new NoOpChatModelProvider(), null);
        DocumentStructureHierarchyResolver hierarchyResolver = new DocumentStructureHierarchyResolver();
        DocumentStructureTreeValidator treeValidator = new DocumentStructureTreeValidator();

        DocumentStructureNodeExtractor structureExtractor = new DocumentStructureNodeExtractor(
                signalExtractor, ambiguityResolver, hierarchyResolver, treeValidator);

        parseResultService = new DocumentParseResultServiceImpl(structureExtractor);
    }

    /**
     * 空壳 ObjectProvider —— ambiguity resolver 需要它但我们的 LLM 开关关闭，不会调到。
     */
    private static class NoOpChatModelProvider implements ObjectProvider<org.springframework.ai.chat.model.ChatModel> {
        @Override
        public org.springframework.ai.chat.model.ChatModel getIfAvailable() { return null; }
        @Override
        public org.springframework.ai.chat.model.ChatModel getObject() { return null; }
        @Override
        public org.springframework.ai.chat.model.ChatModel getObject(Object... args) { return null; }
        @Override
        public org.springframework.ai.chat.model.ChatModel getIfUnique() { return null; }
    }

    // ========================================================================
    // 测试数据源 —— 每种格式各取一个代表
    // ========================================================================

    static Stream<Path> sampleFiles() throws IOException {
        if (!Files.isDirectory(DOC_DIR)) {
            fail("测试文档目录不存在: " + DOC_DIR.toAbsolutePath());
        }

        // 每种格式选一个代表 + 加一个长文档
        List<String> samples = List.of(
                "深度学习与RAG技术.txt",
                "RAG系统技术指南.md",
                "wikipedia-Kafka.html",
                "RAG系统技术白皮书.docx",
                "RAG综述论文.pdf"
        );

        return samples.stream()
                .map(DOC_DIR::resolve)
                .filter(Files::exists)
                .sorted(Comparator.comparing((Path p) -> {
                    String n = p.getFileName().toString().toLowerCase(Locale.ROOT);
                    if (n.endsWith(".txt")) return 0;
                    if (n.endsWith(".md")) return 1;
                    if (n.endsWith(".html")) return 2;
                    if (n.endsWith(".docx")) return 3;
                    return 4;
                }));
    }

    // ========================================================================
    // 测试
    // ========================================================================

    @ParameterizedTest
    @MethodSource("sampleFiles")
    @DisplayName("完整 parse() 调用并展示结果")
    void testFullParse(Path filePath) throws IOException {
        String fileName = filePath.getFileName().toString();
        String ext = extension(fileName);
        long fileSize = Files.size(filePath);
        byte[] bytes = Files.readAllBytes(filePath);

        DocumentFileTypeEnum fileType = mapFileType(ext);
        String mediaType = guessMediaType(ext);

        // ★ 核心调用
        long start = System.nanoTime();
        DocumentParseResult result = parseResultService.parse(bytes, fileName, mediaType, fileType);
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        // ================================================================
        // 输出
        // ================================================================
        System.out.println();
        System.out.println(B + GREEN + "╔══════════════════════════════════════════════════════════════════════╗" + R);
        System.out.println(B + GREEN + "║  " + fileName + R);
        System.out.println(B + GREEN + "╚══════════════════════════════════════════════════════════════════════╝" + R);

        // 基本信息
        System.out.printf("  %s格式%s: %s  |  %s原文件%s: %s  |  %s耗时%s: %d ms%n",
                CYAN, R, ext.toUpperCase(),
                CYAN, R, formatSize(fileSize),
                CYAN, R, elapsedMs);

        // 统计指标
        System.out.println();
        System.out.println("  " + B + "── 统计指标 ──" + R);
        System.out.printf("  字符数: %s%d  |  Token 估算: %s%d  |  段落数: %s%d  |  标题数: %s%d  |  最长段落: %s%d 字符%n",
                MAGENTA, result.getCharCount(),
                MAGENTA, result.getTokenCount(),
                BLUE, result.getParagraphCount(),
                GREEN, result.getHeadingCount(),
                YELLOW, result.getMaxParagraphLength());
        System.out.printf("  结构等级: %s%d%s (%s)  |  内容质量: %s%d%s (%s)%n",
                colorLevel(result.getStructureLevel()), result.getStructureLevel(),
                R, describeStructureLevel(result.getStructureLevel()),
                colorLevel(result.getContentQualityLevel()), result.getContentQualityLevel(),
                R, describeQualityLevel(result.getContentQualityLevel()));

        // 提取文本前 500 字预览
        System.out.println();
        System.out.println("  " + B + "── 提取文本预览 (前 500 字) ──" + R);
        String text = result.getParsedText();
        String preview = text.length() > 500 ? text.substring(0, 500) + "..." : text;
        System.out.println("  " + D + preview.replace("\n", "\n  ") + R);

        // 结构节点树
        List<DocumentIntermediateStructureNode> nodes = result.getStructureNodes();
        System.out.println();
        System.out.println("  " + B + "── 结构节点树 (" + nodes.size() + " 个节点) ──" + R);

        if (nodes == null || nodes.isEmpty()) {
            System.out.println("  " + D + "(无结构节点)" + R);
        } else {
            for (DocumentIntermediateStructureNode node : nodes) {
                printNode(node, "", true);
            }
        }

        // 断言
        assertNotNull(result.getParsedText(), "提取文本不应为 null");
        assertFalse(result.getParsedText().isEmpty(), fileName + " 提取文本不应为空");
        assertTrue(result.getCharCount() > 0, "字符数应 > 0");
        assertNotNull(result.getStructureNodes(), "结构节点不应为 null");
        assertFalse(result.getStructureNodes().isEmpty(), "至少应有 ROOT 节点");
    }

    // ========================================================================
    // 树形打印
    // ========================================================================

    private void printNode(DocumentIntermediateStructureNode node, String prefix, boolean isLast) {
        String connector = isLast ? "└── " : "├── ";
        int nodeType = node.getNodeType() != null ? node.getNodeType() : 5;
        String icon = nodeType >= 1 && nodeType <= 4 ? NODE_ICONS[nodeType] : NODE_ICONS[4];
        String color = nodeType >= 1 && nodeType <= 4 ? NODE_COLORS[nodeType] : NODE_COLORS[4];

        String title = node.getTitle();
        if (title != null && title.length() > 50) {
            title = title.substring(0, 47) + "...";
        }

        String typeName = nodeTypeName(nodeType);
        String sectionPath = node.getSectionPath();
        String pathInfo = (sectionPath != null && !sectionPath.isEmpty()) ? " " + D + sectionPath + R : "";

        System.out.printf("  %s%s%s %s%s%s %s(depth=%d)%s%s%n",
                prefix, connector,
                color, icon, typeName, R,
                D, node.getDepth() != null ? node.getDepth() : 0, R,
                title != null ? " " + title : "");

        if (pathInfo.length() > 4) {
            System.out.printf("  %s    %s%n", prefix, pathInfo);
        }
    }

    // ========================================================================
    // 工具方法
    // ========================================================================

    private static DocumentFileTypeEnum mapFileType(String ext) {
        return switch (ext) {
            case "pdf" -> DocumentFileTypeEnum.PDF;
            case "docx", "doc" -> DocumentFileTypeEnum.DOCX;
            case "txt" -> DocumentFileTypeEnum.TXT;
            case "md" -> DocumentFileTypeEnum.MD;
            case "html", "htm" -> DocumentFileTypeEnum.HTML;
            default -> null;
        };
    }

    private static String guessMediaType(String ext) {
        return switch (ext) {
            case "pdf" -> "application/pdf";
            case "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
            case "txt" -> "text/plain";
            case "md" -> "text/markdown";
            case "html", "htm" -> "text/html";
            default -> null;
        };
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

    static String colorLevel(int level) {
        return switch (level) {
            case 3, 5 -> GREEN;
            case 2 -> YELLOW;
            default -> D;
        };
    }

    static String describeStructureLevel(int level) {
        return switch (level) {
            case 3 -> "高 (标题≥5)";
            case 2 -> "中 (标题≥2)";
            case 1 -> "低 (段落≥3)";
            default -> "未知";
        };
    }

    static String describeQualityLevel(int level) {
        return switch (level) {
            case 5 -> "高";
            case 3 -> "中";
            default -> "低";
        };
    }

    static String nodeTypeName(int code) {
        return switch (code) {
            case 1 -> "ROOT";
            case 2 -> "CHAPTER";
            case 3 -> "STEP";
            case 4 -> "LIST_ITEM";
            default -> "UNKNOWN";
        };
    }

    private static Path findProjectRoot() {
        Path dir = Paths.get("").toAbsolutePath();
        while (dir != null && !Files.exists(dir.resolve("docs/doc-to-test"))) {
            dir = dir.getParent();
        }
        return dir != null ? dir : Paths.get("").toAbsolutePath();
    }
}
