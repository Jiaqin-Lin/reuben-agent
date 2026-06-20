package com.reubenagent.document;

import com.reubenagent.document.entity.Document;
import com.reubenagent.document.entity.DocumentProfile;
import com.reubenagent.document.entity.DocumentStructureNode;
import com.reubenagent.document.enums.DocumentStructureNodeTypeEnum;
import com.reubenagent.document.mapper.IDocumentMapper;
import com.reubenagent.document.mapper.IDocumentProfileMapper;
import com.reubenagent.document.model.DocumentParseResult;
import com.reubenagent.document.service.impl.DocumentProfileServiceImpl;
import com.reubenagent.framework.uid.UidGenerator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link DocumentProfileServiceImpl} 单元测试。
 *
 * <p>Mock 所有 DB 依赖，覆盖画像生成的各字段推断逻辑、增改查、回填和边界情况。</p>
 *
 * @author reuben
 * @since 2026-06-20
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("DocumentProfileServiceImpl 单元测试")
class DocumentProfileServiceImplTest {

    @Mock
    private IDocumentMapper documentMapper;

    @Mock
    private IDocumentProfileMapper profileMapper;

    @Mock
    private UidGenerator uidGenerator;

    @InjectMocks
    private DocumentProfileServiceImpl profileService;

    private static final Long DOC_ID = 100L;
    private static final Long PROFILE_ID = 200L;

    // ---- 工厂方法 ----

    /** 创建一个基础 Document */
    private Document createDocument(String name) {
        return Document.builder()
                .id(DOC_ID)
                .documentName(name)
                .originalFileName(name + ".pdf")
                .build();
    }

    /** 创建带历史标签的 Document */
    private Document createDocumentWithTags(String name, String tags) {
        return Document.builder()
                .id(DOC_ID)
                .documentName(name)
                .originalFileName(name + ".pdf")
                .documentTags(tags)
                .build();
    }

    /** 创建 CHAPTER 类型结构节点 */
    private DocumentStructureNode createChapterNode(String title) {
        return DocumentStructureNode.builder()
                .nodeType(DocumentStructureNodeTypeEnum.CHAPTER.getCode())
                .title(title)
                .build();
    }

    private DocumentStructureNode createStepNode(String title) {
        return DocumentStructureNode.builder()
                .nodeType(DocumentStructureNodeTypeEnum.STEP.getCode())
                .title(title)
                .build();
    }

    private DocumentStructureNode createListItemNode(String title) {
        return DocumentStructureNode.builder()
                .nodeType(DocumentStructureNodeTypeEnum.LIST_ITEM.getCode())
                .title(title)
                .build();
    }

    /** 创建 DocumentParseResult */
    private DocumentParseResult createParseResult(String text) {
        return DocumentParseResult.builder()
                .parsedText(text)
                .build();
    }

    // ---- 提取 ProfileDraft 各字段的便捷方法 ----
    // 由于 buildDraft 是 private，通过捕获 insert/update 的 profile 来验证

    private DocumentProfile captureInsertedProfile() {
        ArgumentCaptor<DocumentProfile> captor = ArgumentCaptor.forClass(DocumentProfile.class);
        verify(profileMapper).insert(captor.capture());
        return captor.getValue();
    }

    private Document captureUpdatedDocument() {
        ArgumentCaptor<Document> captor = ArgumentCaptor.forClass(Document.class);
        verify(documentMapper).updateById(captor.capture());
        return captor.getValue();
    }

    // ========================================================================
    // 文档类型推断
    // ========================================================================
    @Nested
    @DisplayName("文档类型推断")
    class DocumentTypeInference {

        @BeforeEach
        void setUp() {
            when(uidGenerator.getUid()).thenReturn(PROFILE_ID);
            when(profileMapper.selectOne(any())).thenReturn(null);
        }

        @Test
        @DisplayName("文件名含 FAQ → faq")
        void shouldInferFaq() {
            Document doc = createDocument("产品FAQ手册");
            DocumentParseResult parseResult = createParseResult("常见问题解答");
            List<DocumentStructureNode> nodes = List.of(createChapterNode("常见问题"));
            when(documentMapper.selectById(DOC_ID)).thenReturn(doc);

            profileService.generateProfile(DOC_ID, parseResult, nodes);

            DocumentProfile profile = captureInsertedProfile();
            assertThat(profile.getDocumentType()).isEqualTo("faq");
        }

        @Test
        @DisplayName("文本含「故障」「排查」→ troubleshooting")
        void shouldInferTroubleshooting() {
            Document doc = createDocument("服务运维");
            DocumentParseResult parseResult = createParseResult("故障排查检查顺序如下：");
            List<DocumentStructureNode> nodes = List.of();
            when(documentMapper.selectById(DOC_ID)).thenReturn(doc);

            profileService.generateProfile(DOC_ID, parseResult, nodes);

            DocumentProfile profile = captureInsertedProfile();
            assertThat(profile.getDocumentType()).isEqualTo("troubleshooting");
        }

        @Test
        @DisplayName("文本含「规则」「制度」→ rule")
        void shouldInferRule() {
            Document doc = createDocument("公司制度");
            DocumentParseResult parseResult = createParseResult("本规则适用于全体员工");
            List<DocumentStructureNode> nodes = List.of();
            when(documentMapper.selectById(DOC_ID)).thenReturn(doc);

            profileService.generateProfile(DOC_ID, parseResult, nodes);

            DocumentProfile profile = captureInsertedProfile();
            assertThat(profile.getDocumentType()).isEqualTo("rule");
        }

        @Test
        @DisplayName("文本含「规格」「参数」→ spec")
        void shouldInferSpec() {
            Document doc = createDocument("技术规格书");
            DocumentParseResult parseResult = createParseResult("产品参数如下表所示");
            List<DocumentStructureNode> nodes = List.of();
            when(documentMapper.selectById(DOC_ID)).thenReturn(doc);

            profileService.generateProfile(DOC_ID, parseResult, nodes);

            DocumentProfile profile = captureInsertedProfile();
            assertThat(profile.getDocumentType()).isEqualTo("spec");
        }

        @Test
        @DisplayName("有步骤节点 → manual（itemLookup 优先）")
        void shouldInferManualByItemLookup() {
            Document doc = createDocument("产品简介");
            DocumentParseResult parseResult = createParseResult("通用内容说明");
            List<DocumentStructureNode> nodes = List.of(createStepNode("步骤一"));
            when(documentMapper.selectById(DOC_ID)).thenReturn(doc);

            profileService.generateProfile(DOC_ID, parseResult, nodes);

            DocumentProfile profile = captureInsertedProfile();
            assertThat(profile.getDocumentType()).isEqualTo("manual");
        }

        @Test
        @DisplayName("文本含「手册」→ manual")
        void shouldInferManualByKeyword() {
            Document doc = createDocument("操作手册");
            DocumentParseResult parseResult = createParseResult("部署指南");
            List<DocumentStructureNode> nodes = List.of();
            when(documentMapper.selectById(DOC_ID)).thenReturn(doc);

            profileService.generateProfile(DOC_ID, parseResult, nodes);

            DocumentProfile profile = captureInsertedProfile();
            assertThat(profile.getDocumentType()).isEqualTo("manual");
        }

        @Test
        @DisplayName("无匹配关键词且无步骤节点 → intro")
        void shouldInferIntroByDefault() {
            Document doc = createDocument("公司简介");
            DocumentParseResult parseResult = createParseResult("产品概述，包含核心功能说明");
            List<DocumentStructureNode> nodes = List.of();
            when(documentMapper.selectById(DOC_ID)).thenReturn(doc);

            profileService.generateProfile(DOC_ID, parseResult, nodes);

            DocumentProfile profile = captureInsertedProfile();
            assertThat(profile.getDocumentType()).isEqualTo("intro");
        }
    }

    // ========================================================================
    // 知识范围推断
    // ========================================================================
    @Nested
    @DisplayName("知识范围推断")
    class KnowledgeScopeInference {

        @BeforeEach
        void setUp() {
            when(uidGenerator.getUid()).thenReturn(PROFILE_ID);
            when(profileMapper.selectOne(any())).thenReturn(null);
        }

        @Test
        @DisplayName("含「上线观察」「运营」→ operation_rule")
        void shouldInferOperationRule() {
            Document doc = createDocument("上线值班规则");
            DocumentParseResult parseResult = createParseResult("上线观察时长不少于24小时");
            List<DocumentStructureNode> nodes = List.of();
            when(documentMapper.selectById(DOC_ID)).thenReturn(doc);

            profileService.generateProfile(DOC_ID, parseResult, nodes);

            DocumentProfile profile = captureInsertedProfile();
            Document updatedDoc = captureUpdatedDocument();
            assertThat(updatedDoc.getKnowledgeScopeCode()).isEqualTo("operation_rule");
            assertThat(updatedDoc.getKnowledgeScopeName()).isEqualTo("运营规则");
        }

        @Test
        @DisplayName("含「机器人」「知识召回」→ robot_strategy")
        void shouldInferRobotStrategy() {
            Document doc = createDocument("意图识别策略");
            DocumentParseResult parseResult = createParseResult("机器人知识召回策略设计文档");
            List<DocumentStructureNode> nodes = List.of();
            when(documentMapper.selectById(DOC_ID)).thenReturn(doc);

            profileService.generateProfile(DOC_ID, parseResult, nodes);

            DocumentProfile profile = captureInsertedProfile();
            Document updatedDoc = captureUpdatedDocument();
            assertThat(updatedDoc.getKnowledgeScopeCode()).isEqualTo("robot_strategy");
            assertThat(updatedDoc.getKnowledgeScopeName()).isEqualTo("机器人策略");
        }

        @Test
        @DisplayName("含「安装」「部署」→ deployment")
        void shouldInferDeployment() {
            Document doc = createDocument("安装指南");
            DocumentParseResult parseResult = createParseResult("默认密码和访问地址如下");
            List<DocumentStructureNode> nodes = List.of();
            when(documentMapper.selectById(DOC_ID)).thenReturn(doc);

            profileService.generateProfile(DOC_ID, parseResult, nodes);

            DocumentProfile profile = captureInsertedProfile();
            Document updatedDoc = captureUpdatedDocument();
            assertThat(updatedDoc.getKnowledgeScopeCode()).isEqualTo("deployment");
            assertThat(updatedDoc.getKnowledgeScopeName()).isEqualTo("安装部署");
        }

        @Test
        @DisplayName("含「故障」「异常」→ troubleshooting（scope）")
        void shouldInferTroubleshootingScope() {
            Document doc = createDocument("异常处理");
            DocumentParseResult parseResult = createParseResult("故障排查检查顺序手册");
            List<DocumentStructureNode> nodes = List.of();
            when(documentMapper.selectById(DOC_ID)).thenReturn(doc);

            profileService.generateProfile(DOC_ID, parseResult, nodes);

            DocumentProfile profile = captureInsertedProfile();
            Document updatedDoc = captureUpdatedDocument();
            assertThat(updatedDoc.getKnowledgeScopeCode()).isEqualTo("troubleshooting");
        }

        @Test
        @DisplayName("含「产品简介」「技术规格」→ product")
        void shouldInferProduct() {
            Document doc = createDocument("产品简介");
            DocumentParseResult parseResult = createParseResult("核心特性与技术规格说明");
            List<DocumentStructureNode> nodes = List.of();
            when(documentMapper.selectById(DOC_ID)).thenReturn(doc);

            profileService.generateProfile(DOC_ID, parseResult, nodes);

            DocumentProfile profile = captureInsertedProfile();
            Document updatedDoc = captureUpdatedDocument();
            assertThat(updatedDoc.getKnowledgeScopeCode()).isEqualTo("product");
        }

        @Test
        @DisplayName("无匹配 → general_document（默认）")
        void shouldFallbackToGeneralDocument() {
            Document doc = createDocument("杂项");
            DocumentParseResult parseResult = createParseResult("纯文本内容无任何关键词");
            List<DocumentStructureNode> nodes = List.of();
            when(documentMapper.selectById(DOC_ID)).thenReturn(doc);

            profileService.generateProfile(DOC_ID, parseResult, nodes);

            DocumentProfile profile = captureInsertedProfile();
            Document updatedDoc = captureUpdatedDocument();
            assertThat(updatedDoc.getKnowledgeScopeCode()).isEqualTo("general_document");
            assertThat(updatedDoc.getKnowledgeScopeName()).isEqualTo("通用文档");
        }
    }

    // ========================================================================
    // 业务分类推断
    // ========================================================================
    @Nested
    @DisplayName("业务分类推断")
    class BusinessCategoryInference {

        @BeforeEach
        void setUp() {
            when(uidGenerator.getUid()).thenReturn(PROFILE_ID);
            when(profileMapper.selectOne(any())).thenReturn(null);
        }

        @Test
        @DisplayName("troubleshooting → 故障排查")
        void shouldMapTroubleshooting() {
            Document doc = createDocument("故障排查手册");
            DocumentParseResult parseResult = createParseResult("故障排查流程");
            when(documentMapper.selectById(DOC_ID)).thenReturn(doc);

            profileService.generateProfile(DOC_ID, parseResult, List.of());

            Document updatedDoc = captureUpdatedDocument();
            assertThat(updatedDoc.getBusinessCategory()).isEqualTo("故障排查");
        }

        @Test
        @DisplayName("rule → 规则")
        void shouldMapRule() {
            Document doc = createDocument("公司制度");
            DocumentParseResult parseResult = createParseResult("员工行为规则");
            when(documentMapper.selectById(DOC_ID)).thenReturn(doc);

            profileService.generateProfile(DOC_ID, parseResult, List.of());

            Document updatedDoc = captureUpdatedDocument();
            assertThat(updatedDoc.getBusinessCategory()).isEqualTo("规则");
        }

        @Test
        @DisplayName("spec → 规格说明")
        void shouldMapSpec() {
            Document doc = createDocument("技术规格书");
            DocumentParseResult parseResult = createParseResult("产品参数规格一览");
            when(documentMapper.selectById(DOC_ID)).thenReturn(doc);

            profileService.generateProfile(DOC_ID, parseResult, List.of());

            Document updatedDoc = captureUpdatedDocument();
            assertThat(updatedDoc.getBusinessCategory()).isEqualTo("规格说明");
        }

        @Test
        @DisplayName("manual 含「步骤」「操作」→ 操作手册")
        void shouldMapManualWithSteps() {
            Document doc = createDocument("部署手册");
            DocumentParseResult parseResult = createParseResult("操作步骤：1. 安装 2. 部署 3. 验证");
            when(documentMapper.selectById(DOC_ID)).thenReturn(doc);

            profileService.generateProfile(DOC_ID, parseResult, List.of());

            Document updatedDoc = captureUpdatedDocument();
            assertThat(updatedDoc.getBusinessCategory()).isEqualTo("操作手册");
        }

        @Test
        @DisplayName("intro → 介绍")
        void shouldMapIntro() {
            Document doc = createDocument("公司简介");
            DocumentParseResult parseResult = createParseResult("产品概述");
            when(documentMapper.selectById(DOC_ID)).thenReturn(doc);

            profileService.generateProfile(DOC_ID, parseResult, List.of());

            Document updatedDoc = captureUpdatedDocument();
            assertThat(updatedDoc.getBusinessCategory()).isEqualTo("介绍");
        }
    }

    // ========================================================================
    // 核心主题
    // ========================================================================
    @Nested
    @DisplayName("核心主题提取")
    class CoreTopics {

        @BeforeEach
        void setUp() {
            when(uidGenerator.getUid()).thenReturn(PROFILE_ID);
            when(profileMapper.selectOne(any())).thenReturn(null);
        }

        @Test
        @DisplayName("去掉章节编号前缀（中英文）")
        void shouldStripSectionCode() {
            Document doc = createDocument("用户指南");
            List<DocumentStructureNode> nodes = List.of(
                    createChapterNode("1.1 系统概述"),
                    createChapterNode("1.2 安装步骤"),
                    createChapterNode("第二章 配置说明"));
            DocumentParseResult parseResult = createParseResult("");
            when(documentMapper.selectById(DOC_ID)).thenReturn(doc);

            profileService.generateProfile(DOC_ID, parseResult, nodes);

            DocumentProfile profile = captureInsertedProfile();
            assertThat(profile.getCoreTopics())
                    .contains("系统概述")
                    .contains("安装步骤")
                    .contains("配置说明");
        }

        @Test
        @DisplayName("包含文档名（去扩展名）+ 章节标题")
        void shouldIncludeDocName() {
            Document doc = createDocument("API手册");
            List<DocumentStructureNode> nodes = List.of(createChapterNode("接口说明"));
            DocumentParseResult parseResult = createParseResult("");
            when(documentMapper.selectById(DOC_ID)).thenReturn(doc);

            profileService.generateProfile(DOC_ID, parseResult, nodes);

            DocumentProfile profile = captureInsertedProfile();
            assertThat(profile.getCoreTopics())
                    .contains("API手册")
                    .contains("接口说明");
        }

        @Test
        @DisplayName("去重 + 上限 6 个")
        void shouldDeduplicateAndCap() {
            Document doc = createDocument("手册");
            List<DocumentStructureNode> nodes = List.of(
                    createChapterNode("安装"),
                    createChapterNode("部署"),
                    createChapterNode("安装"),       // 重复
                    createChapterNode("配置"),
                    createChapterNode("FAQ"),
                    createChapterNode("监控"),
                    createChapterNode("日志"),
                    createChapterNode("备份"));       // 第 8 个 → 被截断
            DocumentParseResult parseResult = createParseResult("");
            when(documentMapper.selectById(DOC_ID)).thenReturn(doc);

            profileService.generateProfile(DOC_ID, parseResult, nodes);

            DocumentProfile profile = captureInsertedProfile();
            // 6 个章节 + 1 个文档名 → 上限截断为 6
            List<String> topics = com.alibaba.fastjson.JSON.parseArray(
                    profile.getCoreTopics(), String.class);
            assertThat(topics).hasSizeLessThanOrEqualTo(6);
            // "安装" 只出现一次
            assertThat(topics.stream().filter("安装"::equals).count()).isEqualTo(1);
        }
    }

    // ========================================================================
    // 示例问题
    // ========================================================================
    @Nested
    @DisplayName("示例问题生成")
    class ExampleQuestions {

        @BeforeEach
        void setUp() {
            when(uidGenerator.getUid()).thenReturn(PROFILE_ID);
            when(profileMapper.selectOne(any())).thenReturn(null);
        }

        @Test
        @DisplayName("troubleshooting → 「XX的可能原因有哪些？」")
        void shouldGenerateTroubleshootingQuestions() {
            Document doc = createDocument("故障排查");
            DocumentParseResult parseResult = createParseResult("故障排查检查顺序");
            when(documentMapper.selectById(DOC_ID)).thenReturn(doc);

            profileService.generateProfile(DOC_ID, parseResult, List.of());

            DocumentProfile profile = captureInsertedProfile();
            assertThat(profile.getExampleQuestions())
                    .contains("的可能原因有哪些？");
        }

        @Test
        @DisplayName("manual → 「XX的步骤是什么？」")
        void shouldGenerateManualQuestions() {
            Document doc = createDocument("操作手册");
            DocumentParseResult parseResult = createParseResult("操作步骤说明");
            when(documentMapper.selectById(DOC_ID)).thenReturn(doc);

            profileService.generateProfile(DOC_ID, parseResult, List.of());

            DocumentProfile profile = captureInsertedProfile();
            assertThat(profile.getExampleQuestions())
                    .contains("的步骤是什么？");
        }

        @Test
        @DisplayName("rule → 「XX有哪些规则？」")
        void shouldGenerateRuleQuestions() {
            Document doc = createDocument("公司制度");
            DocumentParseResult parseResult = createParseResult("员工行为规则条例");
            when(documentMapper.selectById(DOC_ID)).thenReturn(doc);

            profileService.generateProfile(DOC_ID, parseResult, List.of());

            DocumentProfile profile = captureInsertedProfile();
            assertThat(profile.getExampleQuestions())
                    .contains("有哪些规则？");
        }

        @Test
        @DisplayName("intro → 「XX是什么意思？」（最多6条去重）")
        void shouldGenerateIntroQuestions() {
            Document doc = createDocument("产品简介");
            DocumentParseResult parseResult = createParseResult("产品概述");
            when(documentMapper.selectById(DOC_ID)).thenReturn(doc);

            profileService.generateProfile(DOC_ID, parseResult, List.of());

            DocumentProfile profile = captureInsertedProfile();
            List<String> questions = com.alibaba.fastjson.JSON.parseArray(
                    profile.getExampleQuestions(), String.class);
            assertThat(questions).allMatch(q -> q.contains("是什么意思？"));
            assertThat(questions).hasSizeLessThanOrEqualTo(6);
        }
    }

    // ========================================================================
    // 摘要格式
    // ========================================================================
    @Nested
    @DisplayName("文档摘要格式")
    class SummaryFormat {

        @BeforeEach
        void setUp() {
            when(uidGenerator.getUid()).thenReturn(PROFILE_ID);
            when(profileMapper.selectOne(any())).thenReturn(null);
        }

        @Test
        @DisplayName("包含文档名 → 「文档《XX》主要涵盖：...。摘要：...」")
        void shouldContainDocName() {
            Document doc = createDocument("用户指南");
            List<DocumentStructureNode> nodes = List.of(
                    createChapterNode("概述"), createChapterNode("入门"));
            DocumentParseResult parseResult = createParseResult("这是正文内容");
            when(documentMapper.selectById(DOC_ID)).thenReturn(doc);

            profileService.generateProfile(DOC_ID, parseResult, nodes);

            DocumentProfile profile = captureInsertedProfile();
            assertThat(profile.getDocumentSummary())
                    .contains("文档《用户指南》")
                    .contains("概述")
                    .contains("入门")
                    .contains("摘要：")
                    .contains("这是正文内容");
        }

        @Test
        @DisplayName("章节标题最多4条")
        void shouldLimitSectionTitlesInSummary() {
            Document doc = createDocument("手册");
            List<DocumentStructureNode> nodes = List.of(
                    createChapterNode("一"), createChapterNode("二"),
                    createChapterNode("三"), createChapterNode("四"),
                    createChapterNode("五"));  // 第5个不出现
            DocumentParseResult parseResult = createParseResult("");
            when(documentMapper.selectById(DOC_ID)).thenReturn(doc);

            profileService.generateProfile(DOC_ID, parseResult, nodes);

            DocumentProfile profile = captureInsertedProfile();
            String summary = profile.getDocumentSummary();
            // 前4个 → 出现；第5个 → 不出现
            assertThat(summary).contains("一", "二", "三", "四");
            assertThat(summary).doesNotContain("五");
        }

        @Test
        @DisplayName("正文过长截断至180字")
        void shouldTruncateExcerptAt180() {
            Document doc = createDocument("手册");
            String longText = "A".repeat(300);
            DocumentParseResult parseResult = createParseResult(longText);
            when(documentMapper.selectById(DOC_ID)).thenReturn(doc);

            profileService.generateProfile(DOC_ID, parseResult, List.of());

            DocumentProfile profile = captureInsertedProfile();
            // 截断后 "文档《手册》摘要：" + ≤180 字符
            String summary = profile.getDocumentSummary();
            assertThat(summary.length()).isLessThanOrEqualTo(
                    "文档《手册》摘要：".length() + 180);
        }
    }

    // ========================================================================
    // 文档标签
    // ========================================================================
    @Nested
    @DisplayName("文档标签组装")
    class DocumentTags {

        @BeforeEach
        void setUp() {
            when(uidGenerator.getUid()).thenReturn(PROFILE_ID);
            when(profileMapper.selectOne(any())).thenReturn(null);
        }

        @Test
        @DisplayName("Document 无已有标签 → 回填 scopeCode + documentType + topics")
        void shouldBackfillTagsWhenBlank() {
            Document doc = createDocument("操作手册");
            // tags 为 null → 触发回填
            DocumentParseResult parseResult = createParseResult("部署安装步骤");
            List<DocumentStructureNode> nodes = List.of(createChapterNode("安装部署"));
            when(documentMapper.selectById(DOC_ID)).thenReturn(doc);

            profileService.generateProfile(DOC_ID, parseResult, nodes);

            Document updatedDoc = captureUpdatedDocument();
            assertThat(updatedDoc.getDocumentTags())
                    .contains("deployment")
                    .contains("manual")
                    .contains("安装部署");
        }

        @Test
        @DisplayName("去重 + 上限 8 个")
        void shouldDeduplicateAndCapTags() {
            Document doc = createDocument("手册");
            DocumentParseResult parseResult = createParseResult("");
            List<DocumentStructureNode> nodes = List.of(
                    createChapterNode("A"), createChapterNode("B"),
                    createChapterNode("C"), createChapterNode("D"),
                    createChapterNode("E"), createChapterNode("F"));
            when(documentMapper.selectById(DOC_ID)).thenReturn(doc);

            profileService.generateProfile(DOC_ID, parseResult, nodes);

            Document updatedDoc = captureUpdatedDocument();
            String[] tags = updatedDoc.getDocumentTags().split(",");
            assertThat(tags).hasSizeLessThanOrEqualTo(8);
        }

        @Test
        @DisplayName("已有标签非空 → 保留原值不动（保护人工编辑）")
        void shouldPreserveExistingTags() {
            Document doc = Document.builder()
                    .id(DOC_ID)
                    .documentName("手册")
                    .originalFileName("手册.pdf")
                    .documentTags("人工标签, 已审核")  // 已有值
                    .build();
            DocumentParseResult parseResult = createParseResult("");
            when(documentMapper.selectById(DOC_ID)).thenReturn(doc);

            profileService.generateProfile(DOC_ID, parseResult, List.of());

            Document updatedDoc = captureUpdatedDocument();
            // 标签保持不变（backfill 跳过了 documentTags）
            assertThat(updatedDoc.getDocumentTags()).isEqualTo("人工标签, 已审核");
        }
    }

    // ========================================================================
    // 图结构适配
    // ========================================================================
    @Nested
    @DisplayName("图结构适配推断")
    class GraphFriendlyInference {

        @BeforeEach
        void setUp() {
            when(uidGenerator.getUid()).thenReturn(PROFILE_ID);
            when(profileMapper.selectOne(any())).thenReturn(null);
        }

        @Test
        @DisplayName("≥2 章节点 → graphFriendly + supportsGraphOutline")
        void shouldEnableGraphBySections() {
            Document doc = createDocument("手册");
            List<DocumentStructureNode> nodes = List.of(
                    createChapterNode("概述"), createChapterNode("入门"));
            DocumentParseResult parseResult = createParseResult("");
            when(documentMapper.selectById(DOC_ID)).thenReturn(doc);

            profileService.generateProfile(DOC_ID, parseResult, nodes);

            DocumentProfile profile = captureInsertedProfile();
            assertThat(profile.getGraphFriendly()).isEqualTo(1);
            assertThat(profile.getSupportsGraphOutline()).isEqualTo(1);
        }

        @Test
        @DisplayName("仅 1 章但含步骤 → graphFriendly + supportsItemLookup")
        void shouldEnableGraphBySteps() {
            Document doc = createDocument("手册");
            List<DocumentStructureNode> nodes = List.of(
                    createChapterNode("概述"), createStepNode("步骤一"));
            DocumentParseResult parseResult = createParseResult("");
            when(documentMapper.selectById(DOC_ID)).thenReturn(doc);

            profileService.generateProfile(DOC_ID, parseResult, nodes);

            DocumentProfile profile = captureInsertedProfile();
            assertThat(profile.getGraphFriendly()).isEqualTo(1);
            assertThat(profile.getSupportsItemLookup()).isEqualTo(1);
        }

        @Test
        @DisplayName("无章节无步骤 → graphFriendly=0")
        void shouldDisableGraphByDefault() {
            Document doc = createDocument("手册");
            List<DocumentStructureNode> nodes = List.of();
            DocumentParseResult parseResult = createParseResult("");
            when(documentMapper.selectById(DOC_ID)).thenReturn(doc);

            profileService.generateProfile(DOC_ID, parseResult, nodes);

            DocumentProfile profile = captureInsertedProfile();
            assertThat(profile.getGraphFriendly()).isEqualTo(0);
            assertThat(profile.getSupportsGraphOutline()).isEqualTo(0);
            assertThat(profile.getSupportsItemLookup()).isEqualTo(0);
        }
    }

    // ========================================================================
    // Profile 增改查
    // ========================================================================
    @Nested
    @DisplayName("Profile 增改查")
    class ProfileUpsert {

        @BeforeEach
        void setUp() {
            when(uidGenerator.getUid()).thenReturn(PROFILE_ID);
        }

        @Test
        @DisplayName("无已有画像 → 新建，version=1")
        void shouldCreateNewProfile() {
            Document doc = createDocument("手册");
            when(documentMapper.selectById(DOC_ID)).thenReturn(doc);
            when(profileMapper.selectOne(any())).thenReturn(null);

            profileService.generateProfile(DOC_ID, createParseResult(""), List.of());

            DocumentProfile profile = captureInsertedProfile();
            assertThat(profile.getId()).isEqualTo(PROFILE_ID);
            assertThat(profile.getDocumentId()).isEqualTo(DOC_ID);
            assertThat(profile.getProfileVersion()).isEqualTo(1);
            assertThat(profile.getProfileSource()).isEqualTo("auto");
            assertThat(profile.getProfileStatus()).isEqualTo(2);  // SUCCESS
            assertThat(profile.getErrorMsg()).isNull();
        }

        @Test
        @DisplayName("有已有画像 → 更新，version + 1")
        void shouldUpdateExistingProfile() {
            Document doc = createDocument("手册");
            DocumentProfile existing = DocumentProfile.builder()
                    .id(PROFILE_ID).documentId(DOC_ID).profileVersion(3).build();
            when(documentMapper.selectById(DOC_ID)).thenReturn(doc);
            when(profileMapper.selectOne(any())).thenReturn(existing);

            profileService.generateProfile(DOC_ID, createParseResult(""), List.of());

            ArgumentCaptor<DocumentProfile> captor = ArgumentCaptor.forClass(DocumentProfile.class);
            verify(profileMapper).updateById(captor.capture());
            assertThat(captor.getValue().getProfileVersion()).isEqualTo(4);
        }

        @Test
        @DisplayName("getByDocumentId → 找到返回 Optional.of，未找到返回 Optional.empty()")
        void shouldQueryByDocumentId() {
            DocumentProfile existing = DocumentProfile.builder()
                    .id(PROFILE_ID).documentId(DOC_ID).build();
            when(profileMapper.selectOne(any())).thenReturn(existing);

            assertThat(profileService.getByDocumentId(DOC_ID)).isPresent();
            assertThat(profileService.getByDocumentId(999L)).isPresent(); // mock 返回同一个

            when(profileMapper.selectOne(any())).thenReturn(null);
            assertThat(profileService.getByDocumentId(DOC_ID)).isEmpty();
        }

        @Test
        @DisplayName("getByDocumentId(null) → Optional.empty()")
        void shouldReturnEmptyForNullId() {
            assertThat(profileService.getByDocumentId(null)).isEmpty();
        }
    }

    // ========================================================================
    // 回填逻辑
    // ========================================================================
    @Nested
    @DisplayName("回填 Document 元数据")
    class BackfillMetadata {

        @BeforeEach
        void setUp() {
            when(uidGenerator.getUid()).thenReturn(PROFILE_ID);
            when(profileMapper.selectOne(any())).thenReturn(null);
        }

        @Test
        @DisplayName("Document 字段为空 → 回填")
        void shouldBackfillWhenBlank() {
            Document doc = createDocument("故障排查手册");
            // 所有元数据字段均为 null
            DocumentParseResult parseResult = createParseResult("故障排查流程");
            when(documentMapper.selectById(DOC_ID)).thenReturn(doc);

            profileService.generateProfile(DOC_ID, parseResult, List.of());

            Document updatedDoc = captureUpdatedDocument();
            assertThat(updatedDoc.getKnowledgeScopeCode()).isEqualTo("troubleshooting");
            assertThat(updatedDoc.getKnowledgeScopeName()).isEqualTo("故障排查");
            assertThat(updatedDoc.getBusinessCategory()).isEqualTo("故障排查");
            assertThat(updatedDoc.getDocumentTags()).isNotBlank();
        }

        @Test
        @DisplayName("Document 字段已有值 → 不回填（保护人工编辑结果）")
        void shouldNotOverwriteExistingValues() {
            Document doc = Document.builder()
                    .id(DOC_ID)
                    .documentName("手册")
                    .originalFileName("手册.pdf")
                    .knowledgeScopeCode("custom_scope")
                    .knowledgeScopeName("自定义范围")
                    .businessCategory("自定义分类")
                    .documentTags("自定义标签")
                    .build();
            DocumentParseResult parseResult = createParseResult("安装部署说明");
            when(documentMapper.selectById(DOC_ID)).thenReturn(doc);

            profileService.generateProfile(DOC_ID, parseResult, List.of());

            // 已有值 → updateById 不应被调用（因为 changed = false）
            verify(documentMapper, never()).updateById(any(Document.class));
        }

        @Test
        @DisplayName("仅空白字段回填，非空白字段保留")
        void shouldBackfillOnlyBlankFields() {
            Document doc = Document.builder()
                    .id(DOC_ID)
                    .documentName("手册")
                    .originalFileName("手册.pdf")
                    .knowledgeScopeCode(null)            // 空白 → 回填
                    .knowledgeScopeName("已有范围名")     // 有值 → 跳过
                    .businessCategory(null)              // 空白 → 回填
                    .documentTags("已有标签")             // 有值 → 跳过
                    .build();
            DocumentParseResult parseResult = createParseResult("安装部署说明");
            when(documentMapper.selectById(DOC_ID)).thenReturn(doc);

            profileService.generateProfile(DOC_ID, parseResult, List.of());

            Document updatedDoc = captureUpdatedDocument();
            assertThat(updatedDoc.getKnowledgeScopeCode()).isEqualTo("deployment");  // 回填
            assertThat(updatedDoc.getKnowledgeScopeName()).isEqualTo("已有范围名");    // 保留
            assertThat(updatedDoc.getBusinessCategory()).isNotBlank();               // 回填
            assertThat(updatedDoc.getDocumentTags()).isEqualTo("已有标签");           // 保留
        }
    }

    // ========================================================================
    // 边界情况
    // ========================================================================
    @Nested
    @DisplayName("边界情况")
    class EdgeCases {

        @BeforeEach
        void setUp() {
            when(uidGenerator.getUid()).thenReturn(PROFILE_ID);
            when(profileMapper.selectOne(any())).thenReturn(null);
        }

        @Test
        @DisplayName("文档不存在 → IllegalArgumentException")
        void shouldThrowWhenDocumentNotFound() {
            when(documentMapper.selectById(DOC_ID)).thenReturn(null);

            assertThatThrownBy(() ->
                    profileService.generateProfile(DOC_ID, createParseResult(""), List.of()))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("文档不存在")
                    .hasMessageContaining(DOC_ID.toString());
        }

        @Test
        @DisplayName("parseResult 为 null → 视为空字符串")
        void shouldHandleNullParseResult() {
            Document doc = createDocument("杂项文档");  // 不含任何关键词
            when(documentMapper.selectById(DOC_ID)).thenReturn(doc);

            DocumentProfile profile = profileService.generateProfile(DOC_ID, null, List.of());

            assertThat(profile).isNotNull();
            assertThat(profile.getDocumentType()).isEqualTo("intro");
        }

        @Test
        @DisplayName("structureNodes 为 null → 视为空列表，仅文档名作为 topic")
        void shouldHandleNullStructureNodes() {
            Document doc = createDocument("手册");
            DocumentParseResult parseResult = createParseResult("故障排查");
            when(documentMapper.selectById(DOC_ID)).thenReturn(doc);

            DocumentProfile profile = profileService.generateProfile(DOC_ID, parseResult, null);

            assertThat(profile).isNotNull();
            // 无章节标题 → coreTopics 仅含文档名（去扩展名）
            assertThat(profile.getCoreTopics()).isEqualTo("[\"手册\"]");
            assertThat(profile.getGraphFriendly()).isEqualTo(0);
        }

        @Test
        @DisplayName("structureNodes 含 null 元素 → 安全跳过")
        void shouldSkipNullNodes() {
            Document doc = createDocument("手册");
            List<DocumentStructureNode> nodes = new ArrayList<>();
            nodes.add(null);
            nodes.add(createChapterNode("概述"));
            nodes.add(null);
            DocumentParseResult parseResult = createParseResult("");
            when(documentMapper.selectById(DOC_ID)).thenReturn(doc);

            profileService.generateProfile(DOC_ID, parseResult, nodes);

            DocumentProfile profile = captureInsertedProfile();
            assertThat(profile.getCoreTopics()).contains("概述");
        }

        @Test
        @DisplayName("空文档名 → 「未命名文档」兜底")
        void shouldFallbackForEmptyDocName() {
            Document doc = Document.builder()
                    .id(DOC_ID)
                    .documentName(null)
                    .originalFileName(null)
                    .build();
            DocumentParseResult parseResult = createParseResult("");
            when(documentMapper.selectById(DOC_ID)).thenReturn(doc);

            profileService.generateProfile(DOC_ID, parseResult, List.of());

            DocumentProfile profile = captureInsertedProfile();
            assertThat(profile.getDocumentSummary()).contains("未命名文档");
        }

        @Test
        @DisplayName("含非 CHAPTER 节点类型 → 不影响类型推断和章节提取")
        void shouldIgnoreNonChapterNodesForTitles() {
            Document doc = createDocument("混合文档");
            List<DocumentStructureNode> nodes = List.of(
                    createChapterNode("仅此一章"),  // 只有1个CHAPTER → supportsGraphOutline=false
                    createStepNode("步骤一"),
                    createListItemNode("列表项"));
            DocumentParseResult parseResult = createParseResult("文本");
            when(documentMapper.selectById(DOC_ID)).thenReturn(doc);

            profileService.generateProfile(DOC_ID, parseResult, nodes);

            DocumentProfile profile = captureInsertedProfile();
            // 仅 1 个 CHAPTER → graphOutline = false，但 itemLookup = true（有 STEP）
            assertThat(profile.getSupportsGraphOutline()).isEqualTo(0);
            assertThat(profile.getSupportsItemLookup()).isEqualTo(1);
            assertThat(profile.getGraphFriendly()).isEqualTo(1);
        }
    }

    // ========================================================================
    // 端到端（完整画像）
    // ========================================================================
    @Nested
    @DisplayName("端到端")
    class EndToEnd {

        @BeforeEach
        void setUp() {
            when(uidGenerator.getUid()).thenReturn(PROFILE_ID);
            when(profileMapper.selectOne(any())).thenReturn(null);
        }

        @Test
        @DisplayName("完整故障排查文档 → 所有字段正确生成")
        void shouldGenerateFullProfileForTroubleshootingDoc() {
            Document doc = createDocument("MySQL故障排查手册");
            String text = """
                    本手册用于指导MySQL数据库常见故障的排查与解决。
                    包含连接失败、慢查询、主从复制延迟等问题的检查顺序和处理步骤。
                    上线观察时长建议不少于12小时，运营期间请遵循值班规则。
                    常见问题包括数据库无法连接、查询超时等。""";
            DocumentParseResult parseResult = createParseResult(text);
            List<DocumentStructureNode> nodes = List.of(
                    createChapterNode("1.1 连接故障排查"),
                    createChapterNode("1.2 慢查询定位"),
                    createChapterNode("1.3 主从复制修复"),
                    createChapterNode("2.1 参数调优"),
                    createStepNode("检查MySQL进程状态"),
                    createStepNode("查看错误日志"));

            when(documentMapper.selectById(DOC_ID)).thenReturn(doc);

            profileService.generateProfile(DOC_ID, parseResult, nodes);

            DocumentProfile profile = captureInsertedProfile();
            Document updatedDoc = captureUpdatedDocument();

            // ===== 输出到控制台，方便人工审阅 =====
            System.out.println();
            System.out.println("╔══════════════════════════════════════════════════╗");
            System.out.println("║          文档画像生成结果（端到端验证）          ║");
            System.out.println("╠══════════════════════════════════════════════════╣");
            System.out.printf ("║  documentType      : %-27s ║%n", profile.getDocumentType());
            System.out.printf ("║  graphFriendly     : %-27d ║%n", profile.getGraphFriendly());
            System.out.printf ("║  supportsOutline   : %-27d ║%n", profile.getSupportsGraphOutline());
            System.out.printf ("║  supportsItemLookup: %-27d ║%n", profile.getSupportsItemLookup());
            System.out.printf ("║  profileVersion    : %-27d ║%n", profile.getProfileVersion());
            System.out.println("╠══════════════════════════════════════════════════╣");
            System.out.printf ("║  knowledgeScopeCode: %-27s ║%n", updatedDoc.getKnowledgeScopeCode());
            System.out.printf ("║  knowledgeScopeName: %-27s ║%n", updatedDoc.getKnowledgeScopeName());
            System.out.printf ("║  businessCategory  : %-27s ║%n", updatedDoc.getBusinessCategory());
            System.out.println("╠══════════════════════════════════════════════════╣");
            System.out.printf ("║  coreTopics        : %-27s ║%n", profile.getCoreTopics());
            System.out.printf ("║  exampleQuestions  : %-27s ║%n", profile.getExampleQuestions());
            System.out.printf ("║  documentTags      : %-27s ║%n", updatedDoc.getDocumentTags());
            System.out.println("╠══════════════════════════════════════════════════╣");
            System.out.println("║  documentSummary:");
            String summary = profile.getDocumentSummary();
            for (int i = 0; i < summary.length(); i += 50) {
                int end = Math.min(i + 50, summary.length());
                System.out.printf("║    %-47s ║%n", summary.substring(i, end));
            }
            System.out.println("╚══════════════════════════════════════════════════╝");
            System.out.println();

            // ===== 断言 =====
            assertThat(profile.getDocumentType()).isEqualTo("faq");  // "常见问题" 优先于 "故障"
            assertThat(profile.getGraphFriendly()).isEqualTo(1);
            assertThat(profile.getSupportsGraphOutline()).isEqualTo(1);
            assertThat(profile.getSupportsItemLookup()).isEqualTo(1);
            assertThat(profile.getProfileVersion()).isEqualTo(1);
            assertThat(profile.getProfileSource()).isEqualTo("auto");
            assertThat(profile.getProfileStatus()).isEqualTo(2);  // SUCCESS
            assertThat(profile.getErrorMsg()).isNull();

            // scope = operation_rule ("上线观察" + "值班规则" + "运营" 命中)
            assertThat(updatedDoc.getKnowledgeScopeCode()).isEqualTo("operation_rule");
            assertThat(updatedDoc.getKnowledgeScopeName()).isEqualTo("运营规则");
            // "faq" 类型无专属业务分类 → 默认 "介绍"
            assertThat(updatedDoc.getBusinessCategory()).isEqualTo("介绍");
            assertThat(updatedDoc.getDocumentTags())
                    .contains("operation_rule")
                    .contains("faq");

            // 主题包含章节标题（去编号）和文档名（去扩展名）
            assertThat(profile.getCoreTopics())
                    .contains("MySQL故障排查手册")
                    .contains("连接故障排查")
                    .contains("慢查询定位");

            // 示例问题模板正确（faq → 默认模板「XX是什么意思？」）
            assertThat(profile.getExampleQuestions())
                    .contains("是什么意思？");

            // 摘要包含文档名
            assertThat(profile.getDocumentSummary()).contains("故障排查手册");
        }
    }
}
