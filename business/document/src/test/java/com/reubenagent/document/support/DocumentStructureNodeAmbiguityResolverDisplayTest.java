package com.reubenagent.document.support;

import com.reubenagent.document.config.DocumentProperties;
import com.reubenagent.document.enums.DocumentStructureNodeSignalEnum;
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
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * LLM 歧义消解展示测试 —— 遍历 test-documents/ 下所有测试文档，
 * 运行 Stage 1（规则引擎）+ Stage 2（LLM 消解），以可读格式输出消解前后对比。
 *
 * <h3>🔑 API Key 配置（按优先级从高到低）</h3>
 * <ol>
 *   <li>系统属性: {@code -Dspring.ai.deepseek.api-key=sk-xxx}</li>
 *   <li>环境变量: {@code SPRING_AI_DEEPSEEK_API_KEY}</li>
 *   <li><b>.env 文件</b> — 编辑 {@code business/document/.env}（推荐 IDE 用户）</li>
 *   <li>application-test.yml — 编辑 {@code spring.ai.deepseek.api-key}</li>
 * </ol>
 *
 * <h3>🤖 模型配置 — 同样从上述来源读取</h3>
 * <table>
 *   <tr><th>参数</th><th>.env 变量</th><th>默认值</th></tr>
 *   <tr><td>模型名</td><td>{@code AI_MODEL}</td><td>{@code deepseek-v4-flash}</td></tr>
 *   <tr><td>API 地址</td><td>{@code AI_BASE_URL}</td><td>{@code https://api.deepseek.com}</td></tr>
 *   <tr><td>温度</td><td>{@code AI_TEMPERATURE}</td><td>{@code 0.2}</td></tr>
 * </table>
 *
 * <h3>运行方式</h3>
 * <pre>{@code
 *   # IDE: 编辑 business/document/.env → 点小三角运行
 *   # CLI:
 *   mvn test -pl business/document -am -Dtest=DocumentStructureNodeAmbiguityResolverDisplayTest
 * }</pre>
 *
 * @author reuben
 * @since 2026-06-19
 */
@DisplayName("LLM 歧义消解 - 展示测试")
class DocumentStructureNodeAmbiguityResolverDisplayTest {

    // =========================================================================
    // 配置 — 由 setUpResolver() 从 .env / yml / 系统属性 / 环境变量中加载
    // =========================================================================
    private static String apiKey;
    private static String modelName;
    private static String apiBaseUrl;
    private static double temperature;

    // =========================================================================
    // 状态
    // =========================================================================
    private static DocumentStructureNodeSignalExtractor signalExtractor;
    private static DocumentStructureNodeAmbiguityResolver resolver;
    private static boolean llmAvailable;

    /** 颜色常量 */
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

    private static final Map<DocumentStructureNodeSignalEnum, String> KIND_COLOR = Map.of(
            DocumentStructureNodeSignalEnum.HEADING, GREEN,
            DocumentStructureNodeSignalEnum.HEADING_CANDIDATE, YELLOW,
            DocumentStructureNodeSignalEnum.LIST_ITEM, BLUE,
            DocumentStructureNodeSignalEnum.BODY, RESET,
            DocumentStructureNodeSignalEnum.STEP_ITEM, CYAN
    );

    // =========================================================================
    // 初始化 — 手工装配整个管线（不依赖 Spring 容器）
    // =========================================================================

    @BeforeAll
    static void setUpResolver() {
        // 1. 创建 SignalExtractor（无依赖）
        signalExtractor = new DocumentStructureNodeSignalExtractor();

        // 2. 加载配置（优先级: 系统属性 > 环境变量 > .env 文件 > application-test.yml）
        Map<String, String> config = loadConfig();

        apiKey = config.getOrDefault("api-key", "");
        modelName = config.getOrDefault("model", "deepseek-v4-flash");
        apiBaseUrl = config.getOrDefault("base-url", "https://api.deepseek.com");
        temperature = Double.parseDouble(config.getOrDefault("temperature", "0.2"));

        if (apiKey.isBlank() || apiKey.startsWith("fake-") || apiKey.startsWith("sk-your-")) {
            System.err.println();
            System.err.println("  ⚠️  未设置有效的 DeepSeek API Key，LLM 调用将跳过。");
            System.err.println("  配置方式（按优先级）：");
            System.err.println("    1. 系统属性:   -Dspring.ai.deepseek.api-key=sk-xxx");
            System.err.println("    2. 环境变量:   export SPRING_AI_DEEPSEEK_API_KEY=sk-xxx");
            System.err.println("    3. .env 文件:  编辑 business/document/.env");
            System.err.println("    4. yml 文件:   编辑 application-test.yml → spring.ai.deepseek.api-key");
            System.err.println();
            llmAvailable = false;
            return;
        }

        System.out.println();
        System.out.println("  🤖 模型: " + BOLD + modelName + RESET
                + "  |  API: " + apiBaseUrl
                + "  |  Temperature: " + temperature);
        System.out.println("  API Key: " + DIM + apiKey.substring(0, 8) + "..." + RESET);
        System.out.println();

        // 3. 创建 DeepSeek ChatModel + Resolver
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

            resolver = new DocumentStructureNodeAmbiguityResolver(
                    properties, chatModelProvider, templateService);

            llmAvailable = true;
        } catch (Exception e) {
            System.err.println("  ❌ 创建 ChatModel 失败: " + e.getMessage());
            e.printStackTrace();
            llmAvailable = false;
        }
    }

    // =========================================================================
    // 配置加载 — .env → application-test.yml → 系统属性 → 环境变量
    // =========================================================================

    /**
     * 按优先级加载 LLM 配置：系统属性 → 环境变量 → .env 文件 → application-test.yml。
     */
    private static Map<String, String> loadConfig() {
        Map<String, String> config = new LinkedHashMap<>();

        // 第4优先级: application-test.yml（最低）
        loadFromApplicationYml(config);
        // 第3优先级: .env 文件
        loadFromEnvFile(config);
        // 第2优先级: 环境变量
        loadFromSystemEnv(config);
        // 第1优先级: 系统属性（最高）
        loadFromSystemProperties(config);

        return config;
    }

    /** 从系统属性读取（最高优先级） */
    private static void loadFromSystemProperties(Map<String, String> config) {
        putIfPresent(config, "api-key", System.getProperty("spring.ai.deepseek.api-key"));
        putIfPresent(config, "api-key", System.getProperty("spring.ai.openai.api-key"));
        putIfPresent(config, "base-url", System.getProperty("spring.ai.deepseek.base-url"));
        putIfPresent(config, "base-url", System.getProperty("spring.ai.openai.base-url"));
        putIfPresent(config, "model", System.getProperty("ai.model"));
        putIfPresent(config, "temperature", System.getProperty("ai.temperature"));
    }

    /** 从环境变量读取 */
    private static void loadFromSystemEnv(Map<String, String> config) {
        putIfPresent(config, "api-key", System.getenv("SPRING_AI_DEEPSEEK_API_KEY"));
        putIfPresent(config, "api-key", System.getenv("SPRING_AI_OPENAI_API_KEY"));
        putIfPresent(config, "base-url", System.getenv("SPRING_AI_DEEPSEEK_BASE_URL"));
        putIfPresent(config, "base-url", System.getenv("SPRING_AI_OPENAI_BASE_URL"));
        putIfPresent(config, "model", System.getenv("AI_MODEL"));
        putIfPresent(config, "temperature", System.getenv("AI_TEMPERATURE"));
    }

    /** 从 .env 文件读取（KEY=VALUE 格式，# 为注释） */
    private static void loadFromEnvFile(Map<String, String> config) {
        Path envFile = findEnvFile();
        if (envFile == null) return;
        try {
            Properties props = new Properties();
            // 手工解析，兼容 = 前后空格、引号包裹的值
            List<String> lines = Files.readAllLines(envFile, StandardCharsets.UTF_8);
            for (String line : lines) {
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#")) continue;
                int eq = trimmed.indexOf('=');
                if (eq < 0) continue;
                String key = trimmed.substring(0, eq).trim();
                String value = trimmed.substring(eq + 1).trim();
                // 去除引号
                if ((value.startsWith("\"") && value.endsWith("\""))
                        || (value.startsWith("'") && value.endsWith("'"))) {
                    value = value.substring(1, value.length() - 1);
                }
                // 映射 .env key → config key
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
            // .env 文件不存在或不可读，静默跳过
        }
    }

    /** 查找 .env 文件：先查模块目录，再查项目根目录 */
    private static Path findEnvFile() {
        // IDE 运行：模块目录
        Path moduleEnv = Paths.get("business/document/.env");
        if (Files.isReadable(moduleEnv)) return moduleEnv.toAbsolutePath();
        // CLI 运行或项目根
        Path projectEnv = Paths.get(".env");
        if (Files.isReadable(projectEnv)) return projectEnv.toAbsolutePath();
        return null;
    }

    /** 从 application-test.yml 读取（最低优先级） */
    @SuppressWarnings("unchecked")
    private static void loadFromApplicationYml(Map<String, String> config) {
        try (InputStream is = DocumentStructureNodeAmbiguityResolverDisplayTest.class
                .getClassLoader().getResourceAsStream("application-test.yml")) {
            if (is == null) return;
            Yaml yaml = new Yaml();
            Map<String, Object> root = yaml.load(is);
            if (root == null) return;
            Map<String, Object> spring = (Map<String, Object>) root.get("spring");
            if (spring == null) return;
            Map<String, Object> ai = (Map<String, Object>) spring.get("ai");
            if (ai == null) return;

            // 优先读 DeepSeek 配置，回退 OpenAI
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
            // yml 解析失败，静默跳过
        }
    }

    private static void putIfPresent(Map<String, String> map, String key, String value) {
        if (value != null && !value.isBlank() && !value.startsWith("fake-") && !value.startsWith("sk-your-")) {
            map.putIfAbsent(key, value);
        }
    }

    // =========================================================================
    // 主测试
    // =========================================================================

    @ParameterizedTest(name = "{0}")
    @MethodSource("testDocumentPaths")
    @DisplayName("LLM 歧义消解结果展示")
    void disambiguateAndDisplay(Path docPath) throws IOException {
        String fileName = docPath.getFileName().toString();
        String rawContent = Files.readString(docPath, StandardCharsets.UTF_8);

        String documentTitle = fileName.contains(".")
                ? fileName.substring(0, fileName.lastIndexOf('.'))
                : fileName;

        // ── Stage 1: 规则引擎 ──
        DocumentStructureNodeSignalBatch batch = signalExtractor.extract(documentTitle, rawContent);
        List<DocumentStructureNodeSignal> beforeSignals = batch.signals();

        // 找出 HEADING_CANDIDATE
        List<DocumentStructureNodeSignal> candidates = beforeSignals.stream()
                .filter(s -> s.getKind() == DocumentStructureNodeSignalEnum.HEADING_CANDIDATE)
                .toList();

        boolean hasCandidates = !candidates.isEmpty();

        // ── 打印文档头部 ──
        printSeparator('═', 110);
        System.out.println(BOLD + CYAN + "  📄 " + fileName + RESET);
        System.out.println(DIM + "     信号总数: " + beforeSignals.size()
                + "  |  HEADING_CANDIDATE: " + candidates.size()
                + (llmAvailable ? "  |  LLM: 可用 ✅" : "  |  LLM: 不可用 ⚠️")
                + RESET);

        if (!hasCandidates) {
            printSeparator('─', 110);
            System.out.println("  " + DIM + "无模糊信号（HEADING_CANDIDATE），无需 LLM 消解" + RESET);
            printSeparator('═', 110);
            System.out.println();
            return;
        }

        // ── 打印 Stage 1 全量信号表 ──
        printSeparator('─', 110);
        System.out.println("  " + BOLD + "Stage 1 — 规则引擎全量分类" + RESET);
        printSignalTable(beforeSignals, null);

        // ── 如果是 LLM 可用，运行 Stage 2 ──
        if (llmAvailable) {
            // 快照：resolve() 会原地修改 sourceSignals，这里深拷贝一份 before 用于展示对比
            List<DocumentStructureNodeSignal> beforeSnapshot = beforeSignals.stream()
                    .map(s -> DocumentStructureNodeSignal.builder()
                            .logicalLineNo(s.getLogicalLineNo())
                            .rawText(s.getRawText())
                            .trimmedText(s.getTrimmedText())
                            .kind(s.getKind())
                            .headingCode(s.getHeadingCode())
                            .sequenceNo(s.getSequenceNo())
                            .numericPath(s.getNumericPath() != null
                                    ? new java.util.ArrayList<>(s.getNumericPath()) : new java.util.ArrayList<>())
                            .title(s.getTitle())
                            .levelHint(s.getLevelHint())
                            .indentLevel(s.getIndentLevel())
                            .reasons(s.getReasons() != null
                                    ? new java.util.ArrayList<>(s.getReasons()) : new java.util.ArrayList<>())
                            .confidence(s.getConfidence())
                            .build())
                    .toList();

            List<DocumentStructureNodeSignal> afterSignals = resolver.resolve(
                    documentTitle, batch.contextLines(), beforeSignals);

            // ── 打印消解对比 ──
            printSeparator('─', 110);
            System.out.println("  " + BOLD + MAGENTA + "Stage 2 — LLM 歧义消解结果" + RESET);
            System.out.println();

            // 只打印被消解的模糊行
            List<DocumentStructureNodeSignal> disambiguated = afterSignals.stream()
                    .filter(s -> s.getReasons().contains("llm-disambiguated"))
                    .toList();

            if (disambiguated.isEmpty()) {
                System.out.println("  " + DIM + "（LLM 未消解任何信号——可能所有 HEADING_CANDIDATE 都不在置信度区间内）" + RESET);
            } else {
                printDisambiguationDetail(beforeSnapshot, afterSignals, disambiguated, batch.contextLines());
            }

            // ── 统计 ──
            printDisambiguationSummary(beforeSnapshot, afterSignals);
        } else {
            printSeparator('─', 110);
            System.out.println("  " + YELLOW + "⚠️  LLM 不可用，Stage 2 跳过。HEADING_CANDIDATE 保持原样。" + RESET);
        }

        printSeparator('═', 110);
        System.out.println();
    }

    // =========================================================================
    // 辅助打印方法
    // =========================================================================

    /** 全量信号表格 */
    private void printSignalTable(List<DocumentStructureNodeSignal> signals,
                                   List<DocumentStructureNodeSignal> resolved) {
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
            if (content.length() > 55) content = content.substring(0, 52) + "...";

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

    /** 消解详情：每条 HEADING_CANDIDATE 的 before/after 对比 + 上下文 */
    private void printDisambiguationDetail(
            List<DocumentStructureNodeSignal> beforeSignals,
            List<DocumentStructureNodeSignal> afterSignals,
            List<DocumentStructureNodeSignal> disambiguated,
            List<String> allLines) {

        for (DocumentStructureNodeSignal after : disambiguated) {
            int lineNo = after.getLogicalLineNo();

            // 找到对应的 before 信号
            DocumentStructureNodeSignal before = beforeSignals.stream()
                    .filter(s -> s.getLogicalLineNo() == lineNo)
                    .findFirst().orElse(null);
            if (before == null) continue;

            // 上下文窗口
            int targetIdx = lineNo - 1;
            int from = Math.max(0, targetIdx - 2);
            int to = Math.min(allLines.size() - 1, targetIdx + 2);

            System.out.println("  " + borderLine('┌', '─', '┐', new int[]{100}));
            System.out.println("  " + DIM + "│" + RESET
                    + " 行号 " + BOLD + lineNo + RESET
                    + "  上下文窗口 [" + (from + 1) + "–" + (to + 1) + "]");
            System.out.println("  " + DIM + "│" + RESET);

            // 打印上下文行
            for (int i = from; i <= to; i++) {
                String prefix;
                String lineColor;
                if (i == targetIdx) {
                    prefix = " ▶ ";
                    lineColor = YELLOW + BOLD;
                } else {
                    prefix = "   ";
                    lineColor = DIM;
                }
                String text = allLines.get(i);
                if (text.length() > 70) text = text.substring(0, 67) + "...";
                System.out.println("  " + DIM + "│" + RESET
                        + prefix + lineColor + text + RESET);
            }

            System.out.println("  " + DIM + "│" + RESET);
            // 消解结果
            String beforeColor = KIND_COLOR.getOrDefault(before.getKind(), RESET);
            String afterColor = KIND_COLOR.getOrDefault(after.getKind(), RESET);
            System.out.println("  " + DIM + "│" + RESET
                    + "  " + beforeColor + before.getKind().name() + RESET
                    + "  " + DIM + "──LLM──▶" + RESET
                    + "  " + afterColor + BOLD + after.getKind().name() + RESET
                    + "  (置信度 " + before.getConfidence() + " → " + ORANGE + after.getConfidence() + RESET + ")"
                    + (after.getLevelHint() != null ? "  level=" + after.getLevelHint() : ""));
            System.out.println("  " + borderLine('└', '─', '┘', new int[]{100}));
            System.out.println();
        }
    }

    /** 消解前后统计对比 */
    private void printDisambiguationSummary(
            List<DocumentStructureNodeSignal> before,
            List<DocumentStructureNodeSignal> after) {

        long beforeHeading = countByKind(before, DocumentStructureNodeSignalEnum.HEADING);
        long beforeCandidate = countByKind(before, DocumentStructureNodeSignalEnum.HEADING_CANDIDATE);
        long beforeList = countByKind(before, DocumentStructureNodeSignalEnum.LIST_ITEM);
        long beforeBody = countByKind(before, DocumentStructureNodeSignalEnum.BODY);

        long afterHeading = countByKind(after, DocumentStructureNodeSignalEnum.HEADING);
        long afterCandidate = countByKind(after, DocumentStructureNodeSignalEnum.HEADING_CANDIDATE);
        long afterList = countByKind(after, DocumentStructureNodeSignalEnum.LIST_ITEM);
        long afterBody = countByKind(after, DocumentStructureNodeSignalEnum.BODY);

        System.out.println("  " + BOLD + "消解统计:" + RESET);
        System.out.printf("    HEADING:           %s%3d%s  ──▶  %s%3d%s%n",
                GREEN, beforeHeading, RESET, GREEN, afterHeading, RESET);
        System.out.printf("    HEADING_CANDIDATE: %s%3d%s  ──▶  %s%3d%s%n",
                YELLOW, beforeCandidate, RESET, YELLOW, afterCandidate, RESET);
        System.out.printf("    LIST_ITEM:         %s%3d%s  ──▶  %s%3d%s%n",
                BLUE, beforeList, RESET, BLUE, afterList, RESET);
        System.out.printf("    BODY:              %s%3d%s  ──▶  %s%3d%s%n",
                RESET, beforeBody, RESET, RESET, afterBody, RESET);
    }

    // =========================================================================
    // 工具方法
    // =========================================================================

    private static long countByKind(List<DocumentStructureNodeSignal> signals,
                                     DocumentStructureNodeSignalEnum kind) {
        return signals.stream().filter(s -> s.getKind() == kind).count();
    }

    static Stream<Path> testDocumentPaths() throws IOException, URISyntaxException {
        var classLoader = DocumentStructureNodeAmbiguityResolverDisplayTest.class.getClassLoader();
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

    // ── 格式化 ──

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
