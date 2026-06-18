package com.reubenagent.document.support;

import com.reubenagent.document.config.DocumentProperties;
import com.reubenagent.document.enums.DocumentStructureNodeSignalEnum;
import com.reubenagent.document.model.DocumentStructureNodeSignal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.ObjectProvider;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;

/**
 * {@link DocumentStructureNodeAmbiguityResolver} 单元测试。
 *
 * <p>Mock ChatModel 和 PromptTemplateService，覆盖三道前置检查、LLM 判定、结果应用和优雅降级。</p>
 *
 * @author reuben
 * @since 2026-06-18
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("DocumentStructureNodeAmbiguityResolver 单元测试")
class DocumentStructureNodeAmbiguityResolverTest {

    @Mock
    private DocumentProperties properties;

    @Mock
    private DocumentProperties.StructureParsing structureParsing;

    @Mock
    private ObjectProvider<ChatModel> chatModelProvider;

    @Mock
    private ChatModel chatModel;

    @Mock
    private PromptTemplateService promptTemplateService;

    @Spy
    @InjectMocks
    private DocumentStructureNodeAmbiguityResolver resolver;

    // ---- 测试常量 ----
    private static final String DOC_TITLE = "测试文档";
    private static final double FLOOR = 0.45;
    private static final double CEIL = 0.80;

    @BeforeEach
    void setUp() {
        // 默认配置：开启 LLM 消解
        when(properties.getStructureParsing()).thenReturn(structureParsing);
        when(structureParsing.getLlmDisambiguationEnabled()).thenReturn(true);
        when(structureParsing.getAmbiguityConfidenceFloor()).thenReturn(FLOOR);
        when(structureParsing.getAmbiguityConfidenceCeil()).thenReturn(CEIL);
        when(structureParsing.getMaxAmbiguousSignalsPerCall()).thenReturn(8);
        when(structureParsing.getContextWindowLines()).thenReturn(2);
        when(chatModelProvider.getIfAvailable()).thenReturn(chatModel);
        // Mock 模板渲染（callLlm 被 mock，模板内容不影响结果）
        when(promptTemplateService.render(anyString(), anyMap())).thenReturn("rendered-prompt");
    }

    // ======================== 辅助方法 ========================

    private DocumentStructureNodeSignal createSignal(int lineNo, DocumentStructureNodeSignalEnum kind,
                                                     double confidence, String title) {
        DocumentStructureNodeSignal signal = new DocumentStructureNodeSignal();
        signal.setLogicalLineNo(lineNo);
        signal.setRawText("raw-" + lineNo);
        signal.setTrimmedText("trimmed-" + lineNo);
        signal.setKind(kind);
        signal.setTitle(title);
        signal.setHeadingCode("H" + lineNo);
        signal.setConfidence(confidence);
        signal.setReasons(new ArrayList<>());
        signal.setLevelHint(null);
        return signal;
    }

    private DocumentStructureNodeSignal createHeadingCandidate(int lineNo, double confidence) {
        return createSignal(lineNo, DocumentStructureNodeSignalEnum.HEADING_CANDIDATE, confidence,
                "候选标题 " + lineNo);
    }

    private DocumentStructureNodeSignal createHeading(int lineNo, double confidence) {
        return createSignal(lineNo, DocumentStructureNodeSignalEnum.HEADING, confidence,
                "确定标题 " + lineNo);
    }

    private void mockLlmResponse(String jsonResponse) {
        doReturn(jsonResponse).when(resolver).callLlm(any(ChatModel.class), anyString());
    }

    // ======================== 三道前置检查 ========================

    @Nested
    @DisplayName("前置检查 — 任一不通过则透传原始信号")
    class GuardConditions {

        @Test
        @DisplayName("总开关关闭 → 透传")
        void shouldPassThroughWhenDisabled() {
            when(structureParsing.getLlmDisambiguationEnabled()).thenReturn(false);

            List<DocumentStructureNodeSignal> source = List.of(createHeadingCandidate(1, 0.6));
            List<DocumentStructureNodeSignal> result = resolver.resolve(DOC_TITLE, List.of("line1"), source);

            assertThat(result).isSameAs(source);
            assertThat(result.get(0).getKind()).isEqualTo(DocumentStructureNodeSignalEnum.HEADING_CANDIDATE);
        }

        @Test
        @DisplayName("无 ChatModel Bean → 透传")
        void shouldPassThroughWhenNoChatModel() {
            when(chatModelProvider.getIfAvailable()).thenReturn(null);

            List<DocumentStructureNodeSignal> source = List.of(createHeadingCandidate(1, 0.6));
            List<DocumentStructureNodeSignal> result = resolver.resolve(DOC_TITLE, List.of("line1"), source);

            assertThat(result).isSameAs(source);
        }

        @Test
        @DisplayName("无符合条件的 HEADING_CANDIDATE → 透传")
        void shouldPassThroughWhenNoAmbiguousSignal() {
            // 全是确定型 HEADING，没有 HEADING_CANDIDATE
            List<DocumentStructureNodeSignal> source = List.of(
                    createHeading(1, 0.95),
                    createSignal(2, DocumentStructureNodeSignalEnum.BODY, 0.9, "正文"));

            List<DocumentStructureNodeSignal> result = resolver.resolve(DOC_TITLE,
                    List.of("line1", "line2"), source);

            assertThat(result).isSameAs(source);
        }

        @Test
        @DisplayName("HEADING_CANDIDATE 置信度不在 [floor, ceil] 范围内 → 透传")
        void shouldPassThroughWhenConfidenceOutOfRange() {
            // 置信度过低和过高
            List<DocumentStructureNodeSignal> source = List.of(
                    createHeadingCandidate(1, 0.3),   // < floor
                    createHeadingCandidate(2, 0.9));  // > ceil

            List<DocumentStructureNodeSignal> result = resolver.resolve(DOC_TITLE,
                    List.of("line1", "line2"), source);

            assertThat(result).isSameAs(source);
        }
    }

    // ======================== LLM 判定结果映射 ========================

    @Nested
    @DisplayName("LLM 判定 — kind 映射 & 结果应用")
    class LlmDisambiguation {

        @Test
        @DisplayName("LLM 判定为 HEADING → kind 更新为 HEADING，置信度提升，理由追加")
        void shouldResolveToHeading() {
            mockLlmResponse("""
                    [
                      {"line_no": 2, "resolved_kind": "HEADING", "level_hint": 1}
                    ]""");

            DocumentStructureNodeSignal ambiguous = createHeadingCandidate(2, 0.6);
            List<DocumentStructureNodeSignal> source = new ArrayList<>(List.of(
                    createSignal(1, DocumentStructureNodeSignalEnum.DOCUMENT_TITLE, 1.0, "文档"),
                    ambiguous));

            resolver.resolve(DOC_TITLE, List.of("title", "line2"), source);

            assertThat(ambiguous.getKind()).isEqualTo(DocumentStructureNodeSignalEnum.HEADING);
            assertThat(ambiguous.getConfidence()).isGreaterThanOrEqualTo(0.88);
            assertThat(ambiguous.getReasons()).contains("llm-disambiguated");
            assertThat(ambiguous.getLevelHint()).isEqualTo(1);
        }

        @Test
        @DisplayName("LLM 判定为 LIST_ITEM → kind 更新为 LIST_ITEM")
        void shouldResolveToListItem() {
            mockLlmResponse("""
                    [
                      {"line_no": 3, "resolved_kind": "LIST_ITEM", "level_hint": null}
                    ]""");

            DocumentStructureNodeSignal ambiguous = createHeadingCandidate(3, 0.58);
            List<DocumentStructureNodeSignal> source = new ArrayList<>(List.of(ambiguous));

            resolver.resolve(DOC_TITLE, List.of("line3"), source);

            assertThat(ambiguous.getKind()).isEqualTo(DocumentStructureNodeSignalEnum.LIST_ITEM);
            assertThat(ambiguous.getConfidence()).isGreaterThanOrEqualTo(0.88);
            assertThat(ambiguous.getReasons()).contains("llm-disambiguated");
        }

        @Test
        @DisplayName("LLM 判定为 BODY → kind 更新为 BODY")
        void shouldResolveToBody() {
            mockLlmResponse("""
                    [
                      {"line_no": 1, "resolved_kind": "BODY", "level_hint": null}
                    ]""");

            DocumentStructureNodeSignal ambiguous = createHeadingCandidate(1, 0.62);
            List<DocumentStructureNodeSignal> source = new ArrayList<>(List.of(ambiguous));

            resolver.resolve(DOC_TITLE, List.of("line1"), source);

            assertThat(ambiguous.getKind()).isEqualTo(DocumentStructureNodeSignalEnum.BODY);
            assertThat(ambiguous.getConfidence()).isGreaterThanOrEqualTo(0.88);
        }

        @Test
        @DisplayName("LLM 返回未知 kind → 兜底为 BODY")
        void shouldDefaultToBodyForUnknownKind() {
            mockLlmResponse("""
                    [
                      {"line_no": 1, "resolved_kind": "UNKNOWN_XYZ", "level_hint": null}
                    ]""");

            DocumentStructureNodeSignal ambiguous = createHeadingCandidate(1, 0.6);
            List<DocumentStructureNodeSignal> source = new ArrayList<>(List.of(ambiguous));

            resolver.resolve(DOC_TITLE, List.of("line1"), source);

            assertThat(ambiguous.getKind()).isEqualTo(DocumentStructureNodeSignalEnum.BODY);
        }

        @Test
        @DisplayName("LLM 返回 level_hint=null 时不覆盖已有 levelHint")
        void shouldNotOverrideLevelHintWhenNull() {
            mockLlmResponse("""
                    [
                      {"line_no": 2, "resolved_kind": "HEADING", "level_hint": null}
                    ]""");

            DocumentStructureNodeSignal ambiguous = createHeadingCandidate(2, 0.6);
            ambiguous.setLevelHint(2); // 规则引擎给的 levelHint

            List<DocumentStructureNodeSignal> source = new ArrayList<>(List.of(ambiguous));

            resolver.resolve(DOC_TITLE, List.of("line2"), source);

            assertThat(ambiguous.getKind()).isEqualTo(DocumentStructureNodeSignalEnum.HEADING);
            // levelHint 为 null 时不应该覆盖原来的值...
            // Wait, the code only sets levelHint when result.levelHint() != null
            assertThat(ambiguous.getLevelHint()).isEqualTo(2); // 保持原值
        }

        @Test
        @DisplayName("置信度 ≥0.88 时不再提升")
        void shouldNotLowerConfidence() {
            mockLlmResponse("""
                    [
                      {"line_no": 1, "resolved_kind": "HEADING", "level_hint": 1}
                    ]""");

            DocumentStructureNodeSignal ambiguous = createHeadingCandidate(1, 0.95); // 已经很高
            List<DocumentStructureNodeSignal> source = new ArrayList<>(List.of(ambiguous));

            resolver.resolve(DOC_TITLE, List.of("line1"), source);

            assertThat(ambiguous.getConfidence()).isEqualTo(0.95); // 不应降低
        }

        @Test
        @DisplayName("不在 LLM 返回结果中的信号 → 保持原样")
        void shouldKeepUnrelatedSignalsUnchanged() {
            mockLlmResponse("""
                    [
                      {"line_no": 2, "resolved_kind": "HEADING", "level_hint": 1}
                    ]""");

            DocumentStructureNodeSignal heading = createHeading(1, 0.95);
            DocumentStructureNodeSignal ambiguous = createHeadingCandidate(2, 0.6);
            DocumentStructureNodeSignal body = createSignal(3, DocumentStructureNodeSignalEnum.BODY, 0.9, "正文");

            List<DocumentStructureNodeSignal> source = new ArrayList<>(List.of(heading, ambiguous, body));

            resolver.resolve(DOC_TITLE, List.of("line1", "line2", "line3"), source);

            // heading 和 body 不受影响
            assertThat(heading.getKind()).isEqualTo(DocumentStructureNodeSignalEnum.HEADING);
            assertThat(body.getKind()).isEqualTo(DocumentStructureNodeSignalEnum.BODY);
            assertThat(heading.getReasons()).doesNotContain("llm-disambiguated");
            assertThat(body.getReasons()).doesNotContain("llm-disambiguated");
            // ambiguous 被更新
            assertThat(ambiguous.getKind()).isEqualTo(DocumentStructureNodeSignalEnum.HEADING);
            assertThat(ambiguous.getReasons()).contains("llm-disambiguated");
        }
    }

    // ======================== 优雅降级 ========================

    @Nested
    @DisplayName("优雅降级 — LLM 异常时透传")
    class GracefulDegradation {

        @Test
        @DisplayName("LLM 调用异常 → 透传原始信号")
        void shouldPassThroughOnLlmException() {
            doThrow(new RuntimeException("网络超时")).when(resolver)
                    .callLlm(any(ChatModel.class), anyString());

            DocumentStructureNodeSignal ambiguous = createHeadingCandidate(1, 0.6);
            List<DocumentStructureNodeSignal> source = new ArrayList<>(List.of(ambiguous));

            List<DocumentStructureNodeSignal> result = resolver.resolve(DOC_TITLE, List.of("line1"), source);

            assertThat(result).isSameAs(source);
            assertThat(ambiguous.getKind()).isEqualTo(DocumentStructureNodeSignalEnum.HEADING_CANDIDATE);
            assertThat(ambiguous.getReasons()).doesNotContain("llm-disambiguated");
        }

        @Test
        @DisplayName("LLM 响应无有效 JSON → 降级透传")
        void shouldPassThroughOnInvalidJson() {
            doReturn("这不是有效的 JSON 响应").when(resolver)
                    .callLlm(any(ChatModel.class), anyString());

            DocumentStructureNodeSignal ambiguous = createHeadingCandidate(1, 0.6);
            List<DocumentStructureNodeSignal> source = new ArrayList<>(List.of(ambiguous));

            List<DocumentStructureNodeSignal> result = resolver.resolve(DOC_TITLE, List.of("line1"), source);

            assertThat(result).isSameAs(source);
        }
    }

    // ======================== 截断 ========================

    @Nested
    @DisplayName("maxAmbiguousSignalsPerCall 截断")
    class Truncation {

        @Test
        @DisplayName("模糊信号超过上限时只取前 N 条")
        void shouldTruncateToMaxSignals() {
            when(structureParsing.getMaxAmbiguousSignalsPerCall()).thenReturn(2);

            // LLM 只返回前两条的结果
            mockLlmResponse("""
                    [
                      {"line_no": 1, "resolved_kind": "HEADING", "level_hint": 1},
                      {"line_no": 2, "resolved_kind": "LIST_ITEM", "level_hint": null}
                    ]""");

            List<DocumentStructureNodeSignal> source = new ArrayList<>(List.of(
                    createHeadingCandidate(1, 0.6),
                    createHeadingCandidate(2, 0.6),
                    createHeadingCandidate(3, 0.6),  // 被截断
                    createHeadingCandidate(4, 0.6))); // 被截断

            resolver.resolve(DOC_TITLE,
                    List.of("l1", "l2", "l3", "l4"), source);

            assertThat(source.get(0).getKind()).isEqualTo(DocumentStructureNodeSignalEnum.HEADING);
            assertThat(source.get(1).getKind()).isEqualTo(DocumentStructureNodeSignalEnum.LIST_ITEM);
            // 被截断的保持原样
            assertThat(source.get(2).getKind()).isEqualTo(DocumentStructureNodeSignalEnum.HEADING_CANDIDATE);
            assertThat(source.get(3).getKind()).isEqualTo(DocumentStructureNodeSignalEnum.HEADING_CANDIDATE);
        }
    }

    // ======================== JSON 解析 ========================

    @Nested
    @DisplayName("JSON 响应解析")
    class JsonParsing {

        @Test
        @DisplayName("LLM 在 JSON 前后附带解释文字 → 正确提取")
        void shouldExtractJsonWithSurroundingText() {
            String response = "以下是判定结果：\n[\n  {\"line_no\": 1, \"resolved_kind\": \"HEADING\", \"level_hint\": 1}\n]\n判定完成。";
            String extracted = resolver.extractJsonArray(response);
            assertThat(extracted).startsWith("[").endsWith("]");
        }

        @Test
        @DisplayName("多个候选行 → 正确解析")
        void shouldParseMultipleResults() {
            String json = """
                    [
                      {"line_no": 1, "resolved_kind": "HEADING", "level_hint": 1},
                      {"line_no": 3, "resolved_kind": "LIST_ITEM", "level_hint": null},
                      {"line_no": 5, "resolved_kind": "BODY", "level_hint": null}
                    ]""";

            Map<Integer, DocumentStructureNodeAmbiguityResolver.DisambiguationResult> results =
                    resolver.parseResponse(json);

            assertThat(results).hasSize(3);
            assertThat(results.get(1).resolvedKind()).isEqualTo("HEADING");
            assertThat(results.get(1).levelHint()).isEqualTo(1);
            assertThat(results.get(3).resolvedKind()).isEqualTo("LIST_ITEM");
            assertThat(results.get(5).resolvedKind()).isEqualTo("BODY");
        }

        @Test
        @DisplayName("LLM 返回大小写混合 → 正确映射")
        void shouldHandleCaseInsensitive() {
            String json = """
                    [
                      {"line_no": 1, "resolved_kind": "heading", "level_hint": 2}
                    ]""";

            Map<Integer, DocumentStructureNodeAmbiguityResolver.DisambiguationResult> results =
                    resolver.parseResponse(json);

            assertThat(results.get(1).resolvedKind()).isEqualTo("heading");
        }

        @Test
        @DisplayName("缺少 line_no 字段 → 跳过该条")
        void shouldSkipEntryWithoutLineNo() {
            String json = """
                    [
                      {"resolved_kind": "HEADING", "level_hint": 1},
                      {"line_no": 2, "resolved_kind": "LIST_ITEM", "level_hint": null}
                    ]""";

            Map<Integer, DocumentStructureNodeAmbiguityResolver.DisambiguationResult> results =
                    resolver.parseResponse(json);

            assertThat(results).hasSize(1);
            assertThat(results.get(2)).isNotNull();
        }
    }

    // ======================== kind 映射 ========================

    @Nested
    @DisplayName("mapResolvedKind")
    class MapResolvedKind {

        @Test
        @DisplayName("HEADING → HEADING")
        void shouldMapHeading() {
            assertThat(resolver.mapResolvedKind("HEADING"))
                    .isEqualTo(DocumentStructureNodeSignalEnum.HEADING);
        }

        @Test
        @DisplayName("LIST_ITEM → LIST_ITEM")
        void shouldMapListItem() {
            assertThat(resolver.mapResolvedKind("LIST_ITEM"))
                    .isEqualTo(DocumentStructureNodeSignalEnum.LIST_ITEM);
        }

        @Test
        @DisplayName("其他 → BODY")
        void shouldMapOtherToBody() {
            assertThat(resolver.mapResolvedKind("QUOTE"))
                    .isEqualTo(DocumentStructureNodeSignalEnum.BODY);
            assertThat(resolver.mapResolvedKind(null))
                    .isEqualTo(DocumentStructureNodeSignalEnum.BODY);
        }
    }

    // ======================== 模糊信号筛选 ========================

    @Nested
    @DisplayName("filterAmbiguousSignals — 筛选与截断")
    class FilterAmbiguousSignals {

        @Test
        @DisplayName("只筛选置信度在 [floor, ceil] 范围内的 HEADING_CANDIDATE")
        void shouldFilterByKindAndConfidence() {
            List<DocumentStructureNodeSignal> signals = List.of(
                    createHeadingCandidate(1, 0.3),   // 过低 → 被过滤
                    createHeadingCandidate(2, 0.5),   // ✓ 在范围内
                    createHeadingCandidate(3, 0.75),  // ✓ 在范围内
                    createHeadingCandidate(4, 0.9),   // 过高 → 被过滤
                    createHeading(5, 0.6));           // 不是 HEADING_CANDIDATE → 被过滤

            List<DocumentStructureNodeSignal> filtered = resolver.filterAmbiguousSignals(signals, structureParsing);

            assertThat(filtered).hasSize(2);
            assertThat(filtered.get(0).getLogicalLineNo()).isEqualTo(2);
            assertThat(filtered.get(1).getLogicalLineNo()).isEqualTo(3);
        }
    }
}
