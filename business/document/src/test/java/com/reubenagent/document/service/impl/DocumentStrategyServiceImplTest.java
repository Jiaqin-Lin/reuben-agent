package com.reubenagent.document.service.impl;

import com.reubenagent.document.config.DocumentProperties;
import com.reubenagent.document.config.DocumentProperties.Strategy;
import com.reubenagent.document.entity.Document;
import com.reubenagent.document.enums.DocumentFileTypeEnum;
import com.reubenagent.document.enums.DocumentStrategyPipelineTypeEnum;
import com.reubenagent.document.enums.DocumentStrategyRoleEnum;
import com.reubenagent.document.enums.DocumentStrategyTypeEnum;
import com.reubenagent.document.model.DocumentParseResult;
import com.reubenagent.document.model.DocumentStrategyPlanDraft;
import com.reubenagent.document.model.DocumentStrategyStepDraft;
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

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("DocumentStrategyServiceImpl 策略推荐单元测试")
class DocumentStrategyServiceImplTest {

    @Mock
    private DocumentProperties documentProperties;

    @InjectMocks
    private DocumentStrategyServiceImpl strategyService;

    private Strategy strategyConfig;

    @BeforeEach
    void setUp() {
        strategyConfig = new Strategy();
        strategyConfig.setRecursiveMaxChars(3000);
        strategyConfig.setSemanticMinChars(1000);
        strategyConfig.setRecommendLlmWhenLowQuality(false);
        when(documentProperties.getStrategy()).thenReturn(strategyConfig);
    }

    // ============ 测试数据工厂 ============

    private static Document documentWithFileType(Integer fileType) {
        return Document.builder()
                .id(1L)
                .fileType(fileType)
                .build();
    }

    private static DocumentParseResult parseResultWith(int charCount, int tokenCount,
                                                        int structureLevel, int contentQuality,
                                                        int headingCount, int paragraphCount,
                                                        int maxParagraphLength) {
        return DocumentParseResult.builder()
                .parsedText("test content")
                .charCount(charCount)
                .tokenCount(tokenCount)
                .structureLevel(structureLevel)
                .contentQualityLevel(contentQuality)
                .headingCount(headingCount)
                .paragraphCount(paragraphCount)
                .maxParagraphLength(maxParagraphLength)
                .build();
    }

    // ============ 结构化文档 → STRUCTURE 父管道 ============

    @Nested
    @DisplayName("结构化文档推荐")
    class StructureRecommendation {

        @Test
        @DisplayName("PDF 高结构化 → 父 STRUCTURE + 子 SEMANTIC,RECURSIVE")
        void shouldRecommendStructureForPdfWithHighStructure() {
            Document doc = documentWithFileType(DocumentFileTypeEnum.PDF.getCode());
            DocumentParseResult result = parseResultWith(5000, 1200, 3, 4, 10, 20, 500);

            DocumentStrategyPlanDraft draft = strategyService.recommendStrategy(doc, result);

            assertThat(draft.getParentSteps()).hasSize(1);
            assertThat(draft.getParentSteps().get(0).getStrategyType())
                    .isEqualTo(DocumentStrategyTypeEnum.STRUCTURE.getCode());
            assertThat(draft.getParentSteps().get(0).getStrategyRole())
                    .isEqualTo(DocumentStrategyRoleEnum.PRIMARY.getCode());
            assertThat(draft.getParentSteps().get(0).getPipelineType())
                    .isEqualTo(DocumentStrategyPipelineTypeEnum.PARENT.getStringCode());

            assertThat(draft.getChildSteps()).hasSize(2);
            assertThat(draft.getChildSteps().get(0).getStrategyType())
                    .isEqualTo(DocumentStrategyTypeEnum.SEMANTIC.getCode());
            assertThat(draft.getChildSteps().get(1).getStrategyType())
                    .isEqualTo(DocumentStrategyTypeEnum.RECURSIVE.getCode());

            assertThat(draft.getStrategySnapshot()).startsWith("PARENT:1;CHILD:3,2");
        }

        @Test
        @DisplayName("MD 有标题 → 父 STRUCTURE")
        void shouldRecommendStructureForMdWithHeadings() {
            Document doc = documentWithFileType(DocumentFileTypeEnum.MD.getCode());
            DocumentParseResult result = parseResultWith(2000, 500, 0, 3, 5, 10, 400);

            DocumentStrategyPlanDraft draft = strategyService.recommendStrategy(doc, result);

            assertThat(draft.getParentSteps().get(0).getStrategyType())
                    .isEqualTo(DocumentStrategyTypeEnum.STRUCTURE.getCode());
        }

        @Test
        @DisplayName("HTML structureLevel=2 → 父 STRUCTURE")
        void shouldRecommendStructureForHtmlWithStructureLevel2() {
            Document doc = documentWithFileType(DocumentFileTypeEnum.HTML.getCode());
            DocumentParseResult result = parseResultWith(1500, 400, 2, 3, 1, 8, 300);

            DocumentStrategyPlanDraft draft = strategyService.recommendStrategy(doc, result);

            assertThat(draft.getParentSteps().get(0).getStrategyType())
                    .isEqualTo(DocumentStrategyTypeEnum.STRUCTURE.getCode());
        }
    }

    // ============ 无结构文档 → RECURSIVE 父管道 ============

    @Nested
    @DisplayName("无结构文档回退推荐")
    class FallbackRecommendation {

        @Test
        @DisplayName("TXT 无结构无标题 → 父 RECURSIVE")
        void shouldFallbackToRecursiveForUnstructuredTxt() {
            Document doc = documentWithFileType(DocumentFileTypeEnum.TXT.getCode());
            DocumentParseResult result = parseResultWith(800, 200, 0, 2, 0, 5, 200);

            DocumentStrategyPlanDraft draft = strategyService.recommendStrategy(doc, result);

            assertThat(draft.getParentSteps().get(0).getStrategyType())
                    .isEqualTo(DocumentStrategyTypeEnum.RECURSIVE.getCode());
        }

        @Test
        @DisplayName("PDF 无结构 → 父 RECURSIVE（文件类型不满足结构条件）")
        void shouldFallbackForPdfWithoutStructure() {
            Document doc = documentWithFileType(DocumentFileTypeEnum.PDF.getCode());
            DocumentParseResult result = parseResultWith(500, 100, 0, 2, 1, 3, 150);

            DocumentStrategyPlanDraft draft = strategyService.recommendStrategy(doc, result);

            assertThat(draft.getParentSteps().get(0).getStrategyType())
                    .isEqualTo(DocumentStrategyTypeEnum.RECURSIVE.getCode());
        }

        @Test
        @DisplayName("短文档 → 仅 RECURSIVE 子管道（单步）")
        void shouldHaveSingleChildStepForShortDoc() {
            Document doc = documentWithFileType(DocumentFileTypeEnum.TXT.getCode());
            DocumentParseResult result = parseResultWith(500, 100, 0, 2, 0, 2, 150);

            DocumentStrategyPlanDraft draft = strategyService.recommendStrategy(doc, result);

            assertThat(draft.getChildSteps()).hasSize(1);
            assertThat(draft.getChildSteps().get(0).getStrategyType())
                    .isEqualTo(DocumentStrategyTypeEnum.RECURSIVE.getCode());
            assertThat(draft.getStrategySnapshot()).isEqualTo("PARENT:2;CHILD:2");
        }
    }

    // ============ 语义切分推荐 ============

    @Nested
    @DisplayName("语义切分推荐")
    class SemanticRecommendation {

        @Test
        @DisplayName("高质量多段落文档 → 子 SEMANTIC + RECURSIVE")
        void shouldRecommendSemanticForHighQualityDoc() {
            Document doc = documentWithFileType(DocumentFileTypeEnum.PDF.getCode());
            DocumentParseResult result = parseResultWith(5000, 1200, 3, 4, 8, 15, 400);

            DocumentStrategyPlanDraft draft = strategyService.recommendStrategy(doc, result);

            assertThat(draft.getChildSteps().get(0).getStrategyType())
                    .isEqualTo(DocumentStrategyTypeEnum.SEMANTIC.getCode());
            assertThat(draft.getChildSteps().get(0).getStrategyRole())
                    .isEqualTo(DocumentStrategyRoleEnum.PRIMARY.getCode());
            assertThat(draft.getChildSteps().get(1).getStrategyRole())
                    .isEqualTo(DocumentStrategyRoleEnum.FALLBACK.getCode());
        }

        @Test
        @DisplayName("质量刚好到阈值 → 仍触发语义推荐")
        void shouldTriggerSemanticAtBoundary() {
            Document doc = documentWithFileType(DocumentFileTypeEnum.MD.getCode());
            DocumentParseResult result = parseResultWith(1000, 300, 2, 3, 3, 3, 300);

            DocumentStrategyPlanDraft draft = strategyService.recommendStrategy(doc, result);

            assertThat(draft.getChildSteps().get(0).getStrategyType())
                    .isEqualTo(DocumentStrategyTypeEnum.SEMANTIC.getCode());
        }

        @Test
        @DisplayName("段落数不足 → 不触发语义推荐")
        void shouldNotTriggerSemanticWithFewParagraphs() {
            Document doc = documentWithFileType(DocumentFileTypeEnum.MD.getCode());
            DocumentParseResult result = parseResultWith(2000, 500, 2, 4, 3, 2, 300);

            DocumentStrategyPlanDraft draft = strategyService.recommendStrategy(doc, result);

            assertThat(draft.getChildSteps().get(0).getStrategyType())
                    .isEqualTo(DocumentStrategyTypeEnum.RECURSIVE.getCode());
        }
    }

    // ============ LLM 切分推荐 ============

    @Nested
    @DisplayName("LLM 切分推荐（需配置开关）")
    class LlmRecommendation {

        @Test
        @DisplayName("配置关闭时低质量文档不触发 LLM")
        void shouldNotTriggerLlmWhenFeatureDisabled() {
            strategyConfig.setRecommendLlmWhenLowQuality(false);
            Document doc = documentWithFileType(DocumentFileTypeEnum.PDF.getCode());
            DocumentParseResult result = parseResultWith(2000, 400, 0, 1, 0, 8, 500);

            DocumentStrategyPlanDraft draft = strategyService.recommendStrategy(doc, result);

            boolean hasLlm = draft.getChildSteps().stream()
                    .anyMatch(s -> s.getStrategyType().equals(DocumentStrategyTypeEnum.LLM.getCode()));
            assertThat(hasLlm).isFalse();
        }

        @Test
        @DisplayName("配置开启 + 低质量 → 触发 LLM")
        void shouldTriggerLlmWhenFeatureEnabled() {
            strategyConfig.setRecommendLlmWhenLowQuality(true);
            Document doc = documentWithFileType(DocumentFileTypeEnum.PDF.getCode());
            DocumentParseResult result = parseResultWith(2000, 400, 0, 1, 0, 8, 500);

            DocumentStrategyPlanDraft draft = strategyService.recommendStrategy(doc, result);

            assertThat(draft.getChildSteps().get(0).getStrategyType())
                    .isEqualTo(DocumentStrategyTypeEnum.LLM.getCode());
            assertThat(draft.getChildSteps().get(0).getStrategyRole())
                    .isEqualTo(DocumentStrategyRoleEnum.PRIMARY.getCode());
            assertThat(draft.getChildSteps().get(1).getStrategyType())
                    .isEqualTo(DocumentStrategyTypeEnum.RECURSIVE.getCode());
        }

        @Test
        @DisplayName("配置开启但文本太短 → 不触发 LLM")
        void shouldNotTriggerLlmForShortText() {
            strategyConfig.setRecommendLlmWhenLowQuality(true);
            Document doc = documentWithFileType(DocumentFileTypeEnum.TXT.getCode());
            DocumentParseResult result = parseResultWith(500, 100, 0, 1, 0, 3, 200);

            DocumentStrategyPlanDraft draft = strategyService.recommendStrategy(doc, result);

            boolean hasLlm = draft.getChildSteps().stream()
                    .anyMatch(s -> s.getStrategyType().equals(DocumentStrategyTypeEnum.LLM.getCode()));
            assertThat(hasLlm).isFalse();
        }
    }

    // ============ 快照格式 ============

    @Nested
    @DisplayName("策略快照格式")
    class StrategySnapshot {

        @Test
        @DisplayName("标准快照 PARENT:X;CHILD:Y,Z")
        void shouldFormatSnapshotCorrectly() {
            Document doc = documentWithFileType(DocumentFileTypeEnum.PDF.getCode());
            DocumentParseResult result = parseResultWith(5000, 1200, 3, 4, 10, 20, 500);

            DocumentStrategyPlanDraft draft = strategyService.recommendStrategy(doc, result);

            assertThat(draft.getStrategySnapshot())
                    .isEqualTo("PARENT:1;CHILD:3,2");
        }

        @Test
        @DisplayName("全递归快照")
        void shouldFormatRecursiveOnlySnapshot() {
            Document doc = documentWithFileType(DocumentFileTypeEnum.TXT.getCode());
            DocumentParseResult result = parseResultWith(500, 100, 0, 2, 0, 2, 150);

            DocumentStrategyPlanDraft draft = strategyService.recommendStrategy(doc, result);

            assertThat(draft.getStrategySnapshot())
                    .isEqualTo("PARENT:2;CHILD:2");
        }
    }

    // ============ 推荐理由 ============

    @Nested
    @DisplayName("推荐理由")
    class RecommendReason {

        @Test
        @DisplayName("多策略组合时理由包含多个分号分隔项")
        void shouldContainMultipleReasonsForMultiStrategy() {
            Document doc = documentWithFileType(DocumentFileTypeEnum.PDF.getCode());
            DocumentParseResult result = parseResultWith(5000, 1200, 3, 4, 10, 20, 500);

            DocumentStrategyPlanDraft draft = strategyService.recommendStrategy(doc, result);

            assertThat(draft.getRecommendReason()).contains("；");
            assertThat(draft.getRecommendReason()).contains("结构化");
            assertThat(draft.getRecommendReason()).contains("语义");
            assertThat(draft.getRecommendReason()).contains("兜底");
        }

        @Test
        @DisplayName("纯递归方案的推荐理由包含父管道和子管道说明")
        void shouldContainRecursiveReasonForFallback() {
            Document doc = documentWithFileType(DocumentFileTypeEnum.TXT.getCode());
            DocumentParseResult result = parseResultWith(500, 100, 0, 2, 0, 2, 150);

            DocumentStrategyPlanDraft draft = strategyService.recommendStrategy(doc, result);

            assertThat(draft.getRecommendReason()).contains("递归");
            assertThat(draft.getRecommendReason()).contains("；");
        }
    }

    // ============ 来源类型 ============

    @Nested
    @DisplayName("步骤来源标识")
    class SourceType {

        @Test
        @DisplayName("所有步骤来源均为 SYSTEM_RECOMMEND")
        void shouldAllBeSystemRecommend() {
            Document doc = documentWithFileType(DocumentFileTypeEnum.PDF.getCode());
            DocumentParseResult result = parseResultWith(5000, 1200, 3, 4, 10, 20, 500);

            DocumentStrategyPlanDraft draft = strategyService.recommendStrategy(doc, result);

            assertThat(draft.getParentSteps()).allMatch(
                    s -> s.getSourceType().equals(1));
            assertThat(draft.getChildSteps()).allMatch(
                    s -> s.getSourceType().equals(1));
        }
    }

    // ============ null 安全 ============

    @Nested
    @DisplayName("空值防御")
    class NullSafety {

        @Test
        @DisplayName("fileType 为 null 时不抛异常")
        void shouldNotThrowOnNullFileType() {
            Document doc = documentWithFileType(null);
            DocumentParseResult result = parseResultWith(5000, 1200, 3, 4, 10, 20, 500);

            DocumentStrategyPlanDraft draft = strategyService.recommendStrategy(doc, result);

            assertThat(draft).isNotNull();
            assertThat(draft.getParentSteps().get(0).getStrategyType())
                    .isEqualTo(DocumentStrategyTypeEnum.RECURSIVE.getCode());
        }

        @Test
        @DisplayName("parseResult 字段为 null 时不抛异常")
        void shouldNotThrowOnNullParseResultFields() {
            Document doc = documentWithFileType(DocumentFileTypeEnum.PDF.getCode());
            DocumentParseResult result = DocumentParseResult.builder().parsedText("test").build();

            DocumentStrategyPlanDraft draft = strategyService.recommendStrategy(doc, result);

            assertThat(draft).isNotNull();
        }
    }

    // ============ 端到端展示 ============

    @Test
    @DisplayName("多种文档类型端到端展示")
    void shouldDisplayStrategyForMultipleDocumentTypes() {
        record TestCase(String label, Document doc, DocumentParseResult result) {
        }

        List<TestCase> cases = List.of(
                new TestCase("高结构化 PDF",
                        documentWithFileType(DocumentFileTypeEnum.PDF.getCode()),
                        parseResultWith(8000, 2000, 3, 5, 15, 30, 600)),
                new TestCase("无结构 TXT",
                        documentWithFileType(DocumentFileTypeEnum.TXT.getCode()),
                        parseResultWith(500, 100, 0, 2, 0, 2, 150)),
                new TestCase("短 MD",
                        documentWithFileType(DocumentFileTypeEnum.MD.getCode()),
                        parseResultWith(600, 150, 0, 3, 1, 4, 200)),
                new TestCase("超长低质量文档",
                        documentWithFileType(DocumentFileTypeEnum.DOCX.getCode()),
                        parseResultWith(10000, 3000, 0, 1, 0, 50, 2000))
        );

        System.out.println();
        System.out.println("┌─────────────────────────────────────────────────────────────────────────────┐");
        System.out.println("│                         策略推荐结果展示                                     │");
        System.out.println("├─────────────────────────────────────────────────────────────────────────────┤");

        for (TestCase tc : cases) {
            DocumentStrategyPlanDraft draft = strategyService.recommendStrategy(tc.doc, tc.result);
            System.out.printf("│ %-20s │ 快照: %-30s │ 理由: %s%n",
                    tc.label, draft.getStrategySnapshot(),
                    truncate(draft.getRecommendReason(), 30));
            System.out.println("├─────────────────────────────────────────────────────────────────────────────┤");
        }

        System.out.println("│ 所有用例通过                                                                  │");
        System.out.println("└─────────────────────────────────────────────────────────────────────────────┘");

        // 确保所有用例都返回有效结果
        assertThat(cases).allMatch(tc -> {
            DocumentStrategyPlanDraft d = strategyService.recommendStrategy(tc.doc, tc.result);
            return d != null && d.getStrategySnapshot() != null && !d.getStrategySnapshot().isEmpty();
        });
    }

    private static String truncate(String s, int maxLen) {
        if (s == null) return "null";
        return s.length() <= maxLen ? s : s.substring(0, maxLen - 3) + "...";
    }
}
