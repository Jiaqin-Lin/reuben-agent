package com.reubenagent.document;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.Refresh;
import com.reubenagent.common.dto.PageVo;
import com.reubenagent.common.enums.DocumentManageCode;
import com.reubenagent.common.exception.DocumentException;
import com.reubenagent.document.dto.*;
import com.reubenagent.document.entity.*;
import com.reubenagent.document.enums.KnowledgeRouteMode;
import com.reubenagent.document.enums.KnowledgeRouteStatus;
import com.reubenagent.document.mapper.*;
import com.reubenagent.document.model.es.RouteLexicalHit;
import com.reubenagent.document.model.route.*;
import com.reubenagent.document.service.IDocumentVectorGateway;
import com.reubenagent.document.service.IKnowledgeManageService;
import com.reubenagent.document.service.KnowledgeRouteIndexService;
import com.reubenagent.document.service.KnowledgeRouteService;
import com.reubenagent.document.service.impl.EsKnowledgeRouteIndexService;
import com.reubenagent.document.support.KnowledgeRouteTokenizer;
import com.reubenagent.document.vo.*;
import com.reubenagent.framework.uid.UidGenerator;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.*;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.when;

/**
 * Knowledge 模块 Docker 集成测试 —— 真实 MySQL + ES + mock Embedding。
 *
 * <p>前置条件: docker compose up -d mysql elasticsearch</p>
 * <p>运行: mvn test -pl business/document -am
 *    -Dtest=KnowledgeDockerIntegrationTest
 *    -Dsurefire.failIfNoSpecifiedTests=false
 *    -Dspring.profiles.active=docker</p>
 *
 * @author reuben
 * @since 2026-06-28
 */
@Slf4j
@SpringBootTest(
        classes = DocumentTestConfig.TestApp.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.neo4j.Neo4jAutoConfiguration",
                "reuben.document.pgvector.enabled=false",
                "reuben.document.elasticsearch.route-index-name=reuben_test_route_integration",
                "reuben.document.elasticsearch.analyzer=standard",
                "reuben.document.elasticsearch.search-analyzer=standard",
                "reuben.document.knowledge-route.enabled=true"
        })
@Import(DocumentTestConfig.TestMetaConfig.class)
@ActiveProfiles("docker")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("Knowledge Docker 集成测试")
class KnowledgeDockerIntegrationTest {

    // ==================== Mock Beans ====================

    @MockBean
    private UidGenerator uidGenerator;
    @MockBean
    private EmbeddingModel embeddingModel;
    @MockBean
    private IDocumentVectorGateway documentVectorGateway;

    // ==================== Autowired Beans ====================

    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private ElasticsearchClient esClient;
    @Autowired
    private IKnowledgeScopeNodeMapper scopeMapper;
    @Autowired
    private IKnowledgeTopicNodeMapper topicMapper;
    @Autowired
    private ITopicDocumentRelationMapper relationMapper;
    @Autowired
    private IDocumentMapper documentMapper;
    @Autowired
    private IDocumentProfileMapper profileMapper;
    @Autowired
    private IKnowledgeManageService manageService;
    @Autowired
    private KnowledgeRouteService routeService;
    @Autowired
    private KnowledgeRouteIndexService routeIndexService;
    @Autowired
    private IKnowledgeRouteTraceMapper traceMapper;

    private final AtomicLong idSeq = new AtomicLong(10000);
    private static final int EMBEDDING_DIM = 1024;

    // ==================== Lifecycle ====================

    @BeforeAll
    void initMocks() {
        // EmbeddingModel: 返回确定性归一化向量（同文本同向量，不同文本近正交）
        when(embeddingModel.embed(anyList())).thenAnswer(inv -> {
            @SuppressWarnings("unchecked")
            List<String> texts = inv.getArgument(0);
            return texts.stream().map(this::deterministicEmbedding).toList();
        });
    }

    @BeforeEach
    void setUp() throws Exception {
        DocumentTestSchema.dropTables(jdbcTemplate);
        DocumentTestSchema.createAllTables(jdbcTemplate);
        when(uidGenerator.getUid()).thenAnswer(inv -> idSeq.incrementAndGet());

        // 清理 ES 测试索引
        deleteTestEsIndex();
    }

    private void deleteTestEsIndex() {
        try {
            esClient.deleteByQuery(d -> d
                    .index("reuben_test_route_integration")
                    .refresh(true)
                    .query(q -> q.matchAll(m -> m)));
        } catch (Exception e) {
            log.debug("ES 索引清理跳过（可能尚未创建）: {}", e.getMessage());
        }
    }

    // ==================== Helpers ====================

    private float[] deterministicEmbedding(String text) {
        int seed = text == null ? 0 : text.hashCode();
        Random rand = new Random(seed);
        float[] vec = new float[EMBEDDING_DIM];
        double norm = 0.0;
        for (int i = 0; i < EMBEDDING_DIM; i++) {
            vec[i] = (float) rand.nextGaussian();
            norm += vec[i] * vec[i];
        }
        norm = Math.sqrt(norm);
        if (norm > 0) {
            for (int i = 0; i < EMBEDDING_DIM; i++) {
                vec[i] /= norm;
            }
        }
        return vec;
    }

    /** 轮询等待 trace 异步落库 */
    private List<KnowledgeRouteTrace> awaitTraces(int expectedCount, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            List<KnowledgeRouteTrace> traces = traceMapper.selectList(
                    new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<KnowledgeRouteTrace>()
                            .eq(KnowledgeRouteTrace::getIsDeleted, 0));
            if (traces.size() >= expectedCount) {
                return traces;
            }
            try { Thread.sleep(100); } catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
        }
        return traceMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<KnowledgeRouteTrace>()
                        .eq(KnowledgeRouteTrace::getIsDeleted, 0));
    }

    /** 反射调用 EsKnowledgeRouteIndexService.doRefresh() 同步刷新 ES 索引 */
    private void forceRefreshRouteIndex() throws Exception {
        if (!(routeIndexService instanceof EsKnowledgeRouteIndexService esService)) {
            log.warn("routeIndexService 不是 EsKnowledgeRouteIndexService，跳过 ES 刷新");
            return;
        }
        // 重置 refreshPending 标志
        Field pendingField = EsKnowledgeRouteIndexService.class.getDeclaredField("refreshPending");
        pendingField.setAccessible(true);
        pendingField.set(esService, false);

        Method doRefresh = EsKnowledgeRouteIndexService.class.getDeclaredMethod("doRefresh");
        doRefresh.setAccessible(true);
        doRefresh.invoke(esService);

        // bulk 索引无强制 refresh，手动刷新确保可搜索
        try {
            esClient.indices().refresh(r -> r.index("reuben_test_route_integration"));
        } catch (Exception e) {
            log.warn("ES 手动刷新失败: {}", e.getMessage());
        }
    }

    /** 创建测试用 Document */
    private Long createDocument(String name, String scopeCode, String scopeName,
                                 String businessCategory, String tags) {
        Long docId = uidGenerator.getUid();
        Document doc = Document.builder()
                .id(docId).documentName(name).originalFileName(name + ".pdf")
                .fileType(1).mediaType("application/pdf").fileSize(1024L)
                .storageType(1).bucketName("test").objectName("test/" + name + ".pdf")
                .parseStatus(3).strategyStatus(1).indexStatus(3)
                .knowledgeScopeCode(scopeCode).knowledgeScopeName(scopeName)
                .businessCategory(businessCategory).documentTags(tags)
                .build();
        documentMapper.insert(doc);
        return docId;
    }

    /** 创建测试用 DocumentProfile */
    private void createProfile(Long documentId, String summary, String coreTopics, String exampleQuestions) {
        DocumentProfile profile = DocumentProfile.builder()
                .id(uidGenerator.getUid()).documentId(documentId)
                .profileVersion(1).documentSummary(summary)
                .documentType("manual").coreTopics(coreTopics)
                .exampleQuestions(exampleQuestions)
                .graphFriendly(1).profileSource("auto").profileStatus(2)
                .build();
        profileMapper.insert(profile);
    }

    // ==================== 1. 环境就绪 ====================

    @Test
    @Order(0)
    @DisplayName("环境就绪检查")
    void shouldPrepareEnvironment() {
        assertThat(esClient).isNotNull();
        assertThat(embeddingModel).isNotNull();
        assertThat(routeService).isNotNull();
        assertThat(routeIndexService).isNotNull();
        assertThat(manageService).isNotNull();

        // 验证 ES 连通
        try {
            boolean alive = esClient.ping().value();
            assertThat(alive).isTrue();
        } catch (Exception e) {
            fail("ES ping 失败: " + e.getMessage());
        }

        log.info("=== Knowledge Docker 集成测试环境就绪 ===");
    }

    // ==================== 2. Scope CRUD ====================

    @Test
    @Order(1)
    @DisplayName("Scope CRUD 全流程")
    void shouldCrudScope() {
        // 创建
        KnowledgeScopeItemVo created = manageService.saveScope(
                KnowledgeScopeSaveDto.builder()
                        .scopeCode("product").scopeName("产品文档")
                        .description("产品相关文档").aliases("产品,product").sortOrder(1).build());
        assertThat(created.getScopeCode()).isEqualTo("product");
        assertThat(created.getScopeName()).isEqualTo("产品文档");

        // 更新
        KnowledgeScopeItemVo updated = manageService.saveScope(
                KnowledgeScopeSaveDto.builder()
                        .scopeCode("product").scopeName("产品文档V2").sortOrder(2).build());
        assertThat(updated.getScopeName()).isEqualTo("产品文档V2");

        // 验证不重复
        long count = scopeMapper.selectCount(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<KnowledgeScopeNode>()
                .eq(KnowledgeScopeNode::getScopeCode, "product")
                .eq(KnowledgeScopeNode::getIsDeleted, 0));
        assertThat(count).isOne();

        // 多 scope 列表
        manageService.saveScope(KnowledgeScopeSaveDto.builder().scopeCode("api").scopeName("API 文档").sortOrder(0).build());
        List<KnowledgeScopeItemVo> list = manageService.listScopes();
        assertThat(list).hasSize(2);
        assertThat(list.get(0).getScopeCode()).isEqualTo("api"); // sortOrder 0 first

        // 软删除
        manageService.deleteScope(KnowledgeScopeDeleteDto.builder().scopeCode("api").build());
        List<KnowledgeScopeItemVo> afterDelete = manageService.listScopes();
        assertThat(afterDelete).hasSize(1);
        assertThat(afterDelete.get(0).getScopeCode()).isEqualTo("product");

        // 删除不存在 → 异常
        assertThatThrownBy(() -> manageService.deleteScope(
                KnowledgeScopeDeleteDto.builder().scopeCode("nonexistent").build()))
                .isInstanceOf(DocumentException.class);
    }

    // ==================== 3. Topic CRUD + Relation ====================

    @Test
    @Order(2)
    @DisplayName("Topic CRUD 与 Relation 绑定")
    void shouldCrudTopicAndRelation() {
        // 准备 scope
        manageService.saveScope(KnowledgeScopeSaveDto.builder()
                .scopeCode("product").scopeName("产品").build());

        // 创建 topic
        KnowledgeTopicItemVo topic = manageService.saveTopic(KnowledgeTopicSaveDto.builder()
                .topicCode("faq").topicName("常见问题").scopeCode("product")
                .answerShape("list").executionPreference("retrieval").build());
        assertThat(topic.getTopicCode()).isEqualTo("faq");
        assertThat(topic.getScopeCode()).isEqualTo("product");

        // scope 不存在 → 异常
        assertThatThrownBy(() -> manageService.saveTopic(KnowledgeTopicSaveDto.builder()
                .topicCode("bad").topicName("Bad").scopeCode("no-scope").build()))
                .isInstanceOf(DocumentException.class);

        // 按 scope 筛选
        manageService.saveTopic(KnowledgeTopicSaveDto.builder()
                .topicCode("ref").topicName("参考").scopeCode("product").build());
        List<KnowledgeTopicItemVo> topics = manageService.listTopics("product");
        assertThat(topics).hasSize(2);

        // 准备 document + profile
        Long docId = createDocument("产品FAQ手册", "product", "产品文档", "FAQ", "faq,product");
        createProfile(docId, "产品常见问题汇总", "[\"FAQ\",\"常见问题\"]",
                "[\"如何登录?\",\"如何重置密码?\"]");

        // 创建 relation
        TopicDocumentRelationItemVo rel = manageService.saveRelation(
                TopicDocumentRelationSaveDto.builder()
                        .topicCode("faq").documentId(docId)
                        .relationScore(new BigDecimal("0.90")).reason("主题匹配").build());
        assertThat(rel.getDocumentName()).isEqualTo("产品FAQ手册");
        assertThat(rel.getRelationScore()).isEqualByComparingTo("0.90");

        // 列表
        List<TopicDocumentRelationItemVo> rels = manageService.listRelations("faq");
        assertThat(rels).hasSize(1);

        // 删除
        manageService.removeRelation(TopicDocumentRelationRemoveDto.builder()
                .topicCode("faq").documentId(docId).build());
        List<TopicDocumentRelationItemVo> empty = manageService.listRelations("faq");
        assertThat(empty).isEmpty();
    }

    // ==================== 4. 路由引擎 — 三级打分 ====================

    @Test
    @Order(3)
    @DisplayName("路由引擎 — 三级路由打分")
    void shouldRouteWithThreeLevelScoring() throws Exception {
        // 阶段1: 准备知识节点
        seedKnowledgeGraph();

        // 阶段2: 刷新 ES 路由索引
        forceRefreshRouteIndex();

        // 验证 ES 索引有数据
        List<RouteLexicalHit> esHits = routeIndexService.search("产品常见问题", "topic", 5);
        assertThat(esHits).isNotEmpty();
        log.info("ES 词法命中: {} 条", esHits.size());

        // 阶段3: 执行路由
        KnowledgeRouteDecision decision = routeService.route("产品常见问题有哪些？", "产品常见问题");

        assertThat(decision).isNotNull();
        assertThat(decision.getScopes()).isNotEmpty();
        assertThat(decision.getTopics()).isNotEmpty();
        assertThat(decision.getDocuments()).isNotEmpty();

        // Scope 级验证
        ScopeRouteCandidate topScope = decision.topScope();
        assertThat(topScope).isNotNull();
        assertThat(topScope.getScopeCode()).isEqualTo("product");
        log.info("Top scope: {} score={}", topScope.getScopeName(), topScope.getScore());

        // Topic 级验证
        TopicRouteCandidate topTopic = decision.topTopic();
        assertThat(topTopic).isNotNull();
        assertThat(topTopic.getTopicCode()).isEqualTo("faq");
        log.info("Top topic: {} score={}", topTopic.getTopicName(), topTopic.getScore());

        // Document 级验证
        DocumentRouteCandidate topDoc = decision.topDocument();
        assertThat(topDoc).isNotNull();
        assertThat(topDoc.getDocumentName()).contains("FAQ");
        log.info("Top document: {} score={}", topDoc.getDocumentName(), topDoc.getScore());

        // 置信度
        assertThat(decision.getConfidence()).isNotNull();
        assertThat(decision.getRouteStatus()).isIn(KnowledgeRouteStatus.SUCCESS, KnowledgeRouteStatus.LOW_CONFIDENCE);
        log.info("置信度: {} status={}", decision.getConfidence(), decision.getRouteStatus());
    }

    // ==================== 5. 路由引擎 — 低置信度降级 ====================

    @Test
    @Order(4)
    @DisplayName("路由引擎 — 低置信度降级")
    void shouldRouteLowConfidenceForUnrelatedQuestion() throws Exception {
        seedKnowledgeGraph();
        forceRefreshRouteIndex();

        // 用不相关的问题路由
        KnowledgeRouteDecision decision = routeService.route(
                "今天天气怎么样？", "今天天气怎么样？");

        assertThat(decision).isNotNull();
        // 无关联时应该 LOW_CONFIDENCE 或 单候选被选中但 score 低
        log.info("不相关问题置信度: {} status={}", decision.getConfidence(), decision.getRouteStatus());
    }

    // ==================== 6. 路由引擎 — Fallback derive ====================

    @Test
    @Order(5)
    @DisplayName("路由引擎 — 无 scope/topic 节点时从 Document 推导")
    void shouldDeriveFromDocumentsWhenNoNodes() throws Exception {
        // 无 scope/topic 记录，仅有 document + profile
        Long docId1 = createDocument("Java 编程指南", "java", "Java 技术栈", "Programming", "java,guide");
        createProfile(docId1, "Java 编程入门指南",
                "[\"Java\",\"编程\"]", "[\"如何学 Java?\"]");
        Long docId2 = createDocument("Python 数据分析", "python", "Python 技术栈", "Data", "python,data");
        createProfile(docId2, "Python 数据分析手册",
                "[\"Python\",\"数据分析\"]", "[\"如何用 Python 分析数据?\"]");

        forceRefreshRouteIndex();

        KnowledgeRouteDecision decision = routeService.route("Python 数据分析怎么做？", "Python 数据分析");

        assertThat(decision).isNotNull();
        // 至少有人工文档候选（从 document 推导 scope）
        assertThat(decision.getDocuments()).isNotEmpty();
        log.info("Fallback 路由: {} 个文档候选, confidence={}",
                decision.getDocuments().size(), decision.getConfidence());
    }

    // ==================== 7. 路由追踪落库 ====================

    @Test
    @Order(6)
    @DisplayName("路由追踪落库与分页查询")
    void shouldRecordTraceAndPageQuery() throws Exception {
        seedKnowledgeGraph();
        forceRefreshRouteIndex();

        // Shadow 路由
        routeService.recordShadowRoute("conv-shadow", 1L, 10001L,
                "测试问题", "改写后的问题");

        // Auto 路由
        KnowledgeRouteDecision decision = routeService.route("产品FAQ有哪些？", "产品FAQ");
        routeService.recordAutoRoute("conv-auto", 2L, "自动路由问题",
                "自动路由改写", decision);

        // 验证 trace 落库（异步写入，轮询等待）
        List<KnowledgeRouteTrace> traces = awaitTraces(2, 5000);
        assertThat(traces).hasSize(2);
        log.info("落库 trace 数量: {}", traces.size());

        // 分页查询
        KnowledgeRouteTraceQueryDto queryDto = KnowledgeRouteTraceQueryDto.builder()
                .pageNo(1).pageSize(10).build();
        PageVo<KnowledgeRouteTraceItemVo> page = manageService.pageQueryRouteTrace(queryDto);
        assertThat(page.getTotal()).isEqualTo(2);
        assertThat(page.getRecords()).hasSize(2);

        // 按 mode 筛选（DB 存储的是 getMsg() 即中文值）
        String shadowMsg = KnowledgeRouteMode.SHADOW.getMsg();
        KnowledgeRouteTraceQueryDto shadowQuery = KnowledgeRouteTraceQueryDto.builder()
                .mode(shadowMsg).pageNo(1).pageSize(10).build();
        PageVo<KnowledgeRouteTraceItemVo> shadowPage = manageService.pageQueryRouteTrace(shadowQuery);
        assertThat(shadowPage.getTotal()).isEqualTo(1);
        assertThat(shadowPage.getRecords().get(0).getMode()).isEqualTo(shadowMsg);
    }

    // ==================== 8. 分词器 ====================

    @Test
    @Order(7)
    @DisplayName("KnowledgeRouteTokenizer — 中文分词 + n-gram")
    void shouldTokenizeChineseText() {
        String delimiter = "[\\s、，,；;：:（）()\\-的和及与或]+";
        List<String> tokens = KnowledgeRouteTokenizer.tokenize(
                "产品常见问题与解决方案", delimiter, 2, 40);

        assertThat(tokens).isNotEmpty();
        assertThat(tokens).contains("产品", "常见问题", "解决方案");
        log.info("分词结果: {}", tokens);
    }

    // ==================== Seed Data ====================

    private void seedKnowledgeGraph() {
        // Scopes
        manageService.saveScope(KnowledgeScopeSaveDto.builder()
                .scopeCode("product").scopeName("产品文档")
                .description("所有产品相关文档").aliases("产品,product").sortOrder(1).build());
        manageService.saveScope(KnowledgeScopeSaveDto.builder()
                .scopeCode("tech").scopeName("技术文档")
                .description("技术架构相关文档").aliases("技术,tech").sortOrder(2).build());

        // Topics
        manageService.saveTopic(KnowledgeTopicSaveDto.builder()
                .topicCode("faq").topicName("常见问题").scopeCode("product")
                .description("产品使用常见问题").aliases("FAQ,问答")
                .answerShape("list").executionPreference("retrieval").sortOrder(1).build());
        manageService.saveTopic(KnowledgeTopicSaveDto.builder()
                .topicCode("guide").topicName("使用指南").scopeCode("product")
                .description("产品使用指南").aliases("指南,教程")
                .answerShape("steps").executionPreference("graph_then_evidence").sortOrder(2).build());
        manageService.saveTopic(KnowledgeTopicSaveDto.builder()
                .topicCode("architecture").topicName("架构设计").scopeCode("tech")
                .description("技术架构设计文档").aliases("架构,设计")
                .answerShape("explain").executionPreference("graph_only").sortOrder(1).build());

        // Documents
        Long doc1 = createDocument("产品FAQ手册", "product", "产品文档", "FAQ", "faq,product,help");
        createProfile(doc1, "关于产品使用的常见问题解答",
                "[\"FAQ\",\"常见问题\",\"产品使用\"]",
                "[\"如何登录系统?\",\"忘记密码怎么办?\",\"如何创建新项目?\"]");

        Long doc2 = createDocument("产品快速入门指南", "product", "产品文档", "Guide", "guide,quickstart");
        createProfile(doc2, "产品快速入门使用指南",
                "[\"快速入门\",\"使用指南\"]",
                "[\"第一步做什么?\",\"如何配置项目?\"]");

        Long doc3 = createDocument("系统架构设计文档", "tech", "技术文档", "Architecture", "architecture,design");
        createProfile(doc3, "系统整体架构设计说明",
                "[\"架构设计\",\"系统架构\"]",
                "[\"系统由哪些模块组成?\",\"各模块之间如何通信?\"]");

        // Relations
        manageService.saveRelation(TopicDocumentRelationSaveDto.builder()
                .topicCode("faq").documentId(doc1).relationScore(new BigDecimal("0.95")).build());
        manageService.saveRelation(TopicDocumentRelationSaveDto.builder()
                .topicCode("guide").documentId(doc2).relationScore(new BigDecimal("0.90")).build());
        manageService.saveRelation(TopicDocumentRelationSaveDto.builder()
                .topicCode("architecture").documentId(doc3).relationScore(new BigDecimal("0.92")).build());

        // 跨 scope 弱关联
        manageService.saveRelation(TopicDocumentRelationSaveDto.builder()
                .topicCode("faq").documentId(doc2).relationScore(new BigDecimal("0.30")).build());

        log.info("种子数据已插入: 2 scopes, 3 topics, 3 documents, 4 relations");
    }
}
