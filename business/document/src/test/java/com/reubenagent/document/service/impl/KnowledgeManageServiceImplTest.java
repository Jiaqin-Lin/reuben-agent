package com.reubenagent.document.service.impl;

import com.reubenagent.common.exception.DocumentException;
import com.reubenagent.document.DocumentTestConfig;
import com.reubenagent.document.DocumentTestSchema;
import com.reubenagent.document.dto.KnowledgeScopeDeleteDto;
import com.reubenagent.document.dto.KnowledgeScopeSaveDto;
import com.reubenagent.document.dto.KnowledgeTopicDeleteDto;
import com.reubenagent.document.dto.KnowledgeTopicSaveDto;
import com.reubenagent.document.dto.TopicDocumentRelationRemoveDto;
import com.reubenagent.document.dto.TopicDocumentRelationSaveDto;
import com.reubenagent.document.entity.Document;
import com.reubenagent.document.entity.KnowledgeScopeNode;
import com.reubenagent.document.entity.KnowledgeTopicNode;
import com.reubenagent.document.entity.TopicDocumentRelation;
import com.reubenagent.document.mapper.IDocumentMapper;
import com.reubenagent.document.mapper.IKnowledgeScopeNodeMapper;
import com.reubenagent.document.mapper.IKnowledgeTopicNodeMapper;
import com.reubenagent.document.mapper.ITopicDocumentRelationMapper;
import com.reubenagent.document.service.IKnowledgeManageService;
import com.reubenagent.document.service.KnowledgeRouteIndexService;
import com.reubenagent.document.vo.KnowledgeScopeItemVo;
import com.reubenagent.document.vo.KnowledgeTopicItemVo;
import com.reubenagent.document.vo.TopicDocumentRelationItemVo;
import com.reubenagent.framework.uid.UidGenerator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * KnowledgeManageServiceImpl 集成测试 —— 真实 DB 写入 + Mock 外部依赖。
 *
 * @author reuben
 * @since 2026-06-28
 */
@SpringBootTest(classes = DocumentTestConfig.TestApp.class)
@ActiveProfiles("test")
@DisplayName("KnowledgeManageServiceImpl")
class KnowledgeManageServiceImplTest {

    @Autowired private IKnowledgeManageService knowledgeManageService;
    @Autowired private IKnowledgeScopeNodeMapper scopeNodeMapper;
    @Autowired private IKnowledgeTopicNodeMapper topicNodeMapper;
    @Autowired private ITopicDocumentRelationMapper relationMapper;
    @Autowired private IDocumentMapper documentMapper;
    @Autowired private JdbcTemplate jdbcTemplate;

    @MockBean private UidGenerator uidGenerator;
    @MockBean private KnowledgeRouteIndexService routeIndexService;

    private final AtomicLong idSeq = new AtomicLong(90000);

    @BeforeEach
    void setUp() {
        DocumentTestSchema.dropTables(jdbcTemplate);
        DocumentTestSchema.createAllTables(jdbcTemplate);
        org.mockito.Mockito.when(uidGenerator.getUid()).thenAnswer(inv -> idSeq.incrementAndGet());
    }

    // ==================== Scope CRUD ====================

    @Nested
    @DisplayName("Scope CRUD")
    class ScopeCrud {

        @Test
        @DisplayName("创建新 scope")
        void shouldCreateScope() {
            KnowledgeScopeItemVo result = knowledgeManageService.saveScope(
                    KnowledgeScopeSaveDto.builder()
                            .scopeCode("product").scopeName("产品文档")
                            .description("产品相关文档").build());

            assertThat(result.getScopeCode()).isEqualTo("product");
            assertThat(result.getScopeName()).isEqualTo("产品文档");
            assertThat(result.getId()).isPositive();

            // 验证落库
            KnowledgeScopeNode db = scopeNodeMapper.selectById(result.getId());
            assertThat(db).isNotNull();
            assertThat(db.getScopeCode()).isEqualTo("product");
        }

        @Test
        @DisplayName("更新已有 scope")
        void shouldUpdateScope() {
            knowledgeManageService.saveScope(
                    KnowledgeScopeSaveDto.builder().scopeCode("product").scopeName("产品文档").build());

            KnowledgeScopeItemVo result = knowledgeManageService.saveScope(
                    KnowledgeScopeSaveDto.builder().scopeCode("product").scopeName("产品文档V2").build());

            assertThat(result.getScopeName()).isEqualTo("产品文档V2");

            // 不应产生重复行
            long count = scopeNodeMapper.selectCount(
                    new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<KnowledgeScopeNode>()
                            .eq(KnowledgeScopeNode::getScopeCode, "product")
                            .eq(KnowledgeScopeNode::getIsDeleted, 0));
            assertThat(count).isOne();
        }

        @Test
        @DisplayName("删除 scope（软删除）")
        void shouldSoftDeleteScope() {
            knowledgeManageService.saveScope(
                    KnowledgeScopeSaveDto.builder().scopeCode("product").scopeName("产品文档").build());

            knowledgeManageService.deleteScope(
                    KnowledgeScopeDeleteDto.builder().scopeCode("product").build());

            List<KnowledgeScopeItemVo> list = knowledgeManageService.listScopes();
            assertThat(list).isEmpty();
        }

        @Test
        @DisplayName("删除不存在的 scope 抛异常")
        void shouldThrowWhenDeletingNonExistent() {
            assertThatThrownBy(() -> knowledgeManageService.deleteScope(
                    KnowledgeScopeDeleteDto.builder().scopeCode("nonexistent").build()))
                    .isInstanceOf(DocumentException.class)
                    .matches(e -> ((DocumentException) e).getCode() == 50001); // SCOPE_NOT_FOUND
        }

        @Test
        @DisplayName("listScopes 按 sortOrder 排序")
        void shouldListScopesInOrder() {
            knowledgeManageService.saveScope(
                    KnowledgeScopeSaveDto.builder().scopeCode("z").scopeName("Z").sortOrder(3).build());
            knowledgeManageService.saveScope(
                    KnowledgeScopeSaveDto.builder().scopeCode("a").scopeName("A").sortOrder(1).build());
            knowledgeManageService.saveScope(
                    KnowledgeScopeSaveDto.builder().scopeCode("m").scopeName("M").sortOrder(2).build());

            List<KnowledgeScopeItemVo> list = knowledgeManageService.listScopes();
            assertThat(list).hasSize(3);
            assertThat(list.get(0).getScopeCode()).isEqualTo("a");
            assertThat(list.get(1).getScopeCode()).isEqualTo("m");
            assertThat(list.get(2).getScopeCode()).isEqualTo("z");
        }
    }

    // ==================== Topic CRUD ====================

    @Nested
    @DisplayName("Topic CRUD")
    class TopicCrud {

        @BeforeEach
        void seedScope() {
            knowledgeManageService.saveScope(
                    KnowledgeScopeSaveDto.builder().scopeCode("product").scopeName("产品文档").build());
        }

        @Test
        @DisplayName("创建 topic（需 scope 存在）")
        void shouldCreateTopic() {
            KnowledgeTopicItemVo result = knowledgeManageService.saveTopic(
                    KnowledgeTopicSaveDto.builder()
                            .topicCode("faq").topicName("常见问题").scopeCode("product").build());

            assertThat(result.getTopicCode()).isEqualTo("faq");
            assertThat(result.getScopeCode()).isEqualTo("product");
        }

        @Test
        @DisplayName("scope 不存在时创建 topic 抛异常")
        void shouldThrowWhenScopeNotFound() {
            assertThatThrownBy(() -> knowledgeManageService.saveTopic(
                    KnowledgeTopicSaveDto.builder()
                            .topicCode("faq").topicName("常见问题").scopeCode("nonexistent").build()))
                    .isInstanceOf(DocumentException.class);
        }

        @Test
        @DisplayName("按 scopeCode 筛选 topic")
        void shouldFilterTopicsByScope() {
            knowledgeManageService.saveScope(
                    KnowledgeScopeSaveDto.builder().scopeCode("api").scopeName("API").build());
            knowledgeManageService.saveTopic(
                    KnowledgeTopicSaveDto.builder().topicCode("faq").topicName("FAQ").scopeCode("product").build());
            knowledgeManageService.saveTopic(
                    KnowledgeTopicSaveDto.builder().topicCode("ref").topicName("参考").scopeCode("api").build());

            List<KnowledgeTopicItemVo> productTopics = knowledgeManageService.listTopics("product");
            assertThat(productTopics).hasSize(1);
            assertThat(productTopics.get(0).getTopicCode()).isEqualTo("faq");

            List<KnowledgeTopicItemVo> allTopics = knowledgeManageService.listTopics(null);
            assertThat(allTopics).hasSize(2);
        }

        @Test
        @DisplayName("软删除 topic")
        void shouldSoftDeleteTopic() {
            knowledgeManageService.saveTopic(
                    KnowledgeTopicSaveDto.builder().topicCode("faq").topicName("FAQ").scopeCode("product").build());

            knowledgeManageService.deleteTopic(
                    KnowledgeTopicDeleteDto.builder().topicCode("faq").build());

            List<KnowledgeTopicItemVo> list = knowledgeManageService.listTopics(null);
            assertThat(list).isEmpty();
        }
    }

    // ==================== Relation CRUD ====================

    @Nested
    @DisplayName("Relation CRUD")
    class RelationCrud {

        private Long docId;

        @BeforeEach
        void seed() {
            knowledgeManageService.saveScope(
                    KnowledgeScopeSaveDto.builder().scopeCode("product").scopeName("产品").build());
            knowledgeManageService.saveTopic(
                    KnowledgeTopicSaveDto.builder().topicCode("faq").topicName("FAQ").scopeCode("product").build());

            docId = 90100L;
            Document doc = Document.builder().id(docId).documentName("产品FAQ手册").knowledgeScopeName("产品").build();
            documentMapper.insert(doc);
        }

        @Test
        @DisplayName("创建 topic-document 关联")
        void shouldCreateRelation() {
            TopicDocumentRelationItemVo result = knowledgeManageService.saveRelation(
                    TopicDocumentRelationSaveDto.builder()
                            .topicCode("faq").documentId(docId).relationScore(new BigDecimal("0.90")).build());

            assertThat(result.getTopicCode()).isEqualTo("faq");
            assertThat(result.getDocumentId()).isEqualTo(docId);
            assertThat(result.getRelationScore()).isEqualByComparingTo("0.90");
        }

        @Test
        @DisplayName("删除关联")
        void shouldRemoveRelation() {
            knowledgeManageService.saveRelation(
                    TopicDocumentRelationSaveDto.builder().topicCode("faq").documentId(docId).build());

            knowledgeManageService.removeRelation(
                    TopicDocumentRelationRemoveDto.builder().topicCode("faq").documentId(docId).build());

            List<TopicDocumentRelationItemVo> list = knowledgeManageService.listRelations("faq");
            assertThat(list).isEmpty();
        }

        @Test
        @DisplayName("listRelations 返回带 documentName 的结果")
        void shouldIncludeDocumentName() {
            knowledgeManageService.saveRelation(
                    TopicDocumentRelationSaveDto.builder().topicCode("faq").documentId(docId).build());

            List<TopicDocumentRelationItemVo> list = knowledgeManageService.listRelations("faq");
            assertThat(list).hasSize(1);
            assertThat(list.get(0).getDocumentName()).isEqualTo("产品FAQ手册");
        }
    }
}
