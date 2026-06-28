package com.reubenagent.document.service.impl;

import com.reubenagent.document.config.DocumentProperties;
import com.reubenagent.document.entity.Document;
import com.reubenagent.document.entity.KnowledgeScopeNode;
import com.reubenagent.document.entity.KnowledgeTopicNode;
import com.reubenagent.document.entity.TopicDocumentRelation;
import com.reubenagent.document.enums.KnowledgeRouteStatus;
import com.reubenagent.document.mapper.IDocumentMapper;
import com.reubenagent.document.mapper.IDocumentProfileMapper;
import com.reubenagent.document.mapper.IKnowledgeScopeNodeMapper;
import com.reubenagent.document.mapper.IKnowledgeTopicNodeMapper;
import com.reubenagent.document.mapper.ITopicDocumentRelationMapper;
import com.reubenagent.document.model.route.DocumentRouteCandidate;
import com.reubenagent.document.model.route.KnowledgeRouteDecision;
import com.reubenagent.document.model.route.TopicRouteCandidate;
import com.reubenagent.document.service.KnowledgeRouteIndexService;
import com.reubenagent.framework.uid.UidGenerator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.ObjectProvider;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * KnowledgeRouteServiceImpl 纯单元测试 —— Mock 所有依赖，验证评分与 Fallback 逻辑。
 *
 * @author reuben
 * @since 2026-06-28
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("KnowledgeRouteServiceImpl")
class KnowledgeRouteServiceImplTest {

    @Mock private IKnowledgeScopeNodeMapper scopeNodeMapper;
    @Mock private IKnowledgeTopicNodeMapper topicNodeMapper;
    @Mock private ITopicDocumentRelationMapper relationMapper;
    @Mock private IDocumentMapper documentMapper;
    @Mock private IDocumentProfileMapper documentProfileMapper;
    @Mock private ObjectProvider<EmbeddingModel> embeddingModelProvider;
    @Mock private ObjectProvider<KnowledgeRouteIndexService> routeIndexServiceProvider;
    @Mock private KnowledgeRouteTraceService traceService;
    @Mock private UidGenerator uidGenerator;

    private KnowledgeRouteServiceImpl routeService;
    private DocumentProperties properties;

    @BeforeEach
    void setUp() {
        properties = new DocumentProperties();
        // 手动构造，避免 @InjectMocks 无法处理 DocumentProperties
        routeService = new KnowledgeRouteServiceImpl(
                scopeNodeMapper, topicNodeMapper, relationMapper,
                documentMapper, documentProfileMapper,
                embeddingModelProvider, routeIndexServiceProvider,
                traceService, uidGenerator, properties);
    }

    @Nested
    @DisplayName("路由关闭")
    class RouteDisabled {

        @Test
        @DisplayName("路由关闭时返回 FAILED 状态")
        void shouldReturnFailedWhenDisabled() {
            properties.getKnowledgeRoute().setEnabled(false);

            KnowledgeRouteDecision decision = routeService.route("测试问题", null);

            assertThat(decision.getRouteStatus()).isEqualTo(KnowledgeRouteStatus.FAILED);
            assertThat(decision.getDocuments()).isEmpty();
            assertThat(decision.getReason()).contains("关闭");
        }
    }

    @Nested
    @DisplayName("Fallback 降级")
    class Fallback {

        @Test
        @DisplayName("scope/topic/document 全空时返回空结果")
        void shouldReturnEmptyWhenNoData() {
            when(scopeNodeMapper.selectList(any())).thenReturn(List.of());
            when(topicNodeMapper.selectList(any())).thenReturn(List.of());
            when(documentMapper.selectList(any())).thenReturn(List.of());

            KnowledgeRouteDecision decision = routeService.route("任意问题", null);

            assertThat(decision.getRouteStatus()).isEqualTo(KnowledgeRouteStatus.FAILED);
            assertThat(decision.getDocuments()).isEmpty();
        }

        @Test
        @DisplayName("scope 表无数据时从 Document 表推导")
        void shouldDeriveScopesFromDocuments() {
            when(scopeNodeMapper.selectList(any())).thenReturn(List.of());
            when(topicNodeMapper.selectList(any())).thenReturn(List.of());

            Document doc = Document.builder()
                    .id(1L).documentName("产品手册")
                    .knowledgeScopeCode("product").knowledgeScopeName("产品文档")
                    .build();
            when(documentMapper.selectList(any())).thenReturn(List.of(doc));

            KnowledgeRouteDecision decision = routeService.route("产品功能咨询", null);

            assertThat(decision.getScopes()).isNotEmpty();
            assertThat(decision.getScopes().get(0).getScopeCode()).isEqualTo("product");
        }

        @Test
        @DisplayName("无 Embedding 时仅靠实体词命中打分")
        void shouldScoreByKeywordOnlyWhenNoEmbedding() {
            when(embeddingModelProvider.getIfAvailable()).thenReturn(null);

            KnowledgeScopeNode scope = KnowledgeScopeNode.builder()
                    .scopeCode("api").scopeName("api参考").build();
            when(scopeNodeMapper.selectList(any())).thenReturn(List.of(scope));
            when(topicNodeMapper.selectList(any())).thenReturn(List.of());
            when(documentMapper.selectList(any())).thenReturn(List.of());

            KnowledgeRouteDecision decision = routeService.route("api 接口文档", null);

            assertThat(decision.getScopes()).isNotEmpty();
            assertThat(decision.getScopes().get(0).getScore()).isGreaterThan(BigDecimal.ZERO);
        }
    }

    @Nested
    @DisplayName("三级评分")
    class Scoring {

        @Test
        @DisplayName("scope 评分按分数降序排列")
        void shouldRankScopesByScore() {
            KnowledgeScopeNode s1 = KnowledgeScopeNode.builder()
                    .scopeCode("product").scopeName("产品文档").build();
            KnowledgeScopeNode s2 = KnowledgeScopeNode.builder()
                    .scopeCode("api").scopeName("api参考").build();
            when(scopeNodeMapper.selectList(any())).thenReturn(List.of(s1, s2));
            when(topicNodeMapper.selectList(any())).thenReturn(List.of());
            when(documentMapper.selectList(any())).thenReturn(List.of());

            KnowledgeRouteDecision decision = routeService.route("api 参考 接口", null);

            assertThat(decision.getScopes()).hasSize(2);
            // api scope 应有更高分："api" code 命中 + "api" name 命中 + "参考" name 命中
            assertThat(decision.getScopes().get(0).getScopeCode()).isEqualTo("api");
        }

        @Test
        @DisplayName("topic 级 scope 匹配加分")
        void shouldBoostTopicWhenScopeMatches() {
            KnowledgeScopeNode scope = KnowledgeScopeNode.builder()
                    .scopeCode("product").scopeName("产品文档").build();
            when(scopeNodeMapper.selectList(any())).thenReturn(List.of(scope));

            KnowledgeTopicNode t1 = KnowledgeTopicNode.builder()
                    .topicCode("faq").topicName("常见问题").scopeCode("product").build();
            KnowledgeTopicNode t2 = KnowledgeTopicNode.builder()
                    .topicCode("api-ref").topicName("API参考").scopeCode("api").build();
            when(topicNodeMapper.selectList(any())).thenReturn(List.of(t1, t2));
            when(documentMapper.selectList(any())).thenReturn(List.of());

            KnowledgeRouteDecision decision = routeService.route("产品常见问题如何解决", null);

            TopicRouteCandidate topTopic = decision.topTopic();
            assertThat(topTopic).isNotNull();
            assertThat(topTopic.getTopicCode()).isEqualTo("faq");
            assertThat(topTopic.getReason()).contains("scopeMatch");
        }

        @Test
        @DisplayName("document 级 relationScore 加分")
        void shouldBoostDocumentWithRelationScore() {
            KnowledgeScopeNode scope = KnowledgeScopeNode.builder()
                    .scopeCode("product").scopeName("产品文档").build();
            when(scopeNodeMapper.selectList(any())).thenReturn(List.of(scope));

            KnowledgeTopicNode topic = KnowledgeTopicNode.builder()
                    .topicCode("faq").topicName("常见问题").scopeCode("product").build();
            when(topicNodeMapper.selectList(any())).thenReturn(List.of(topic));

            Document doc1 = Document.builder()
                    .id(1L).documentName("产品FAQ手册").knowledgeScopeCode("product").build();
            Document doc2 = Document.builder()
                    .id(2L).documentName("运维手册").knowledgeScopeCode("ops").build();
            when(documentMapper.selectList(any())).thenReturn(List.of(doc1, doc2));

            TopicDocumentRelation rel = TopicDocumentRelation.builder()
                    .topicCode("faq").documentId(1L)
                    .relationScore(new BigDecimal("0.85")).build();
            when(relationMapper.selectList(any())).thenReturn(List.of(rel));

            KnowledgeRouteDecision decision = routeService.route("产品常见问题", null);

            DocumentRouteCandidate topDoc = decision.topDocument();
            assertThat(topDoc).isNotNull();
            assertThat(topDoc.getDocumentId()).isEqualTo(1L);
            assertThat(topDoc.getReason()).contains("relation");
        }

        @Test
        @DisplayName("截断到配置上限")
        void shouldTruncateToMaxCandidates() {
            java.util.List<KnowledgeScopeNode> manyScopes = new java.util.ArrayList<>();
            for (int i = 1; i <= 10; i++) {
                manyScopes.add(KnowledgeScopeNode.builder()
                        .scopeCode("scope" + i).scopeName("范围" + i).build());
            }
            when(scopeNodeMapper.selectList(any())).thenReturn(manyScopes);
            when(topicNodeMapper.selectList(any())).thenReturn(List.of());
            when(documentMapper.selectList(any())).thenReturn(List.of());

            KnowledgeRouteDecision decision = routeService.route("测试", null);

            assertThat(decision.getScopes()).hasSizeLessThanOrEqualTo(5);
        }
    }

    @Nested
    @DisplayName("置信度计算")
    class Confidence {

        @Test
        @DisplayName("单候选置信度为 1.0")
        void shouldReturnOneForSingleCandidate() {
            KnowledgeScopeNode scope = KnowledgeScopeNode.builder()
                    .scopeCode("api").scopeName("api参考").build();
            when(scopeNodeMapper.selectList(any())).thenReturn(List.of(scope));
            when(topicNodeMapper.selectList(any())).thenReturn(List.of());

            Document doc = Document.builder()
                    .id(1L).documentName("API文档").knowledgeScopeCode("api").build();
            when(documentMapper.selectList(any())).thenReturn(List.of(doc));

            KnowledgeRouteDecision decision = routeService.route("API", null);

            assertThat(decision.getConfidence()).isEqualByComparingTo(BigDecimal.ONE);
            assertThat(decision.getRouteStatus()).isEqualTo(KnowledgeRouteStatus.SUCCESS);
        }

        @Test
        @DisplayName("两个文档得分接近 → 低置信度")
        void shouldDetectLowConfidence() {
            properties.getKnowledgeRoute().setLowConfidenceThreshold(0.90);

            KnowledgeScopeNode scope = KnowledgeScopeNode.builder()
                    .scopeCode("product").scopeName("产品").build();
            when(scopeNodeMapper.selectList(any())).thenReturn(List.of(scope));
            when(topicNodeMapper.selectList(any())).thenReturn(List.of());

            Document doc1 = Document.builder().id(1L).documentName("产品文档").build();
            Document doc2 = Document.builder().id(2L).documentName("运维文档").build();
            when(documentMapper.selectList(any())).thenReturn(List.of(doc1, doc2));

            KnowledgeRouteDecision decision = routeService.route("产品", null);

            assertThat(decision.getRouteStatus()).isEqualTo(KnowledgeRouteStatus.LOW_CONFIDENCE);
        }
    }

    @Nested
    @DisplayName("数学工具")
    class MathUtils {

        @Test
        @DisplayName("cosineSimilarity 相同向量为 1.0")
        void shouldReturnOneForIdenticalVectors() {
            float[] v = {1.0f, 2.0f, 3.0f};
            double sim = KnowledgeRouteServiceImpl.cosineSimilarity(v, v);
            assertThat(sim).isCloseTo(1.0, org.assertj.core.data.Offset.offset(0.0001));
        }

        @Test
        @DisplayName("cosineSimilarity 正交向量为 0.0")
        void shouldReturnZeroForOrthogonalVectors() {
            float[] a = {1.0f, 0.0f};
            float[] b = {0.0f, 1.0f};
            double sim = KnowledgeRouteServiceImpl.cosineSimilarity(a, b);
            assertThat(sim).isCloseTo(0.0, org.assertj.core.data.Offset.offset(0.0001));
        }

        @Test
        @DisplayName("cosineSimilarity null 安全")
        void shouldHandleNullAndMismatch() {
            assertThat(KnowledgeRouteServiceImpl.cosineSimilarity(null, new float[]{1f})).isEqualTo(0.0);
            assertThat(KnowledgeRouteServiceImpl.cosineSimilarity(new float[]{1f}, null)).isEqualTo(0.0);
            assertThat(KnowledgeRouteServiceImpl.cosineSimilarity(new float[]{1f}, new float[]{1f, 2f})).isEqualTo(0.0);
        }
    }
}
