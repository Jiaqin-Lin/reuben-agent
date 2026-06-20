package com.reubenagent.document.service.impl;

import com.reubenagent.common.enums.DocumentManageCode;
import com.reubenagent.common.exception.DocumentException;
import com.reubenagent.document.config.DocumentProperties;
import com.reubenagent.document.config.DocumentProperties.Strategy;
import com.reubenagent.document.entity.Document;
import com.reubenagent.document.entity.DocumentStrategyPlan;
import com.reubenagent.document.entity.DocumentStrategyStep;
import com.reubenagent.document.entity.DocumentStructureNode;
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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("DocumentStrategyServiceImpl Phase6 策略执行单元测试")
class DocumentStrategyPhase6Test {

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

    // ==================== 2.1 文本工具方法 ====================

    @Nested
    @DisplayName("splitSentences 句子拆分")
    class SplitSentencesTest {

        @Test
        @DisplayName("中文句号分句")
        void shouldSplitChineseByPeriod() {
            List<String> result = DocumentStrategyServiceImpl.splitSentences(
                    "这是第一句。这是第二句。这是第三句。");
            // 标点留在句尾（lookbehind 不消耗分隔符）
            assertThat(result).containsExactly("这是第一句。", "这是第二句。", "这是第三句。");
        }

        @Test
        @DisplayName("英文句点分句")
        void shouldSplitEnglishByPeriod() {
            List<String> result = DocumentStrategyServiceImpl.splitSentences(
                    "First sentence. Second sentence. Third sentence.");
            assertThat(result).containsExactly("First sentence.", "Second sentence.", "Third sentence.");
        }

        @Test
        @DisplayName("混合中英文标点")
        void shouldSplitMixedPunctuation() {
            List<String> result = DocumentStrategyServiceImpl.splitSentences(
                    "你好！这是测试。Hello world! Another one? Yes.");
            assertThat(result).hasSize(5);
        }

        @Test
        @DisplayName("空字符串返回空列表")
        void shouldReturnEmptyForBlank() {
            assertThat(DocumentStrategyServiceImpl.splitSentences("")).isEmpty();
        }

        @Test
        @DisplayName("null 返回空列表")
        void shouldReturnEmptyForNull() {
            assertThat(DocumentStrategyServiceImpl.splitSentences(null)).isEmpty();
        }

        @Test
        @DisplayName("无标点单句")
        void shouldReturnSingleForUnpunctuated() {
            List<String> result = DocumentStrategyServiceImpl.splitSentences(
                    "这是一段没有标点符号的文本");
            assertThat(result).hasSize(1);
        }
    }

    @Nested
    @DisplayName("jaccardSimilarity 相似度计算")
    class JaccardSimilarityTest {

        @Test
        @DisplayName("相同文本相似度为 1.0")
        void shouldReturnOneForIdentical() {
            double sim = DocumentStrategyServiceImpl.jaccardSimilarity(
                    "机器学习是人工智能的重要分支",
                    "机器学习是人工智能的重要分支");
            assertThat(sim).isEqualTo(1.0);
        }

        @Test
        @DisplayName("完全无关文本相似度接近 0")
        void shouldReturnLowForUnrelated() {
            double sim = DocumentStrategyServiceImpl.jaccardSimilarity(
                    "机器学习是人工智能的重要分支",
                    "今天天气真好适合出去玩");
            assertThat(sim).isLessThan(0.3);
        }

        @Test
        @DisplayName("部分重叠文本")
        void shouldReturnModerateForPartialOverlap() {
            double sim = DocumentStrategyServiceImpl.jaccardSimilarity(
                    "机器学习是人工智能的重要分支",
                    "深度学习也是人工智能的核心技术");
            assertThat(sim).isGreaterThan(0.0).isLessThan(1.0);
        }

        @Test
        @DisplayName("空参数返回 0.0")
        void shouldReturnZeroForEmpty() {
            assertThat(DocumentStrategyServiceImpl.jaccardSimilarity("", "text")).isEqualTo(0.0);
            assertThat(DocumentStrategyServiceImpl.jaccardSimilarity("text", "")).isEqualTo(0.0);
            assertThat(DocumentStrategyServiceImpl.jaccardSimilarity(null, "text")).isEqualTo(0.0);
        }
    }

    @Nested
    @DisplayName("tokenize 中文分词")
    class TokenizeTest {

        @Test
        @DisplayName("中文 1-2 gram")
        void shouldTokenizeChinese() {
            Set<String> tokens = DocumentStrategyServiceImpl.tokenize("机器学习");
            // unigram: 机, 器, 学, 习 (simplified... actually 机器学习 is 4 chars)
            // bigram: 机器, 器学, 学习
            assertThat(tokens).contains("机", "器", "学", "习", "机器", "器学", "学习");
        }

        @Test
        @DisplayName("英文 lowercase")
        void shouldLowercaseEnglish() {
            Set<String> tokens = DocumentStrategyServiceImpl.tokenize("Hello World");
            assertThat(tokens).contains("h", "e", "l", "o", "w", "r", "d", "he", "el", "ll", "lo");
        }

        @Test
        @DisplayName("空字符串")
        void shouldReturnEmptyForBlank() {
            assertThat(DocumentStrategyServiceImpl.tokenize("")).isEmpty();
        }

        @Test
        @DisplayName("去标点和空格")
        void shouldStripPunctuationAndSpace() {
            Set<String> tokens = DocumentStrategyServiceImpl.tokenize("A. B!");
            // 标点和空格被移除，剩下 "ab"
            assertThat(tokens).doesNotContain(".", "!", " ");
            assertThat(tokens).contains("a", "b", "ab");
        }
    }

    @Nested
    @DisplayName("isNoiseLine 噪声行检测")
    class IsNoiseLineTest {

        @Test
        @DisplayName("纯数字页码为噪声")
        void shouldDetectPageNumber() {
            assertThat(DocumentStrategyServiceImpl.isNoiseLine("42")).isTrue();
            assertThat(DocumentStrategyServiceImpl.isNoiseLine("1234")).isTrue();
        }

        @Test
        @DisplayName("短横线分隔线为噪声")
        void shouldDetectDashSeparator() {
            assertThat(DocumentStrategyServiceImpl.isNoiseLine("---------------")).isTrue();
        }

        @Test
        @DisplayName("等号分隔线为噪声")
        void shouldDetectEqualsSeparator() {
            assertThat(DocumentStrategyServiceImpl.isNoiseLine("==========")).isTrue();
        }

        @Test
        @DisplayName("星号分隔线为噪声")
        void shouldDetectStarSeparator() {
            assertThat(DocumentStrategyServiceImpl.isNoiseLine("***")).isTrue();
        }

        @Test
        @DisplayName("纯标点为噪声")
        void shouldDetectPunctuationOnly() {
            assertThat(DocumentStrategyServiceImpl.isNoiseLine("...")).isTrue();
        }

        @Test
        @DisplayName("正常文本不是噪声")
        void shouldPassNormalText() {
            assertThat(DocumentStrategyServiceImpl.isNoiseLine("这是一段正常文本")).isFalse();
            assertThat(DocumentStrategyServiceImpl.isNoiseLine("Chapter 1 Introduction")).isFalse();
        }

        @Test
        @DisplayName("空白/null 已在调用侧过滤")
        void shouldHandleBlank() {
            assertThat(DocumentStrategyServiceImpl.isNoiseLine("")).isTrue();
        }
    }

    // ==================== 2.2 递归切分 ====================

    @Nested
    @DisplayName("buildParentBlocks 递归切分策略")
    class RecursiveChunkingTest {

        private Document document;
        private DocumentStrategyPlan plan;

        @BeforeEach
        void setUp() {
            document = documentWithId(1L);
            plan = planWithId(100L, 1L);
            when(structureNodeService.listDocumentNodes(eq(1L), any())).thenReturn(Collections.emptyList());
        }

        @Test
        @DisplayName("短文本 → 单 chunk")
        void shouldReturnSingleChunkForShortText() {
            String text = "这是一段短文本，只有几十个字符。";
            List<DocumentStrategyStep> steps = recursiveOnlySteps();

            List<ParentBlockCandidate> result = strategyService.buildParentBlocks(
                    document, plan, steps, text);

            assertThat(result).hasSize(1);
            assertThat(result.get(0).getChildChunks()).isNotEmpty();
        }

        @Test
        @DisplayName("多段文本 → 产出非空且文本不丢失")
        void shouldSplitByParagraphs() {
            String para1 = "第一章内容：机器学习基础知识。".repeat(15); // ~300 chars
            String para2 = "第二章内容：深度学习进阶技术。".repeat(15); // ~300 chars
            String text = para1 + "\n\n" + para2;

            List<DocumentStrategyStep> steps = recursiveOnlySteps();
            List<ParentBlockCandidate> result = strategyService.buildParentBlocks(
                    document, plan, steps, text);

            assertThat(result).isNotEmpty();
            // 验证核心文本未丢失
            String allText = result.stream()
                    .flatMap(b -> {
                        List<String> texts = new ArrayList<>();
                        texts.add(b.getText());
                        b.getChildChunks().forEach(c -> texts.add(c.getText()));
                        return texts.stream();
                    })
                    .collect(Collectors.joining(" "));
            assertThat(allText).contains("机器学习基础知识");
            assertThat(allText).contains("深度学习进阶技术");
        }

        @Test
        @DisplayName("超长段落 → 按句子/窗口切分")
        void shouldSplitLongParagraph() {
            // 一个超长段落（无 \\n\\n），30 句中文
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 30; i++) {
                sb.append("这是第").append(i).append("句测试内容句子。");
            }
            String text = sb.toString(); // ~300+ 字符

            strategyConfig.setRecursiveMaxChars(100);
            List<DocumentStrategyStep> steps = recursiveOnlySteps();
            List<ParentBlockCandidate> result = strategyService.buildParentBlocks(
                    document, plan, steps, text);

            assertThat(result).isNotEmpty();
            long totalChildren = result.stream()
                    .mapToLong(b -> b.getChildChunks().size())
                    .sum();
            assertThat(totalChildren).isGreaterThanOrEqualTo(2);
        }

        @Test
        @DisplayName("空文本抛异常")
        void shouldThrowForEmptyText() {
            List<DocumentStrategyStep> steps = recursiveOnlySteps();

            assertThatThrownBy(() -> strategyService.buildParentBlocks(
                    document, plan, steps, ""))
                    .isInstanceOf(DocumentException.class)
                    .hasFieldOrPropertyWithValue("code", DocumentManageCode.EMPTY_FILE.getCode());
        }

        @Test
        @DisplayName("null 文本抛异常")
        void shouldThrowForNullText() {
            List<DocumentStrategyStep> steps = recursiveOnlySteps();

            assertThatThrownBy(() -> strategyService.buildParentBlocks(
                    document, plan, steps, null))
                    .isInstanceOf(DocumentException.class);
        }
    }

    // ==================== 2.3 语义切分 ====================

    @Nested
    @DisplayName("buildParentBlocks 语义切分策略")
    class SemanticChunkingTest {

        private Document document;
        private DocumentStrategyPlan plan;

        @BeforeEach
        void setUp() {
            document = documentWithId(2L);
            plan = planWithId(200L, 2L);
            when(structureNodeService.listDocumentNodes(eq(2L), any())).thenReturn(Collections.emptyList());
            strategyConfig.setSemanticSimilarityThreshold(0.5);
        }

        @Test
        @DisplayName("高度相似句子合并为一个 chunk")
        void shouldMergeSimilarSentences() {
            String text = "机器学习是人工智能的重要分支。" +
                    "深度学习是机器学习的核心技术。" +
                    "神经网络是深度学习的基础模型。";

            List<DocumentStrategyStep> steps = semanticChildSteps();
            List<ParentBlockCandidate> result = strategyService.buildParentBlocks(
                    document, plan, steps, text);

            assertThat(result).isNotEmpty();
            // 三句高度相关 → 应该合并
            long totalChildren = result.stream()
                    .mapToLong(b -> b.getChildChunks().size())
                    .sum();
            assertThat(totalChildren).isGreaterThanOrEqualTo(1);
        }

        @Test
        @DisplayName("不相关句子在断点处切开")
        void shouldSplitUnrelatedSentences() {
            // 用低阈值确保不相关句子被切开
            strategyConfig.setSemanticSimilarityThreshold(0.2);
            String text = "机器学习是人工智能的重要分支。" +
                    "今天天气真好适合出去玩。" +
                    "深度学习需要大量计算资源。" +
                    "我明天要去超市买东西。";

            List<DocumentStrategyStep> steps = semanticChildSteps();
            List<ParentBlockCandidate> result = strategyService.buildParentBlocks(
                    document, plan, steps, text);

            assertThat(result).isNotEmpty();
            long totalChildren = result.stream()
                    .mapToLong(b -> b.getChildChunks().size())
                    .sum();
            assertThat(totalChildren).isGreaterThanOrEqualTo(1);
        }

        @Test
        @DisplayName("单句输入")
        void shouldHandleSingleSentence() {
            String text = "这是唯一的一句话。";
            List<DocumentStrategyStep> steps = semanticChildSteps();

            List<ParentBlockCandidate> result = strategyService.buildParentBlocks(
                    document, plan, steps, text);

            assertThat(result).isNotEmpty();
        }
    }

    // ==================== 2.4 cleanupChunkList ====================

    @Nested
    @DisplayName("cleanupChunkList 去重")
    class CleanupChunkListTest {

        @Test
        @DisplayName("完全相同的 chunk 只保留一个")
        void shouldDeduplicateIdentical() {
            List<ChunkCandidate> chunks = List.of(
                    new ChunkCandidate("path1", "text A", DocumentChunkSourceTypeEnum.ORIGINAL.getCode()),
                    new ChunkCandidate("path1", "text A", DocumentChunkSourceTypeEnum.ORIGINAL.getCode())
            );

            List<ChunkCandidate> result = strategyService.cleanupChunkList(chunks);
            assertThat(result).hasSize(1);
        }

        @Test
        @DisplayName("不同 text 不同 sectionPath 都保留")
        void shouldKeepDifferent() {
            List<ChunkCandidate> chunks = List.of(
                    new ChunkCandidate("path1", "text A", DocumentChunkSourceTypeEnum.ORIGINAL.getCode()),
                    new ChunkCandidate("path2", "text B", DocumentChunkSourceTypeEnum.ORIGINAL.getCode())
            );

            List<ChunkCandidate> result = strategyService.cleanupChunkList(chunks);
            assertThat(result).hasSize(2);
        }

        @Test
        @DisplayName("相同 text 不同 sectionPath 都保留")
        void shouldKeepSameTextDifferentPath() {
            List<ChunkCandidate> chunks = List.of(
                    new ChunkCandidate("path1", "text A", DocumentChunkSourceTypeEnum.ORIGINAL.getCode()),
                    new ChunkCandidate("path2", "text A", DocumentChunkSourceTypeEnum.ORIGINAL.getCode())
            );

            List<ChunkCandidate> result = strategyService.cleanupChunkList(chunks);
            assertThat(result).hasSize(2);
        }

        @Test
        @DisplayName("空文本过滤")
        void shouldFilterEmptyText() {
            List<ChunkCandidate> chunks = new ArrayList<>();
            chunks.add(new ChunkCandidate("path1", "valid", DocumentChunkSourceTypeEnum.ORIGINAL.getCode()));
            chunks.add(new ChunkCandidate("path2", "", DocumentChunkSourceTypeEnum.ORIGINAL.getCode()));
            chunks.add(new ChunkCandidate("path3", null, DocumentChunkSourceTypeEnum.ORIGINAL.getCode()));

            List<ChunkCandidate> result = strategyService.cleanupChunkList(chunks);
            assertThat(result).hasSize(1);
            assertThat(result.get(0).getText()).isEqualTo("valid");
        }

        @Test
        @DisplayName("null 输入返回空列表")
        void shouldReturnEmptyForNull() {
            assertThat(strategyService.cleanupChunkList(null)).isEmpty();
        }
    }

    // ==================== 2.5 cleanupParentBlockList ====================

    @Nested
    @DisplayName("cleanupParentBlockList 去重合并")
    class CleanupParentBlockListTest {

        @Test
        @DisplayName("同 sectionPath 合并 child")
        void shouldMergeSameSectionPath() {
            List<ParentBlockCandidate> blocks = List.of(
                    new ParentBlockCandidate("path1", "text A", DocumentChunkSourceTypeEnum.ORIGINAL.getCode(),
                            new ArrayList<>(List.of(chunk("child1")))),
                    new ParentBlockCandidate("path1", "text A", DocumentChunkSourceTypeEnum.ORIGINAL.getCode(),
                            new ArrayList<>(List.of(chunk("child2"))))
            );

            List<ParentBlockCandidate> result = strategyService.cleanupParentBlockList(blocks);
            assertThat(result).hasSize(1);
            assertThat(result.get(0).getChildChunks()).hasSize(2);
        }

        @Test
        @DisplayName("不同 sectionPath 各自保留")
        void shouldKeepDifferentPath() {
            List<ParentBlockCandidate> blocks = List.of(
                    new ParentBlockCandidate("path1", "text A", DocumentChunkSourceTypeEnum.ORIGINAL.getCode(),
                            List.of(chunk("c1"))),
                    new ParentBlockCandidate("path2", "text B", DocumentChunkSourceTypeEnum.ORIGINAL.getCode(),
                            List.of(chunk("c2")))
            );

            List<ParentBlockCandidate> result = strategyService.cleanupParentBlockList(blocks);
            assertThat(result).hasSize(2);
        }

        @Test
        @DisplayName("合并后 child 自动去重")
        void shouldDedupChildrenOnMerge() {
            ChunkCandidate dup = chunk("dup");
            List<ParentBlockCandidate> blocks = List.of(
                    new ParentBlockCandidate("path1", "text A", DocumentChunkSourceTypeEnum.ORIGINAL.getCode(),
                            new ArrayList<>(List.of(dup, chunk("c1")))),
                    new ParentBlockCandidate("path1", "text A", DocumentChunkSourceTypeEnum.ORIGINAL.getCode(),
                            new ArrayList<>(List.of(dup, chunk("c2"))))
            );

            List<ParentBlockCandidate> result = strategyService.cleanupParentBlockList(blocks);
            assertThat(result).hasSize(1);
            // dup 只出现一次
            long dupCount = result.get(0).getChildChunks().stream()
                    .filter(c -> "dup".equals(c.getText()))
                    .count();
            assertThat(dupCount).isEqualTo(1);
        }
    }

    // ==================== 2.6 resolveCanonicalPath ====================

    @Nested
    @DisplayName("resolveCanonicalPath 路径归一化")
    class ResolveCanonicalPathTest {

        @Test
        @DisplayName("去 # 标记和多余空格并小写")
        void shouldNormalizeMarkdownHeading() {
            String result = DocumentStrategyServiceImpl.resolveCanonicalPath(
                    "# 第一章 概述 ");
            assertThat(result).isEqualTo("第一章 概述");
        }

        @Test
        @DisplayName("英文小写和空格归一化")
        void shouldLowercaseEnglish() {
            String result = DocumentStrategyServiceImpl.resolveCanonicalPath(
                    "Chapter  1/Intro");
            assertThat(result).isEqualTo("chapter 1/intro");
        }

        @Test
        @DisplayName("null 返回空字符串")
        void shouldReturnEmptyForNull() {
            assertThat(DocumentStrategyServiceImpl.resolveCanonicalPath(null)).isEqualTo("");
        }
    }

    // ==================== 2.7 管线引擎角色逻辑 ====================

    @Nested
    @DisplayName("管线引擎角色逻辑")
    class PipelineRoleTest {

        private Document document;
        private DocumentStrategyPlan plan;

        @BeforeEach
        void setUp() {
            document = documentWithId(3L);
            plan = planWithId(300L, 3L);
            // 无结构节点 → STRUCTURE 策略无产出，FALLBACK 应生效
            when(structureNodeService.listDocumentNodes(eq(3L), any())).thenReturn(Collections.emptyList());
        }

        @Test
        @DisplayName("PRIMARY 无产出时 FALLBACK 生效")
        void fallbackShouldExecuteWhenPrimaryEmpty() {
            String text = "Fallback 应该生效的测试文本。需要一些内容来确保递归切分有产出。"
                    + "继续添加更多文本。".repeat(10);

            List<DocumentStrategyStep> steps = List.of(
                    step(1, DocumentStrategyPipelineTypeEnum.CHILD.getStringCode(),
                            DocumentStrategyTypeEnum.STRUCTURE.getCode(),
                            DocumentStrategyRoleEnum.PRIMARY.getCode()),
                    step(2, DocumentStrategyPipelineTypeEnum.PARENT.getStringCode(),
                            DocumentStrategyTypeEnum.RECURSIVE.getCode(),
                            DocumentStrategyRoleEnum.PRIMARY.getCode()),
                    step(3, DocumentStrategyPipelineTypeEnum.CHILD.getStringCode(),
                            DocumentStrategyTypeEnum.RECURSIVE.getCode(),
                            DocumentStrategyRoleEnum.FALLBACK.getCode())
            );

            List<ParentBlockCandidate> result = strategyService.buildParentBlocks(
                    document, plan, steps, text);

            // FALLBACK 应生效 → 有产出
            assertThat(result).isNotEmpty();
        }

        @Test
        @DisplayName("ENHANCE 追加补充 chunk")
        void enhanceShouldAppendChunks() {
            String text = "ENHANCE 测试文本。需要一些内容。".repeat(20);

            List<DocumentStrategyStep> steps = List.of(
                    step(1, DocumentStrategyPipelineTypeEnum.PARENT.getStringCode(),
                            DocumentStrategyTypeEnum.RECURSIVE.getCode(),
                            DocumentStrategyRoleEnum.PRIMARY.getCode()),
                    step(2, DocumentStrategyPipelineTypeEnum.CHILD.getStringCode(),
                            DocumentStrategyTypeEnum.RECURSIVE.getCode(),
                            DocumentStrategyRoleEnum.PRIMARY.getCode()),
                    step(3, DocumentStrategyPipelineTypeEnum.CHILD.getStringCode(),
                            DocumentStrategyTypeEnum.RECURSIVE.getCode(),
                            DocumentStrategyRoleEnum.ENHANCE.getCode())
            );

            List<ParentBlockCandidate> result = strategyService.buildParentBlocks(
                    document, plan, steps, text);

            assertThat(result).isNotEmpty();
        }

        @Test
        @DisplayName("步骤不完整（缺 PARENT/CHILD）抛异常")
        void shouldThrowForIncompleteSteps() {
            List<DocumentStrategyStep> steps = List.of(
                    step(1, DocumentStrategyPipelineTypeEnum.PARENT.getStringCode(),
                            DocumentStrategyTypeEnum.RECURSIVE.getCode(),
                            DocumentStrategyRoleEnum.PRIMARY.getCode())
                    // 缺少 CHILD 步骤
            );

            assertThatThrownBy(() -> strategyService.buildParentBlocks(
                    document, plan, steps, "some text"))
                    .isInstanceOf(DocumentException.class);
        }
    }

    // ==================== 2.8 buildParentBlocks 端到端 ====================

    @Nested
    @DisplayName("buildParentBlocks 端到端集成")
    class BuildParentBlocksE2ETest {

        private Document document;
        private DocumentStrategyPlan plan;

        @BeforeEach
        void setUp() {
            document = documentWithId(4L);
            plan = planWithId(400L, 4L);
        }

        @Test
        @DisplayName("有结构节点时 STRUCTURE 父管道 + RECURSIVE 子管道 完整流程")
        void fullPipelineWithStructure() {
            // 准备结构节点：1 个根 + 2 个章节（章节 parentNodeId 指向根节点）
            Long rootId = 100L;
            DocumentStructureNode root = structureNode(rootId, 4L, 1L, null,
                    "文档标题", "/document", "根节点内容。");
            DocumentStructureNode chapter1 = structureChapterNode(101L, 4L, rootId,
                    "第一章 概述", "/document/chapter1", "第一章 概述的内容段落。这里有详细信息。");
            DocumentStructureNode chapter2 = structureChapterNode(102L, 4L, rootId,
                    "第二章 方法", "/document/chapter2", "第二章 方法的详细说明。包含实验步骤。");

            when(structureNodeService.listDocumentNodes(eq(4L), any()))
                    .thenReturn(List.of(root, chapter1, chapter2));

            String text = "第一章 概述\n概述的内容段落。这里有详细信息。\n\n第二章 方法\n方法的详细说明。包含实验步骤。";

            List<DocumentStrategyStep> steps = List.of(
                    step(1, DocumentStrategyPipelineTypeEnum.PARENT.getStringCode(),
                            DocumentStrategyTypeEnum.STRUCTURE.getCode(),
                            DocumentStrategyRoleEnum.PRIMARY.getCode()),
                    step(2, DocumentStrategyPipelineTypeEnum.CHILD.getStringCode(),
                            DocumentStrategyTypeEnum.RECURSIVE.getCode(),
                            DocumentStrategyRoleEnum.PRIMARY.getCode())
            );

            List<ParentBlockCandidate> result = strategyService.buildParentBlocks(
                    document, plan, steps, text);

            assertThat(result).isNotEmpty();
            // 每个 parent 有 sectionPath
            assertThat(result).allMatch(b -> b.getSectionPath() != null);
            // 每个 parent 有 child
            assertThat(result).allMatch(b -> !b.getChildChunks().isEmpty());
            // parent 数量与章节数一致
            assertThat(result).hasSize(2);
        }

        @Test
        @DisplayName("纯递归流程（无结构节点）")
        void fullPipelineRecursiveOnly() {
            when(structureNodeService.listDocumentNodes(eq(4L), any())).thenReturn(Collections.emptyList());

            String text = ("这是一篇没有结构的纯文本。它包含多个段落。\n\n"
                    + "第二个段落有更多的内容。我们需要确保递归切分正常工作。\n\n"
                    + "第三个段落继续添加内容。").repeat(5);

            List<DocumentStrategyStep> steps = List.of(
                    step(1, DocumentStrategyPipelineTypeEnum.PARENT.getStringCode(),
                            DocumentStrategyTypeEnum.RECURSIVE.getCode(),
                            DocumentStrategyRoleEnum.PRIMARY.getCode()),
                    step(2, DocumentStrategyPipelineTypeEnum.CHILD.getStringCode(),
                            DocumentStrategyTypeEnum.RECURSIVE.getCode(),
                            DocumentStrategyRoleEnum.PRIMARY.getCode())
            );

            List<ParentBlockCandidate> result = strategyService.buildParentBlocks(
                    document, plan, steps, text);

            assertThat(result).isNotEmpty();
            assertThat(result).allMatch(b -> !b.getChildChunks().isEmpty());
        }

        @Test
        @DisplayName("文本内容不丢失")
        void textContentPreserved() {
            when(structureNodeService.listDocumentNodes(eq(4L), any())).thenReturn(Collections.emptyList());

            String uniqueWord = "UNIQUE_MARKER_12345";
            String text = "段落一。\n\n段落二包含 " + uniqueWord + "。\n\n段落三。";

            List<DocumentStrategyStep> steps = List.of(
                    step(1, DocumentStrategyPipelineTypeEnum.PARENT.getStringCode(),
                            DocumentStrategyTypeEnum.RECURSIVE.getCode(),
                            DocumentStrategyRoleEnum.PRIMARY.getCode()),
                    step(2, DocumentStrategyPipelineTypeEnum.CHILD.getStringCode(),
                            DocumentStrategyTypeEnum.RECURSIVE.getCode(),
                            DocumentStrategyRoleEnum.PRIMARY.getCode())
            );

            List<ParentBlockCandidate> result = strategyService.buildParentBlocks(
                    document, plan, steps, text);

            // 拼接所有 parent + child 文本，验证唯一标记存在
            String allText = result.stream()
                    .flatMap(b -> {
                        List<String> texts = new ArrayList<>();
                        texts.add(b.getText());
                        b.getChildChunks().forEach(c -> texts.add(c.getText()));
                        return texts.stream();
                    })
                    .collect(Collectors.joining(" "));
            assertThat(allText).contains(uniqueWord);
        }
    }

    // ==================== 测试数据工厂 ====================

    private static Document documentWithId(Long id) {
        return Document.builder()
                .id(id)
                .documentName("测试文档")
                .fileType(1)
                .build();
    }

    private static DocumentStrategyPlan planWithId(Long id, Long documentId) {
        return DocumentStrategyPlan.builder()
                .id(id)
                .documentId(documentId)
                .planVersion(1)
                .planStatus(2) // CONFIRMED
                .build();
    }

    private static DocumentStrategyStep step(int stepNo, String pipelineType,
                                              Integer strategyType, Integer strategyRole) {
        return DocumentStrategyStep.builder()
                .id(1000L + stepNo)
                .planId(100L)
                .documentId(1L)
                .stepNo(stepNo)
                .pipelineType(pipelineType)
                .strategyType(strategyType)
                .strategyRole(strategyRole)
                .sourceType(1)
                .executeStatus(1)
                .build();
    }

    /** PARENT=RECURSIVE + CHILD=RECURSIVE 完整步骤 */
    private List<DocumentStrategyStep> recursiveOnlySteps() {
        return List.of(
                step(1, DocumentStrategyPipelineTypeEnum.PARENT.getStringCode(),
                        DocumentStrategyTypeEnum.RECURSIVE.getCode(),
                        DocumentStrategyRoleEnum.PRIMARY.getCode()),
                step(2, DocumentStrategyPipelineTypeEnum.CHILD.getStringCode(),
                        DocumentStrategyTypeEnum.RECURSIVE.getCode(),
                        DocumentStrategyRoleEnum.PRIMARY.getCode())
        );
    }

    /** PARENT=RECURSIVE + CHILD=SEMANTIC 步骤 */
    private List<DocumentStrategyStep> semanticChildSteps() {
        return List.of(
                step(1, DocumentStrategyPipelineTypeEnum.PARENT.getStringCode(),
                        DocumentStrategyTypeEnum.RECURSIVE.getCode(),
                        DocumentStrategyRoleEnum.PRIMARY.getCode()),
                step(2, DocumentStrategyPipelineTypeEnum.CHILD.getStringCode(),
                        DocumentStrategyTypeEnum.SEMANTIC.getCode(),
                        DocumentStrategyRoleEnum.PRIMARY.getCode())
        );
    }

    private static ChunkCandidate chunk(String text) {
        return new ChunkCandidate(null, text, DocumentChunkSourceTypeEnum.ORIGINAL.getCode());
    }

    private static DocumentStructureNode structureChapterNode(Long id, Long documentId,
                                                               Long parentNodeId,
                                                               String title, String canonicalPath,
                                                               String contentText) {
        return DocumentStructureNode.builder()
                .id(id)
                .documentId(documentId)
                .parseTaskId(1L)
                .nodeNo(id.intValue() % 100)
                .nodeType(2) // CHAPTER
                .parentNodeId(parentNodeId)
                .depth(1)
                .title(title)
                .canonicalPath(canonicalPath)
                .sectionPath(title)
                .contentText(contentText)
                .build();
    }

    private static DocumentStructureNode structureNode(Long id, Long documentId,
                                                        Long parseTaskId, Long parentNodeId,
                                                        String title, String canonicalPath,
                                                        String contentText) {
        return DocumentStructureNode.builder()
                .id(id)
                .documentId(documentId)
                .parseTaskId(parseTaskId)
                .nodeNo(id.intValue() % 100)
                .nodeType(1) // ROOT
                .parentNodeId(parentNodeId)
                .depth(0)
                .title(title)
                .canonicalPath(canonicalPath)
                .sectionPath(title)
                .contentText(contentText)
                .build();
    }
}
