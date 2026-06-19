package com.reubenagent.document.support;

import com.reubenagent.document.config.DocumentProperties;
import com.reubenagent.document.enums.DocumentStructureNodeSignalEnum;
import com.reubenagent.document.enums.DocumentStructureNodeTypeEnum;
import com.reubenagent.document.model.DocumentIntermediateStructureNode;
import com.reubenagent.document.model.DocumentStructureNodeSignal;
import com.reubenagent.document.model.DocumentStructureNodeSignalBatch;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.deepseek.DeepSeekChatModel;
import org.springframework.ai.deepseek.DeepSeekChatOptions;
import org.springframework.ai.deepseek.api.DeepSeekApi;
import org.springframework.beans.factory.ObjectProvider;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 层级解析展示测试 —— 遍历 test-documents/ 下所有测试文档，
 * 运行 Stage 1（规则引擎）+ Stage 2（LLM 消解）+ Stage 3（层级解析），
 * 以树形结构输出信号→节点归并结果。
 *
 * <h3>🔑 API Key 配置（按优先级从高到低）</h3>
 * <ol>
 *   <li>系统属性: {@code -Dspring.ai.deepseek.api-key=sk-xxx}</li>
 *   <li>环境变量: {@code SPRING_AI_DEEPSEEK_API_KEY}</li>
 *   <li><b>.env 文件</b> — 编辑 {@code business/document/.env}（推荐 IDE 用户）</li>
 *   <li>application-test.yml — 编辑 {@code spring.ai.deepseek.api-key}</li>
 * </ol>
 *
 * <h3>运行方式</h3>
 * <pre>{@code
 *   # IDE: 编辑 business/document/.env → 点小三角运行
 *   # CLI:
 *   mvn test -pl business/document -am -Dtest=DocumentStructureHierarchyResolverDisplayTest
 * }</pre>
 *
 * @author reuben
 * @since 2026-06-19
 */
@DisplayName("层级解析 - 展示测试")
class DocumentStructureHierarchyResolverDisplayTest {

    // =========================================================================
    // 配置
    // =========================================================================
    private static String apiKey;
    private static String modelName;
    private static String apiBaseUrl;
    private static double temperature;

    // =========================================================================
    // 组件
    // =========================================================================
    private static DocumentStructureNodeSignalExtractor signalExtractor;
    private static DocumentStructureNodeAmbiguityResolver ambiguityResolver;
    private static DocumentStructureHierarchyResolver hierarchyResolver;
    private static boolean llmAvailable;

    // =========================================================================
    // 颜色
    // =========================================================================
    private static final String RESET = "[0m";
    private static final String BOLD = "[1m";
    private static final String CYAN = "[36m";
    private static final String GREEN = "[32m";
    private static final String YELLOW = "[33m";
    private static final String RED = "[31m";
    private static final String BLUE = "[34m";
    private static final String MAGENTA = "[35m";
    private static final String DIM = "[2m";
    private static final String ORANGE = "[38;5;214m";

    private static final Map<Integer, String> NODE_TYPE_COLOR = Map.of(
            DocumentStructureNodeTypeEnum.ROOT.getCode(), MAGENTA,
            DocumentStructureNodeTypeEnum.CHAPTER.getCode(), GREEN,
            DocumentStructureNodeTypeEnum.STEP.getCode(), CYAN,
            DocumentStructureNodeTypeEnum.LIST_ITEM.getCode(), BLUE
    );

    private static final Map<Integer, String> NODE_TYPE_LABEL = Map.of(
            DocumentStructureNodeTypeEnum.ROOT.getCode(), "ROOT",
            DocumentStructureNodeTypeEnum.CHAPTER.getCode(), "CHAPTER",
            DocumentStructureNodeTypeEnum.STEP.getCode(), "STEP",
            DocumentStructureNodeTypeEnum.LIST_ITEM.getCode(), "LIST"
    );

    private static final Map<DocumentStructureNodeSignalEnum, String> KIND_COLOR = Map.of(
            DocumentStructureNodeSignalEnum.HEADING, GREEN,
            DocumentStructureNodeSignalEnum.HEADING_CANDIDATE, YELLOW,
            DocumentStructureNodeSignalEnum.LIST_ITEM, BLUE,
            DocumentStructureNodeSignalEnum.BODY, RESET,
            DocumentStructureNodeSignalEnum.STEP_ITEM, CYAN
    );

    // =========================================================================
    // 初始化 — 手工装配管线（不依赖 Spring 容器）
    // =========================================================================

    @BeforeAll
    static void setUpPipeline() {
        // Stage 1 & Stage 3 无需外部依赖
        signalExtractor = new DocumentStructureNodeSignalExtractor();
        hierarchyResolver = new DocumentStructureHierarchyResolver();

        // Stage 2: 配置加载（优先级: 系统属性 > 环境变量 > .env > application-test.yml）
        Map<String, String> config = loadConfig();
        apiKey = config.getOrDefault("api-key", "");
        modelName = config.getOrDefault("model", "deepseek-v4-flash");
        apiBaseUrl = config.getOrDefault("base-url", "https://api.deepseek.com");
        temperature = Double.parseDouble(config.getOrDefault("temperature", "0.2"));

        if (apiKey.isBlank() || apiKey.startsWith("fake-") || apiKey.startsWith("sk-your-")) {
            System.err.println();
            System.err.println("  ⚠️  未设置有效的 DeepSeek API Key，LLM Stage 2 将跳过。");
            System.err.println("  Stage 1 (规则引擎) + Stage 3 (层级解析) 仍会完整运行。");
            System.err.println();
            llmAvailable = false;
        } else {
            try {
                DeepSeekApi deepSeekApi = DeepSeekApi.builder()
                        .apiKey(apiKey)
                        .baseUrl(apiBaseUrl)
                        .build();
                DeepSeekChatOptions options = DeepSeekChatOptions.builder()
                        .model(modelName)
                        .temperature(temperature)
                        .build();
                ChatModel chatModel = DeepSeekChatModel.builder()
                        .deepSeekApi(deepSeekApi)
                        .defaultOptions(options)
                        .build();
                ObjectProvider<ChatModel> chatModelProvider = new ObjectProvider<>() {
                    @Override public ChatModel getObject() { return chatModel; }
                    @Override public ChatModel getIfAvailable() { return chatModel; }
                    @Override public ChatModel getIfUnique() { return chatModel; }
                    @Override public ChatModel getObject(Object... args) { return chatModel; }
                    @Override public ChatModel getIfAvailable(java.util.function.Supplier<ChatModel> s) { return chatModel; }
                    @Override public Iterator<ChatModel> iterator() { return List.of(chatModel).iterator(); }
                    @Override public Stream<ChatModel> stream() { return Stream.of(chatModel); }
                };

                DocumentProperties properties = new DocumentProperties();
                DocumentProperties.StructureParsing sp = new DocumentProperties.StructureParsing();
                sp.setLlmDisambiguationEnabled(true);
                sp.setMaxAmbiguousSignalsPerCall(8);
                sp.setContextWindowLines(4);
                sp.setAmbiguityConfidenceFloor(0.45);
                sp.setAmbiguityConfidenceCeil(0.80);
                properties.setStructureParsing(sp);

                PromptTemplateService templateService = new PromptTemplateService();
                ambiguityResolver = new DocumentStructureNodeAmbiguityResolver(
                        properties, chatModelProvider, templateService);
                llmAvailable = true;

                System.out.println();
                System.out.println("  🤖 模型: " + BOLD + modelName + RESET
                        + "  |  API: " + apiBaseUrl
                        + "  |  Temperature: " + temperature);
                System.out.println();
            } catch (Exception e) {
                System.err.println("  ❌ 创建 ChatModel 失败: " + e.getMessage());
                llmAvailable = false;
            }
        }
    }

    // =========================================================================
    // 主测试
    // =========================================================================

    @ParameterizedTest(name = "{0}")
    @MethodSource("testDocumentPaths")
    @DisplayName("层级解析结果展示")
    void resolveAndDisplay(Path docPath) throws IOException {
        String fileName = docPath.getFileName().toString();
        String rawContent = Files.readString(docPath, StandardCharsets.UTF_8);

        String documentTitle = fileName.contains(".")
                ? fileName.substring(0, fileName.lastIndexOf('.'))
                : fileName;

        // ── Stage 1: 规则引擎 ──
        DocumentStructureNodeSignalBatch batch = signalExtractor.extract(documentTitle, rawContent);
        List<DocumentStructureNodeSignal> stage1Signals = batch.signals();

        // ── Stage 2: LLM 消解（可选）──
        List<DocumentStructureNodeSignal> resolvedSignals;
        boolean stage2Ran = false;
        List<DocumentStructureNodeSignal> beforeSnapshot = null;

        if (llmAvailable && ambiguityResolver != null) {
            // 深拷贝 before 快照
            beforeSnapshot = deepCopySignals(stage1Signals);
            resolvedSignals = ambiguityResolver.resolve(
                    documentTitle, batch.contextLines(), stage1Signals);
            stage2Ran = true;
        } else {
            resolvedSignals = stage1Signals;
        }

        // ── Stage 3: 层级解析 ──
        List<DocumentIntermediateStructureNode> nodes =
                hierarchyResolver.resolve(documentTitle, resolvedSignals);

        // ════════════════════════════════════════════════════════════
        //  输出
        // ════════════════════════════════════════════════════════════

        printSeparator('═', 120);
        System.out.println(BOLD + CYAN + "  📄 " + fileName + RESET);
        System.out.println(DIM + "     信号总数: " + stage1Signals.size()
                + "  |  Stage 2: " + (stage2Ran ? "✅" : "跳过")
                + "  |  Stage 3 节点数: " + nodes.size()
                + RESET);
        printSeparator('─', 120);

        // ── ① Stage 1 信号分类表 ──
        System.out.println("  " + BOLD + "Stage 1 — 规则引擎全量信号" + RESET);
        printSignalTable(stage1Signals);

        // ── ② Stage 2 消解对比（如有）──
        if (stage2Ran && beforeSnapshot != null) {
            List<DocumentStructureNodeSignal> disambiguated = resolvedSignals.stream()
                    .filter(s -> s.getReasons().contains("llm-disambiguated"))
                    .toList();
            if (!disambiguated.isEmpty()) {
                System.out.println();
                System.out.println("  " + BOLD + MAGENTA + "Stage 2 — LLM 歧义消解" + RESET);
                printDisambiguationSummary(beforeSnapshot, resolvedSignals);
            }
        }

        // ── ③ Stage 3 节点统计 ──
        System.out.println();
        System.out.println("  " + BOLD + "Stage 3 — 层级解析结果" + RESET);
        printNodeStats(nodes);

        // ── ④ 树形可视化 ──
        System.out.println();
        System.out.println("  " + BOLD + "文档结构树" + RESET);
        printNodeTree(nodes);

        // ── ⑤ 断言 ──
        assertFalse(nodes.isEmpty(), "节点列表不应为空");
        assertEquals(1, nodes.get(0).getNodeNo(), "首节点应为根节点 (nodeNo=1)");
        assertEquals(DocumentStructureNodeTypeEnum.ROOT.getCode(), nodes.get(0).getNodeType(),
                "首节点类型应为 ROOT");

        printSeparator('═', 120);
        System.out.println();
    }

    // =========================================================================
    // ① 信号分类表
    // =========================================================================

    private void printSignalTable(List<DocumentStructureNodeSignal> signals) {
        final int W_LINE = 6, W_KIND = 20, W_CONF = 6, W_LEVEL = 5, W_INDENT = 5;
        final int[] COL_W = {W_LINE, W_KIND, W_CONF, W_LEVEL, W_INDENT};
        final String SEP = DIM + "│" + RESET;

        System.out.println("  " + borderLine('┌', '┬', '┐', COL_W));
        System.out.println("  " + SEP
                + fixedPad("行号", W_LINE) + SEP
                + fixedPad("类型", W_KIND) + SEP
                + fixedPad("置信度", W_CONF) + SEP
                + fixedPad("层级", W_LEVEL) + SEP
                + fixedPad("缩进", W_INDENT) + SEP
                + " 内容");
        System.out.println("  " + borderLine('├', '┼', '┤', COL_W));

        for (DocumentStructureNodeSignal sig : signals) {
            DocumentStructureNodeSignalEnum kind = sig.getKind();
            String color = KIND_COLOR.getOrDefault(kind, RESET);
            boolean isHeadingLike = kind == DocumentStructureNodeSignalEnum.HEADING
                    || kind == DocumentStructureNodeSignalEnum.HEADING_CANDIDATE;

            String content = sig.getTrimmedText();
            if (content == null) content = "";
            if (content.length() > 70) content = content.substring(0, 67) + "...";

            String lineNo = sig.getLogicalLineNo() == 0 ? "TITLE" : String.valueOf(sig.getLogicalLineNo());

            System.out.println("  " + SEP
                    + colorPad(lineNo, W_LINE, color, false) + SEP
                    + colorPad(kind.name(), W_KIND, color + (isHeadingLike ? BOLD : ""), false) + SEP
                    + colorPad(fmtConf(sig.getConfidence()), W_CONF, color, false) + SEP
                    + colorPad(fmtLevel(sig.getLevelHint()), W_LEVEL, color, false) + SEP
                    + colorPad(fmtIndent(sig.getIndentLevel()), W_INDENT, color, false) + SEP
                    + " " + RESET + content);
        }
        System.out.println("  " + borderLine('└', '┴', '┘', COL_W));
    }

    // =========================================================================
    // ② Stage 2 消解统计
    // =========================================================================

    private void printDisambiguationSummary(
            List<DocumentStructureNodeSignal> before,
            List<DocumentStructureNodeSignal> after) {

        long bHeading = countByKind(before, DocumentStructureNodeSignalEnum.HEADING);
        long bCandidate = countByKind(before, DocumentStructureNodeSignalEnum.HEADING_CANDIDATE);
        long bList = countByKind(before, DocumentStructureNodeSignalEnum.LIST_ITEM);
        long bBody = countByKind(before, DocumentStructureNodeSignalEnum.BODY);

        long aHeading = countByKind(after, DocumentStructureNodeSignalEnum.HEADING);
        long aCandidate = countByKind(after, DocumentStructureNodeSignalEnum.HEADING_CANDIDATE);
        long aList = countByKind(after, DocumentStructureNodeSignalEnum.LIST_ITEM);
        long aBody = countByKind(after, DocumentStructureNodeSignalEnum.BODY);

        System.out.printf("    HEADING:           %s%3d%s  ──▶  %s%3d%s%n",
                GREEN, bHeading, RESET, GREEN, aHeading, RESET);
        System.out.printf("    HEADING_CANDIDATE: %s%3d%s  ──▶  %s%3d%s%n",
                YELLOW, bCandidate, RESET, YELLOW, aCandidate, RESET);
        System.out.printf("    LIST_ITEM:         %s%3d%s  ──▶  %s%3d%s%n",
                BLUE, bList, RESET, BLUE, aList, RESET);
        System.out.printf("    BODY:              %s%3d%s  ──▶  %s%3d%s%n",
                RESET, bBody, RESET, RESET, aBody, RESET);
    }

    // =========================================================================
    // ③ 节点统计
    // =========================================================================

    private void printNodeStats(List<DocumentIntermediateStructureNode> nodes) {
        Map<Integer, Long> countByType = nodes.stream()
                .collect(Collectors.groupingBy(
                        DocumentIntermediateStructureNode::getNodeType,
                        LinkedHashMap::new,
                        Collectors.counting()));

        int maxDepth = nodes.stream()
                .mapToInt(n -> n.getDepth() == null ? 0 : n.getDepth())
                .max().orElse(0);

        long withContent = nodes.stream()
                .filter(n -> n.getContentText() != null && !n.getContentText().isBlank())
                .count();

        System.out.print("    节点类型: ");
        for (Map.Entry<Integer, Long> e : countByType.entrySet()) {
            String label = NODE_TYPE_LABEL.getOrDefault(e.getKey(), "?");
            String color = NODE_TYPE_COLOR.getOrDefault(e.getKey(), RESET);
            System.out.print(color + label + "=" + e.getValue() + RESET + "  ");
        }
        System.out.println();
        System.out.println("    最大深度: " + maxDepth
                + "  |  含正文节点: " + withContent
                + "  |  节点总数: " + nodes.size());
    }

    // =========================================================================
    // ④ 树形可视化
    // =========================================================================

    private void printNodeTree(List<DocumentIntermediateStructureNode> nodes) {
        // 建立 parentNodeNo → children 分组
        Map<Integer, List<DocumentIntermediateStructureNode>> childrenByParent = new LinkedHashMap<>();
        for (DocumentIntermediateStructureNode node : nodes) {
            if (node.getNodeNo() == 1) continue; // skip root for grouping
            Integer parentKey = node.getParentNodeNo() == null ? 1 : node.getParentNodeNo();
            childrenByParent.computeIfAbsent(parentKey, k -> new ArrayList<>()).add(node);
        }

        // 每组内按 nodeNo 排序
        childrenByParent.values().forEach(list -> list.sort(
                java.util.Comparator.comparingInt(DocumentIntermediateStructureNode::getNodeNo)));

        // 从根开始递归打印
        DocumentIntermediateStructureNode root = nodes.stream()
                .filter(n -> n.getNodeNo() == 1)
                .findFirst().orElse(null);

        if (root != null) {
            printTreeNode(root, "", true, childrenByParent);
        }

        System.out.println();
        // 图例
        System.out.println("  " + DIM + "图例: "
                + MAGENTA + "ROOT" + RESET + DIM + " | "
                + GREEN + "CHAPTER" + RESET + DIM + " | "
                + CYAN + "STEP" + RESET + DIM + " | "
                + BLUE + "LIST" + RESET + DIM
                + "  ·  contentText 预览 → ..." + RESET);
    }

    private void printTreeNode(
            DocumentIntermediateStructureNode node,
            String prefix,
            boolean isLast,
            Map<Integer, List<DocumentIntermediateStructureNode>> childrenByParent) {

        String connector = isLast ? "└── " : "├── ";
        String typeLabel = NODE_TYPE_LABEL.getOrDefault(node.getNodeType(), "?");
        String typeColor = NODE_TYPE_COLOR.getOrDefault(node.getNodeType(), RESET);

        // 组装节点描述
        StringBuilder desc = new StringBuilder();
        desc.append(typeColor).append(BOLD).append(typeLabel).append(RESET);
        desc.append(" (").append(node.getNodeNo()).append(", d=").append(node.getDepth()).append(")");

        if (node.getNodeCode() != null && !node.getNodeCode().isBlank()) {
            desc.append(" ").append(DIM).append(node.getNodeCode()).append(RESET);
        }
        if (node.getTitle() != null && !node.getTitle().isBlank()) {
            String title = node.getTitle();
            if (title.length() > 40) title = title.substring(0, 37) + "...";
            desc.append(" ").append(BOLD).append(title).append(RESET);
        }

        // 正文预览
        String contentPreview = null;
        if (node.getContentText() != null && !node.getContentText().isBlank()) {
            String ct = node.getContentText().replace('\n', ' ');
            if (ct.length() > 50) ct = ct.substring(0, 47) + "...";
            contentPreview = DIM + " → " + ORANGE + ct + RESET;
        }

        System.out.println("  " + prefix + connector + desc.toString()
                + (contentPreview != null ? contentPreview : ""));

        // 子节点
        List<DocumentIntermediateStructureNode> children = childrenByParent.get(node.getNodeNo());
        if (children == null || children.isEmpty()) return;

        String childPrefix = prefix + (isLast ? "    " : "│   ");
        for (int i = 0; i < children.size(); i++) {
            boolean childIsLast = (i == children.size() - 1);
            printTreeNode(children.get(i), childPrefix, childIsLast, childrenByParent);
        }
    }

    // =========================================================================
    // 工具方法
    // =========================================================================

    private static long countByKind(List<DocumentStructureNodeSignal> signals,
                                     DocumentStructureNodeSignalEnum kind) {
        return signals.stream().filter(s -> s.getKind() == kind).count();
    }

    private List<DocumentStructureNodeSignal> deepCopySignals(List<DocumentStructureNodeSignal> source) {
        return source.stream()
                .map(s -> DocumentStructureNodeSignal.builder()
                        .logicalLineNo(s.getLogicalLineNo())
                        .rawText(s.getRawText())
                        .trimmedText(s.getTrimmedText())
                        .kind(s.getKind())
                        .headingCode(s.getHeadingCode())
                        .sequenceNo(s.getSequenceNo())
                        .numericPath(s.getNumericPath() != null
                                ? new ArrayList<>(s.getNumericPath()) : new ArrayList<>())
                        .title(s.getTitle())
                        .levelHint(s.getLevelHint())
                        .indentLevel(s.getIndentLevel())
                        .reasons(s.getReasons() != null
                                ? new ArrayList<>(s.getReasons()) : new ArrayList<>())
                        .confidence(s.getConfidence())
                        .build())
                .toList();
    }

    // =========================================================================
    // 配置加载
    // =========================================================================

    private static Map<String, String> loadConfig() {
        Map<String, String> config = new LinkedHashMap<>();
        loadFromApplicationYml(config);
        loadFromEnvFile(config);
        loadFromSystemEnv(config);
        loadFromSystemProperties(config);
        return config;
    }

    private static void loadFromSystemProperties(Map<String, String> config) {
        putIfPresent(config, "api-key", System.getProperty("spring.ai.deepseek.api-key"));
        putIfPresent(config, "api-key", System.getProperty("spring.ai.openai.api-key"));
        putIfPresent(config, "base-url", System.getProperty("spring.ai.deepseek.base-url"));
        putIfPresent(config, "base-url", System.getProperty("spring.ai.openai.base-url"));
        putIfPresent(config, "model", System.getProperty("ai.model"));
        putIfPresent(config, "temperature", System.getProperty("ai.temperature"));
    }

    private static void loadFromSystemEnv(Map<String, String> config) {
        putIfPresent(config, "api-key", System.getenv("SPRING_AI_DEEPSEEK_API_KEY"));
        putIfPresent(config, "api-key", System.getenv("SPRING_AI_OPENAI_API_KEY"));
        putIfPresent(config, "base-url", System.getenv("SPRING_AI_DEEPSEEK_BASE_URL"));
        putIfPresent(config, "base-url", System.getenv("SPRING_AI_OPENAI_BASE_URL"));
        putIfPresent(config, "model", System.getenv("AI_MODEL"));
        putIfPresent(config, "temperature", System.getenv("AI_TEMPERATURE"));
    }

    private static void loadFromEnvFile(Map<String, String> config) {
        Path envFile = findEnvFile();
        if (envFile == null) return;
        try {
            List<String> lines = Files.readAllLines(envFile, StandardCharsets.UTF_8);
            for (String line : lines) {
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#")) continue;
                int eq = trimmed.indexOf('=');
                if (eq < 0) continue;
                String key = trimmed.substring(0, eq).trim();
                String value = trimmed.substring(eq + 1).trim();
                if ((value.startsWith("\"") && value.endsWith("\""))
                        || (value.startsWith("'") && value.endsWith("'"))) {
                    value = value.substring(1, value.length() - 1);
                }
                switch (key) {
                    case "SPRING_AI_DEEPSEEK_API_KEY" -> putIfPresent(config, "api-key", value);
                    case "SPRING_AI_OPENAI_API_KEY" -> putIfPresent(config, "api-key", value);
                    case "SPRING_AI_DEEPSEEK_BASE_URL" -> putIfPresent(config, "base-url", value);
                    case "SPRING_AI_OPENAI_BASE_URL" -> putIfPresent(config, "base-url", value);
                    case "AI_MODEL" -> putIfPresent(config, "model", value);
                    case "AI_BASE_URL" -> putIfPresent(config, "base-url", value);
                    case "AI_TEMPERATURE" -> putIfPresent(config, "temperature", value);
                }
            }
        } catch (IOException e) {
            // silent
        }
    }

    private static Path findEnvFile() {
        Path moduleEnv = Paths.get("business/document/.env");
        if (Files.isReadable(moduleEnv)) return moduleEnv.toAbsolutePath();
        Path projectEnv = Paths.get(".env");
        if (Files.isReadable(projectEnv)) return projectEnv.toAbsolutePath();
        return null;
    }

    @SuppressWarnings("unchecked")
    private static void loadFromApplicationYml(Map<String, String> config) {
        try (InputStream is = DocumentStructureHierarchyResolverDisplayTest.class
                .getClassLoader().getResourceAsStream("application-test.yml")) {
            if (is == null) return;
            Yaml yaml = new Yaml();
            Map<String, Object> root = yaml.load(is);
            if (root == null) return;
            Map<String, Object> spring = (Map<String, Object>) root.get("spring");
            if (spring == null) return;
            Map<String, Object> ai = (Map<String, Object>) spring.get("ai");
            if (ai == null) return;
            Map<String, Object> ds = (Map<String, Object>) ai.get("deepseek");
            Map<String, Object> openai = (Map<String, Object>) ai.get("openai");
            Map<String, Object> provider = ds != null ? ds : openai;
            if (provider == null) return;
            putIfPresent(config, "api-key", Objects.toString(provider.get("api-key"), null));
            putIfPresent(config, "base-url", Objects.toString(provider.get("base-url"), null));
            Map<String, Object> chat = (Map<String, Object>) provider.get("chat");
            if (chat != null) {
                Map<String, Object> options = (Map<String, Object>) chat.get("options");
                if (options != null) {
                    putIfPresent(config, "model", Objects.toString(options.get("model"), null));
                    Object temp = options.get("temperature");
                    if (temp != null) putIfPresent(config, "temperature", temp.toString());
                }
            }
        } catch (Exception e) {
            // silent
        }
    }

    private static void putIfPresent(Map<String, String> map, String key, String value) {
        if (value != null && !value.isBlank() && !value.startsWith("fake-") && !value.startsWith("sk-your-")) {
            map.putIfAbsent(key, value);
        }
    }

    // =========================================================================
    // 测试文档发现
    // =========================================================================

    static Stream<Path> testDocumentPaths() throws IOException, URISyntaxException {
        var classLoader = DocumentStructureHierarchyResolverDisplayTest.class.getClassLoader();
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

    // =========================================================================
    // 格式化
    // =========================================================================

    private static String fmtConf(double c) {
        return String.format("%.0f%%", c * 100);
    }

    private static String fmtLevel(Integer l) {
        return l == null ? "-" : String.valueOf(l);
    }

    private static String fmtIndent(Integer i) {
        return i == null || i == 0 ? "-" : String.valueOf(i);
    }

    private static void printSeparator(char ch, int len) {
        System.out.println(DIM + String.valueOf(ch).repeat(len) + RESET);
    }

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

    // ── CJK 对齐 ──

    private static final java.util.regex.Pattern ANSI_ESCAPE =
            java.util.regex.Pattern.compile("\\u001B\\[[;\\d]*m");

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
        if (cp >= 0x4E00 && cp <= 0x9FFF) return true;
        if (cp >= 0x3400 && cp <= 0x4DBF) return true;
        if (cp >= 0xF900 && cp <= 0xFAFF) return true;
        if (cp >= 0xFF01 && cp <= 0xFF60) return true;
        if (cp >= 0xFFE0 && cp <= 0xFFE6) return true;
        if (cp >= 0x3000 && cp <= 0x303F) return true;
        if (cp >= 0x3040 && cp <= 0x309F) return true;
        if (cp >= 0x30A0 && cp <= 0x30FF) return true;
        if (cp >= 0xAC00 && cp <= 0xD7AF) return true;
        return false;
    }

    private static String fixedPad(String text, int targetWidth) {
        int w = terminalWidth(text);
        if (w >= targetWidth) return text + " ";
        return text + " ".repeat(targetWidth - w + 1);
    }

    private static String colorPad(String text, int targetWidth, String color, boolean bold) {
        String inner = bold ? (color + BOLD + text) : (color + text);
        int w = terminalWidth(inner);
        if (w >= targetWidth) return inner + " ";
        return inner + " ".repeat(targetWidth - w + 1);
    }
}
