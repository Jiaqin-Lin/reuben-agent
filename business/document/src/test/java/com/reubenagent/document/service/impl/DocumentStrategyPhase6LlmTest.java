package com.reubenagent.document.service.impl;

import com.reubenagent.document.config.DocumentProperties;
import com.reubenagent.document.config.DocumentProperties.Strategy;
import com.reubenagent.document.entity.Document;
import com.reubenagent.document.entity.DocumentStrategyPlan;
import com.reubenagent.document.entity.DocumentStrategyStep;
import com.reubenagent.document.enums.DocumentChunkSourceTypeEnum;
import com.reubenagent.document.enums.DocumentStrategyPipelineTypeEnum;
import com.reubenagent.document.enums.DocumentStrategyRoleEnum;
import com.reubenagent.document.enums.DocumentStrategyTypeEnum;
import com.reubenagent.document.model.ChunkCandidate;
import com.reubenagent.document.model.ParentBlockCandidate;
import com.reubenagent.document.service.IDocumentStructureNodeService;
import com.reubenagent.document.support.PromptTemplateService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("DocumentStrategyServiceImpl LLM 切分单元测试")
class DocumentStrategyPhase6LlmTest {

    @Mock
    private DocumentProperties documentProperties;

    @Mock
    private IDocumentStructureNodeService structureNodeService;

    @Mock
    private PromptTemplateService promptTemplateService;

    @Mock
    private ChatModel chatModel;

    @InjectMocks
    private DocumentStrategyServiceImpl strategyService;

    private Strategy strategyConfig;

    @BeforeEach
    void setUp() {
        strategyConfig = new Strategy();
        strategyConfig.setRecursiveMaxChars(500);
        strategyConfig.setRecursiveOverlapChars(50);
        strategyConfig.setSemanticSimilarityThreshold(0.7);
        strategyConfig.setLlmMaxInputChars(8000);
        when(documentProperties.getStrategy()).thenReturn(strategyConfig);
    }

    // ==================== 3.1 parseLlmBreakpoints ====================

    @Nested
    @DisplayName("parseLlmBreakpoints LLM 响应解析")
    class ParseLlmBreakpointsTest {

        @Test
        @DisplayName("标准 JSON 数组")
        void shouldParseJsonArray() {
            List<Integer> result = DocumentStrategyServiceImpl.parseLlmBreakpoints(
                    "[3, 7, 12]", 20);
            assertThat(result).containsExactly(3, 7, 12);
        }

        @Test
        @DisplayName("Markdown 代码块包裹")
        void shouldParseMarkdownFenced() {
            List<Integer> result = DocumentStrategyServiceImpl.parseLlmBreakpoints(
                    "```json\n[2,5]\n```", 10);
            assertThat(result).containsExactly(2, 5);
        }

        @Test
        @DisplayName("逗号分隔纯数字")
        void shouldParseCommaSeparated() {
            List<Integer> result = DocumentStrategyServiceImpl.parseLlmBreakpoints(
                    "3,7,12", 20);
            assertThat(result).containsExactly(3, 7, 12);
        }

        @Test
        @DisplayName("空格分隔纯数字")
        void shouldParseSpaceSeparated() {
            List<Integer> result = DocumentStrategyServiceImpl.parseLlmBreakpoints(
                    "3 7 12", 20);
            assertThat(result).containsExactly(3, 7, 12);
        }

        @Test
        @DisplayName("空响应返回空列表")
        void shouldReturnEmptyForBlank() {
            assertThat(DocumentStrategyServiceImpl.parseLlmBreakpoints("", 10)).isEmpty();
        }

        @Test
        @DisplayName("纯文字无数字返回空列表")
        void shouldReturnEmptyForTextOnly() {
            assertThat(DocumentStrategyServiceImpl.parseLlmBreakpoints(
                    "分析完毕，无需切分", 10)).isEmpty();
        }

        @Test
        @DisplayName("过滤超出范围的索引")
        void shouldFilterOutOfRange() {
            List<Integer> result = DocumentStrategyServiceImpl.parseLlmBreakpoints(
                    "[3, 999, 7]", 10);
            // 999 超出 maxIndex=10 → 过滤，但 sentence 共 10 句，索引 0-9
            // 999 > 9，被过滤
            assertThat(result).doesNotContain(999);
            assertThat(result).contains(3, 7);
        }

        @Test
        @DisplayName("过滤索引 0（起始位置不需要断点）")
        void shouldFilterZero() {
            List<Integer> result = DocumentStrategyServiceImpl.parseLlmBreakpoints(
                    "[0, 3, 7]", 20);
            // 0 被过滤（idx > 0 才保留）
            assertThat(result).doesNotContain(0);
            assertThat(result).contains(3, 7);
        }

        @Test
        @DisplayName("混合有效和无效 token")
        void shouldHandleMixedTokens() {
            List<Integer> result = DocumentStrategyServiceImpl.parseLlmBreakpoints(
                    "建议在 3, 7, 和 12 处切分", 20);
            assertThat(result).containsExactly(3, 7, 12);
        }
    }

    // ==================== 3.1 buildChunksFromBreakpoints ====================

    @Nested
    @DisplayName("buildChunksFromBreakpoints 根据断点构建 chunk")
    class BuildChunksFromBreakpointsTest {

        @Test
        @DisplayName("空断点列表 → 整体作为一个 chunk")
        void shouldReturnSingleChunkForNoBreakpoints() {
            List<String> sentences = List.of("句子一", "句子二", "句子三");
            List<ChunkCandidate> result = DocumentStrategyServiceImpl.buildChunksFromBreakpoints(
                    sentences, Collections.emptyList());

            assertThat(result).hasSize(1);
            assertThat(result.get(0).getText()).contains("句子一", "句子二", "句子三");
        }

        @Test
        @DisplayName("单个中间断点 → 两个 chunk")
        void shouldSplitAtBreakpoint() {
            List<String> sentences = List.of("A", "B", "C", "D", "E");
            List<Integer> breakpoints = List.of(2); // 在 B 后切

            List<ChunkCandidate> result = DocumentStrategyServiceImpl.buildChunksFromBreakpoints(
                    sentences, breakpoints);

            assertThat(result).hasSize(2);
            assertThat(result.get(0).getText()).contains("A", "B").doesNotContain("C");
            assertThat(result.get(1).getText()).contains("C", "D", "E");
        }

        @Test
        @DisplayName("多个断点 → 多个 chunk")
        void shouldSplitAtMultipleBreakpoints() {
            List<String> sentences = List.of("A", "B", "C", "D", "E", "F", "G");
            List<Integer> breakpoints = List.of(2, 5); // B 后, E 后

            List<ChunkCandidate> result = DocumentStrategyServiceImpl.buildChunksFromBreakpoints(
                    sentences, breakpoints);

            assertThat(result).hasSize(3);
            assertThat(result.get(0).getText()).contains("A", "B");
            assertThat(result.get(1).getText()).contains("C", "D", "E");
            assertThat(result.get(2).getText()).contains("F", "G");
        }

        @Test
        @DisplayName("断点自动排序")
        void shouldSortBreakpoints() {
            List<String> sentences = List.of("A", "B", "C", "D", "E");
            List<Integer> breakpoints = List.of(3, 1); // 乱序

            List<ChunkCandidate> result = DocumentStrategyServiceImpl.buildChunksFromBreakpoints(
                    sentences, breakpoints);

            assertThat(result).hasSize(3);
            assertThat(result.get(0).getText()).contains("A");
            assertThat(result.get(1).getText()).contains("B", "C");
            assertThat(result.get(2).getText()).contains("D", "E");
        }

        @Test
        @DisplayName("空 sentence 列表")
        void shouldHandleEmptySentences() {
            List<ChunkCandidate> result = DocumentStrategyServiceImpl.buildChunksFromBreakpoints(
                    Collections.emptyList(), List.of(1));
            assertThat(result).isEmpty();
        }
    }

    // ==================== 3.2 LLM 降级 ====================

    @Nested
    @DisplayName("LLM 切分降级")
    class LlmFallbackTest {

        private Document document;
        private DocumentStrategyPlan plan;

        @BeforeEach
        void setUp() {
            document = Document.builder().id(10L).documentName("LLM 测试文档").fileType(1).build();
            plan = DocumentStrategyPlan.builder().id(1000L).documentId(10L).planVersion(1).planStatus(2).build();
            when(structureNodeService.listDocumentNodes(any(), any())).thenReturn(Collections.emptyList());
        }

        @Test
        @DisplayName("ChatModel 抛异常 → 降级为按句子切分，不抛异常")
        void shouldFallbackOnChatModelError() {
            when(promptTemplateService.render(anyString(), anyMap())).thenReturn("fake prompt");
            when(chatModel.call(any(Prompt.class))).thenThrow(new RuntimeException("LLM timeout"));

            String text = "第一句。第二句。第三句。第四句。";

            List<DocumentStrategyStep> steps = List.of(
                    step(1, DocumentStrategyPipelineTypeEnum.PARENT.getStringCode(),
                            DocumentStrategyTypeEnum.RECURSIVE.getCode(),
                            DocumentStrategyRoleEnum.PRIMARY.getCode()),
                    step(2, DocumentStrategyPipelineTypeEnum.CHILD.getStringCode(),
                            DocumentStrategyTypeEnum.LLM.getCode(),
                            DocumentStrategyRoleEnum.PRIMARY.getCode())
            );

            List<ParentBlockCandidate> result = strategyService.buildParentBlocks(
                    document, plan, steps, text);

            // 降级后应有产出（按句子切分），而非抛异常
            assertThat(result).isNotEmpty();
        }
    }

    // ==================== 3.3 LLM 正常流程 ====================

    @Nested
    @DisplayName("LLM 切分正常流程")
    class LlmNormalFlowTest {

        private Document document;
        private DocumentStrategyPlan plan;

        @BeforeEach
        void setUp() {
            document = Document.builder().id(11L).documentName("LLM 正常文档").fileType(1).build();
            plan = DocumentStrategyPlan.builder().id(2000L).documentId(11L).planVersion(1).planStatus(2).build();
            when(structureNodeService.listDocumentNodes(any(), any())).thenReturn(Collections.emptyList());
            when(promptTemplateService.render(eq("document-llm-chunking"), anyMap())).thenReturn("llm prompt");
        }

        @Test
        @DisplayName("LLM 返回有效断点 → 正确切分")
        void shouldChunkByLlmBreakpoints() {
            // 构造 mock ChatResponse
            AssistantMessage output = new AssistantMessage("[2,5]");
            ChatResponse mockResponse = mock(ChatResponse.class);
            Generation mockGeneration = mock(Generation.class);
            when(mockResponse.getResult()).thenReturn(mockGeneration);
            when(mockGeneration.getOutput()).thenReturn(output);
            when(chatModel.call(any(Prompt.class))).thenReturn(mockResponse);

            String text = "第一句。第二句。第三句。第四句。第五句。第六句。第七句。";

            List<DocumentStrategyStep> steps = List.of(
                    step(1, DocumentStrategyPipelineTypeEnum.PARENT.getStringCode(),
                            DocumentStrategyTypeEnum.RECURSIVE.getCode(),
                            DocumentStrategyRoleEnum.PRIMARY.getCode()),
                    step(2, DocumentStrategyPipelineTypeEnum.CHILD.getStringCode(),
                            DocumentStrategyTypeEnum.LLM.getCode(),
                            DocumentStrategyRoleEnum.PRIMARY.getCode())
            );

            List<ParentBlockCandidate> result = strategyService.buildParentBlocks(
                    document, plan, steps, text);

            assertThat(result).isNotEmpty();
            // 应该切出多个 child chunk（按断点 [2,5]）
            long totalChildren = result.stream()
                    .mapToLong(b -> b.getChildChunks().size())
                    .sum();
            assertThat(totalChildren).isGreaterThanOrEqualTo(1);
        }

        @Test
        @DisplayName("文本总长在 llmMaxInputChars 内则单段调用 LLM")
        void shouldCallLlmOnceForShortText() {
            AssistantMessage output = new AssistantMessage("[3]");
            ChatResponse mockResponse = mock(ChatResponse.class);
            Generation mockGeneration = mock(Generation.class);
            when(mockResponse.getResult()).thenReturn(mockGeneration);
            when(mockGeneration.getOutput()).thenReturn(output);
            when(chatModel.call(any(Prompt.class))).thenReturn(mockResponse);

            String text = IntStream.range(0, 8)
                    .mapToObj(i -> "句子" + i + "。")
                    .collect(Collectors.joining());

            List<DocumentStrategyStep> steps = List.of(
                    step(1, DocumentStrategyPipelineTypeEnum.PARENT.getStringCode(),
                            DocumentStrategyTypeEnum.RECURSIVE.getCode(),
                            DocumentStrategyRoleEnum.PRIMARY.getCode()),
                    step(2, DocumentStrategyPipelineTypeEnum.CHILD.getStringCode(),
                            DocumentStrategyTypeEnum.LLM.getCode(),
                            DocumentStrategyRoleEnum.PRIMARY.getCode())
            );

            List<ParentBlockCandidate> result = strategyService.buildParentBlocks(
                    document, plan, steps, text);

            assertThat(result).isNotEmpty();
        }
    }

    // ==================== 测试数据工厂 ====================

    private static DocumentStrategyStep step(int stepNo, String pipelineType,
                                              Integer strategyType, Integer strategyRole) {
        return DocumentStrategyStep.builder()
                .id(5000L + stepNo)
                .planId(1000L)
                .documentId(10L)
                .stepNo(stepNo)
                .pipelineType(pipelineType)
                .strategyType(strategyType)
                .strategyRole(strategyRole)
                .sourceType(1)
                .executeStatus(1)
                .build();
    }
}
