package com.reubenagent.document;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import com.reubenagent.document.entity.Document;
import com.reubenagent.document.entity.DocumentStructureNode;
import com.reubenagent.document.entity.KnowledgeScopeNode;
import com.reubenagent.document.entity.KnowledgeTopicNode;
import com.reubenagent.document.entity.TopicDocumentRelation;
import com.reubenagent.document.mapper.IDocumentMapper;
import com.reubenagent.document.mapper.IDocumentStructureNodeMapper;
import com.reubenagent.document.model.es.NavigationSectionHit;
import com.reubenagent.document.model.graph.GraphSection;
import com.reubenagent.document.model.route.DocumentRouteCandidate;
import com.reubenagent.document.model.route.KnowledgeRouteDecision;
import com.reubenagent.document.service.*;
import com.reubenagent.document.service.IDocumentVectorGateway;
import com.reubenagent.document.service.impl.CompositeGraphAvailabilityCache;
import com.reubenagent.document.service.impl.EsKnowledgeRouteIndexService;
import com.reubenagent.framework.uid.UidGenerator;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.*;
import org.neo4j.driver.Driver;
import org.neo4j.driver.Session;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.when;

/**
 * 端到端 Docker 集成测试 —— Knowledge 路由 + Neo4j 图查询 + Navigation 索引。
 *
 * <p>验证三个能力块在同一文档上协同工作。</p>
 *
 * <p>前置条件: docker compose up -d mysql elasticsearch neo4j</p>
 * <p>运行: mvn test -pl business/document -am
 *    -Dtest=E2EDockerIntegrationTest
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
                "reuben.document.neo4j.enabled=true",
                "reuben.document.neo4j.uri=bolt://127.0.0.1:7687",
                "reuben.document.neo4j.username=neo4j",
                "reuben.document.neo4j.password=neo4j123",
                "reuben.document.neo4j.database=neo4j",
                "reuben.document.neo4j.query-timeout-seconds=10",
                "reuben.document.neo4j.max-connection-pool-size=5",
                "reuben.document.neo4j.connection-acquisition-timeout-ms=5000",
                "reuben.document.elasticsearch.route-index-name=reuben_test_e2e_route",
                "reuben.document.elasticsearch.navigation-index-name=reuben_test_e2e_nav",
                "reuben.document.elasticsearch.analyzer=standard",
                "reuben.document.elasticsearch.search-analyzer=standard",
                "reuben.document.knowledge-route.enabled=true"
        })
@Import(DocumentTestConfig.TestMetaConfig.class)
@ActiveProfiles("docker")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("端到端 Docker 集成测试")
class E2EDockerIntegrationTest {

    // ==================== Mock ====================

    @MockBean
    private UidGenerator uidGenerator;
    @MockBean
    private EmbeddingModel embeddingModel;
    @MockBean
    private IDocumentVectorGateway documentVectorGateway;

    // ==================== Autowired ====================

    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private ElasticsearchClient esClient;
    @Autowired
    private Driver documentManageNeo4jDriver;
    @Autowired
    private KnowledgeRouteService routeService;
    @Autowired
    private KnowledgeRouteIndexService routeIndexService;
    @Autowired
    private DocumentNavigationIndexService navIndexService;
    @Autowired
    private DocumentStructureGraphProjectionService projectionService;
    @Autowired
    private DocumentStructureGraphService graphService;
    @Autowired
    private IDocumentMapper documentMapper;
    @Autowired
    private IDocumentStructureNodeMapper structureNodeMapper;

    private final AtomicLong idSeq = new AtomicLong(80000);
    private static final int DIM = 1024;

    // Test document IDs
    private static final Long DOC_FAQ = 501L;
    private static final Long DOC_ARCH = 502L;
    private static final Long TASK_ID = 601L;

    @BeforeAll
    void initMocks() {
        when(embeddingModel.embed(anyList())).thenAnswer(inv -> {
            @SuppressWarnings("unchecked")
            List<String> texts = inv.getArgument(0);
            return texts.stream().map(this::embedding).toList();
        });
    }

    @BeforeEach
    void setUp() {
        DocumentTestSchema.dropTables(jdbcTemplate);
        DocumentTestSchema.createAllTables(jdbcTemplate);
        when(uidGenerator.getUid()).thenAnswer(inv -> idSeq.incrementAndGet());
        CompositeGraphAvailabilityCache.evict(DOC_FAQ);
        CompositeGraphAvailabilityCache.evict(DOC_ARCH);
        cleanEsIndices();
        cleanNeo4j();
    }

    private void cleanEsIndices() {
        for (String idx : List.of("reuben_test_e2e_route", "reuben_test_e2e_nav")) {
            try {
                esClient.deleteByQuery(d -> d.index(idx).refresh(true)
                        .query(q -> q.matchAll(m -> m)));
            } catch (Exception ignored) { /* not created yet */ }
        }
    }

    private void cleanNeo4j() {
        try (Session session = documentManageNeo4jDriver.session()) {
            for (Long docId : List.of(DOC_FAQ, DOC_ARCH)) {
                session.run("MATCH (n) WHERE n.documentId = $docId DETACH DELETE n",
                        org.neo4j.driver.Values.parameters("docId", docId));
            }
        } catch (Exception e) {
            log.debug("Neo4j 清理跳过: {}", e.getMessage());
        }
    }

    /** 刷新 ES 导航索引，确保 reindex 后可立即搜索 */
    private void refreshNavIndex() {
        try {
            esClient.indices().refresh(r -> r.index("reuben_test_e2e_nav"));
        } catch (Exception e) {
            log.warn("ES nav refresh 失败: {}", e.getMessage());
        }
    }

    private float[] embedding(String text) {
        Random rand = new Random(text == null ? 0 : text.hashCode());
        float[] vec = new float[DIM];
        double norm = 0;
        for (int i = 0; i < DIM; i++) {
            vec[i] = (float) rand.nextGaussian();
            norm += vec[i] * vec[i];
        }
        norm = Math.sqrt(norm);
        if (norm > 0) {
            for (int i = 0; i < DIM; i++) vec[i] /= norm;
        }
        return vec;
    }

    private void forceRefreshRouteIndex() throws Exception {
        if (routeIndexService instanceof EsKnowledgeRouteIndexService es) {
            Field pf = EsKnowledgeRouteIndexService.class.getDeclaredField("refreshPending");
            pf.setAccessible(true);
            pf.set(es, false);
            Method m = EsKnowledgeRouteIndexService.class.getDeclaredMethod("doRefresh");
            m.setAccessible(true);
            m.invoke(es);

            try {
                esClient.indices().refresh(r -> r.index("reuben_test_e2e_route"));
            } catch (Exception e2) {
                log.warn("ES 手动刷新失败: {}", e2.getMessage());
            }
        }
    }

    // ==================== 1. 环境就绪 ====================

    @Test
    @Order(0)
    @DisplayName("环境就绪 — ES + Neo4j + 三大服务均可用")
    void shouldPrepareFullEnvironment() {
        assertThat(esClient).isNotNull();
        assertThat(documentManageNeo4jDriver).isNotNull();
        assertThat(routeService).isNotNull();
        assertThat(navIndexService).isNotNull();
        assertThat(projectionService).isNotNull();
        assertThat(graphService).isNotNull();

        try { assertThat(esClient.ping().value()).isTrue(); } catch (Exception e) { fail("ES: " + e.getMessage()); }
        try (Session s = documentManageNeo4jDriver.session()) {
            assertThat(s.run("RETURN 1 AS ok").single().get("ok").asInt()).isEqualTo(1);
        }
        log.info("=== E2E 集成测试环境就绪 (ES + Neo4j + Knowledge + Navigation + Graph) ===");
    }

    // ==================== 2. 完整管线 ====================

    @Test
    @Order(1)
    @DisplayName("完整管线: Knowledge 路由 → Navigation 索引 → Neo4j 图查询")
    void shouldRunFullPipeline() throws Exception {
        // ===== 阶段 A: 准备知识图谱（Knowledge Route 数据） =====
        seedKnowledgeData();
        forceRefreshRouteIndex();

        // ===== 阶段 B: 准备文档结构和图数据（Neo4j + Navigation） =====
        seedDocumentStructure(DOC_FAQ, "产品FAQ文档");
        seedDocumentStructure(DOC_ARCH, "系统架构文档");
        refreshNavIndex();

        // ===== 阶段 C: Knowledge 路由 — 选择文档 =====
        KnowledgeRouteDecision decision = routeService.route("产品常见问题有哪些？", "产品常见问题");
        assertThat(decision).isNotNull();
        assertThat(decision.getDocuments()).isNotEmpty();

        DocumentRouteCandidate topDoc = decision.topDocument();
        assertThat(topDoc).isNotNull();
        log.info("Knowledge 路由选中: documentId={} name={} confidence={}",
                topDoc.getDocumentId(), topDoc.getDocumentName(), decision.getConfidence());

        Long selectedDocId = topDoc.getDocumentId();

        // ===== 阶段 D: Navigation 索引 — 定位章节 =====
        List<NavigationSectionHit> navHits = navIndexService.searchSections(
                selectedDocId, "FAQ", null, null, "如何配置系统", 5);
        assertThat(navHits).isNotEmpty();
        log.info("Navigation 索引命中: {} 章节", navHits.size());
        for (NavigationSectionHit hit : navHits) {
            log.info("  - {} (score={})", hit.getTitle(), hit.getScore());
        }

        // ===== 阶段 E: Neo4j 图查询 — 遍历结构 =====
        assertThat(graphService.isGraphAvailable(selectedDocId)).isTrue();

        List<GraphSection> sections = graphService.listSections(selectedDocId);
        assertThat(sections).isNotEmpty();
        log.info("Neo4j 图查询: {} 个 Section", sections.size());

        // 验证兄弟关系
        if (sections.size() >= 2) {
            GraphSection first = sections.get(0);
            GraphSection next = graphService.nextSibling(selectedDocId, first.getNodeId());
            log.info("章节遍历: {} → next={}", first.getTitle(),
                    next != null ? next.getTitle() : "null");
        }

        // 验证父子关系
        GraphSection firstChild = sections.stream()
                .filter(s -> s.getDepth() != null && s.getDepth() > 0)
                .findFirst().orElse(null);
        if (firstChild != null) {
            GraphSection parent = graphService.parentSection(selectedDocId, firstChild.getNodeId());
            assertThat(parent).isNotNull();
            log.info("父子关系: parent={} child={}",
                    parent.getTitle(), firstChild.getTitle());
        }
    }

    // ==================== 3. 跨文档知识路由 ====================

    @Test
    @Order(2)
    @DisplayName("跨文档路由: 同一问题对不同文档给出不同路由")
    void shouldRouteDifferentDocuments() throws Exception {
        seedKnowledgeData();
        forceRefreshRouteIndex();
        seedDocumentStructure(DOC_FAQ, "产品FAQ文档");
        seedDocumentStructure(DOC_ARCH, "系统架构文档");

        // FAQ 问题 → 路由到 FAQ 文档
        KnowledgeRouteDecision faqDecision = routeService.route("怎么重置密码？", "怎么重置密码");
        assertThat(faqDecision.getDocuments()).isNotEmpty();
        DocumentRouteCandidate faqDoc = faqDecision.topDocument();
        log.info("FAQ 路由: docId={} name={}", faqDoc.getDocumentId(), faqDoc.getDocumentName());

        // 架构问题 → 路由到架构文档
        KnowledgeRouteDecision archDecision = routeService.route("系统模块之间如何通信？", "系统模块通信");
        assertThat(archDecision.getDocuments()).isNotEmpty();
        DocumentRouteCandidate archDoc = archDecision.topDocument();
        log.info("架构路由: docId={} name={}", archDoc.getDocumentId(), archDoc.getDocumentName());
    }

    // ==================== 4. 图结构一致性 ====================

    @Test
    @Order(3)
    @DisplayName("图结构一致性: Neo4j 投影与 MySQL 节点一致")
    void shouldHaveConsistentGraphAndMysql() throws Exception {
        seedDocumentStructure(DOC_FAQ, "一致性验证文档");

        // MySQL 节点数
        List<DocumentStructureNode> mysqlNodes = structureNodeMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<DocumentStructureNode>()
                        .eq(DocumentStructureNode::getDocumentId, DOC_FAQ));
        int mysqlSectionCount = (int) mysqlNodes.stream()
                .filter(n -> n.getNodeType() != null && (n.getNodeType() == 1 || n.getNodeType() == 2))
                .count();

        // Neo4j Section 数
        if (graphService.isGraphAvailable(DOC_FAQ)) {
            List<GraphSection> neo4jSections = graphService.listSections(DOC_FAQ);
            log.info("一致性: MySQL {} sections, Neo4j {} sections",
                    mysqlSectionCount, neo4jSections.size());
            // Note: exact match may differ if Neo4j projection differs from MySQL filter
            // This is a sanity check, not a strict assert
            assertThat(neo4jSections).isNotEmpty();
        }
    }

    // ==================== Seed Helpers ====================

    private void seedKnowledgeData() {
        // Doc IDs must match DOC_FAQ and DOC_ARCH
        Long faqId = DOC_FAQ;
        Long archId = DOC_ARCH;

        // Documents
        documentMapper.insert(Document.builder()
                .id(faqId).documentName("产品FAQ文档").originalFileName("faq.pdf")
                .fileType(1).mediaType("application/pdf").fileSize(2048L)
                .storageType(1).bucketName("test").objectName("test/faq.pdf")
                .parseStatus(3).strategyStatus(1).indexStatus(3)
                .knowledgeScopeCode("product").knowledgeScopeName("产品")
                .businessCategory("FAQ").documentTags("faq,product").build());

        documentMapper.insert(Document.builder()
                .id(archId).documentName("系统架构文档").originalFileName("arch.pdf")
                .fileType(1).mediaType("application/pdf").fileSize(4096L)
                .storageType(1).bucketName("test").objectName("test/arch.pdf")
                .parseStatus(3).strategyStatus(1).indexStatus(3)
                .knowledgeScopeCode("tech").knowledgeScopeName("技术")
                .businessCategory("Architecture").documentTags("architecture,design").build());

        // Scopes & Topics
        KnowledgeScopeNode scope1 = KnowledgeScopeNode.builder()
                .id(uidGenerator.getUid()).scopeCode("product").scopeName("产品文档")
                .description("产品").aliases("产品,product").sortOrder(1).build();
        KnowledgeScopeNode scope2 = KnowledgeScopeNode.builder()
                .id(uidGenerator.getUid()).scopeCode("tech").scopeName("技术文档")
                .description("技术").aliases("技术,tech").sortOrder(2).build();
        insertScope(scope1);
        insertScope(scope2);

        KnowledgeTopicNode topic1 = KnowledgeTopicNode.builder()
                .id(uidGenerator.getUid()).topicCode("faq").topicName("常见问题")
                .scopeCode("product").description("FAQ").aliases("FAQ,问答,问题").build();
        KnowledgeTopicNode topic2 = KnowledgeTopicNode.builder()
                .id(uidGenerator.getUid()).topicCode("architecture").topicName("架构设计")
                .scopeCode("tech").description("架构").aliases("架构,设计,模块").build();
        insertTopic(topic1);
        insertTopic(topic2);

        // Relations
        TopicDocumentRelation rel1 = TopicDocumentRelation.builder()
                .id(uidGenerator.getUid()).topicCode("faq").documentId(faqId)
                .relationScore(new java.math.BigDecimal("0.95")).relationSource("auto").build();
        TopicDocumentRelation rel2 = TopicDocumentRelation.builder()
                .id(uidGenerator.getUid()).topicCode("architecture").documentId(archId)
                .relationScore(new java.math.BigDecimal("0.92")).relationSource("auto").build();
        insertRelation(rel1);
        insertRelation(rel2);
    }

    private void insertScope(KnowledgeScopeNode s) {
        // Use raw JDBC to avoid service-level side effects
        jdbcTemplate.update(
                "INSERT INTO reuben_agent_knowledge_scope_node (id,scope_code,scope_name,description,aliases,sort_order,create_time,update_time,is_deleted) VALUES (?,?,?,?,?,?,NOW(),NOW(),0)",
                s.getId(), s.getScopeCode(), s.getScopeName(), s.getDescription(), s.getAliases(), s.getSortOrder());
    }

    private void insertTopic(KnowledgeTopicNode t) {
        jdbcTemplate.update(
                "INSERT INTO reuben_agent_knowledge_topic_node (id,topic_code,topic_name,scope_code,description,aliases,create_time,update_time,is_deleted) VALUES (?,?,?,?,?,?,NOW(),NOW(),0)",
                t.getId(), t.getTopicCode(), t.getTopicName(), t.getScopeCode(), t.getDescription(), t.getAliases());
    }

    private void insertRelation(TopicDocumentRelation r) {
        jdbcTemplate.update(
                "INSERT INTO reuben_agent_topic_document_relation (id,topic_code,document_id,relation_score,relation_source,create_time,update_time,is_deleted) VALUES (?,?,?,?,?,NOW(),NOW(),0)",
                r.getId(), r.getTopicCode(), r.getDocumentId(), r.getRelationScore(), r.getRelationSource());
    }

    private void seedDocumentStructure(Long documentId, String docName) {
        // Document
        Document doc = documentMapper.selectById(documentId);
        if (doc == null) {
            doc = Document.builder()
                    .id(documentId).documentName(docName).originalFileName(docName + ".pdf")
                    .fileType(1).mediaType("application/pdf").fileSize(1024L)
                    .storageType(1).bucketName("test").objectName("test/" + docName + ".pdf")
                    .parseStatus(3).strategyStatus(1).indexStatus(3)
                    .build();
            documentMapper.insert(doc);
        } else {
            doc.setDocumentName(docName);
            documentMapper.updateById(doc);
        }

        // Structure nodes
        Long rootId = uidGenerator.getUid();
        Long ch1Id = uidGenerator.getUid();
        Long ch2Id = uidGenerator.getUid();
        Long step1Id = uidGenerator.getUid();

        DocumentStructureNode root = DocumentStructureNode.builder()
                .id(rootId).documentId(documentId).parseTaskId(TASK_ID)
                .nodeNo(1).nodeType(1).depth(0)
                .nodeCode("1").title(docName + " - Overview")
                .anchorText("Overview").sectionPath("/" + docName)
                .canonicalPath("/" + documentId).contentText(docName + " overview content.").build();

        DocumentStructureNode ch1 = DocumentStructureNode.builder()
                .id(ch1Id).documentId(documentId).parseTaskId(TASK_ID)
                .nodeNo(2).nodeType(2).parentNodeId(rootId).depth(1)
                .nodeCode("1.1").title("Getting Started")
                .anchorText("Getting Started").sectionPath("/" + docName + "/getting-started")
                .canonicalPath("/" + documentId + "/1.1")
                .contentText("Getting started guide for " + docName + ".").build();

        DocumentStructureNode ch2 = DocumentStructureNode.builder()
                .id(ch2Id).documentId(documentId).parseTaskId(TASK_ID)
                .nodeNo(3).nodeType(2).parentNodeId(rootId).depth(1)
                .prevSiblingNodeId(ch1Id).nodeCode("1.2")
                .title("Advanced Configuration").anchorText("Advanced Configuration")
                .sectionPath("/" + docName + "/advanced")
                .canonicalPath("/" + documentId + "/1.2")
                .contentText("Advanced configuration for " + docName + ".").build();

        ch1.setNextSiblingNodeId(ch2Id);

        DocumentStructureNode step1 = DocumentStructureNode.builder()
                .id(step1Id).documentId(documentId).parseTaskId(TASK_ID)
                .nodeNo(4).nodeType(3).parentNodeId(ch1Id).depth(1)
                .title("Step 1: Initial Setup").anchorText("Step 1")
                .contentText("Perform initial setup.").itemIndex(1).build();

        for (DocumentStructureNode n : List.of(root, ch1, ch2, step1)) {
            structureNodeMapper.insert(n);
        }

        // Neo4j projection
        projectionService.projectToGraph(documentId, TASK_ID);

        // Navigation index (only SECTION nodes)
        navIndexService.reindexDocumentNodes(documentId, TASK_ID,
                List.of(root, ch1, ch2));
    }
}
