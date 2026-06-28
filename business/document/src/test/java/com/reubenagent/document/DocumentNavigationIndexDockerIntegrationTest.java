package com.reubenagent.document;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import com.reubenagent.document.entity.Document;
import com.reubenagent.document.entity.DocumentStructureNode;
import com.reubenagent.document.mapper.IDocumentMapper;
import com.reubenagent.document.mapper.IDocumentStructureNodeMapper;
import com.reubenagent.document.model.es.NavigationSectionHit;
import com.reubenagent.document.service.DocumentNavigationIndexService;
import com.reubenagent.document.service.IDocumentVectorGateway;
import com.reubenagent.framework.uid.UidGenerator;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.when;

/**
 * 导航索引 Docker 集成测试 —— 真实 MySQL + ES。
 *
 * <p>前置条件: docker compose up -d mysql elasticsearch</p>
 * <p>运行: mvn test -pl business/document -am
 *    -Dtest=DocumentNavigationIndexDockerIntegrationTest
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
                "reuben.document.elasticsearch.navigation-index-name=reuben_test_nav_integration",
                "reuben.document.elasticsearch.analyzer=standard",
                "reuben.document.elasticsearch.search-analyzer=standard"
        })
@Import(DocumentTestConfig.TestMetaConfig.class)
@ActiveProfiles("docker")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("导航索引 Docker 集成测试")
class DocumentNavigationIndexDockerIntegrationTest {

    @MockBean
    private UidGenerator uidGenerator;
    @MockBean
    private IDocumentVectorGateway documentVectorGateway;

    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private ElasticsearchClient esClient;
    @Autowired
    private DocumentNavigationIndexService navIndexService;
    @Autowired
    private IDocumentStructureNodeMapper structureNodeMapper;
    @Autowired
    private IDocumentMapper documentMapper;

    private final AtomicLong idSeq = new AtomicLong(60000);

    private static final Long DOC_ID = 77001L;
    private static final Long TASK_ID = 78001L;
    private static final String INDEX_NAME = "reuben_test_nav_integration";

    @BeforeEach
    void setUp() {
        DocumentTestSchema.dropTables(jdbcTemplate);
        DocumentTestSchema.createAllTables(jdbcTemplate);
        when(uidGenerator.getUid()).thenAnswer(inv -> idSeq.incrementAndGet());
        deleteTestEsIndex();
    }

    /** 强制刷新 ES 索引，确保 bulk 写入立即可搜索 */
    private void refreshEsIndex() {
        try {
            esClient.indices().refresh(r -> r.index(INDEX_NAME));
        } catch (Exception e) {
            log.warn("ES refresh 失败: {}", e.getMessage());
        }
    }

    private void deleteTestEsIndex() {
        try {
            esClient.deleteByQuery(d -> d
                    .index(INDEX_NAME)
                    .refresh(true)
                    .query(q -> q.matchAll(m -> m)));
        } catch (Exception e) {
            log.debug("ES 导航索引清理跳过（可能尚未创建）: {}", e.getMessage());
        }
    }

    /** 创建 CHAPTER 类型 Section 节点 */
    private DocumentStructureNode section(Long id, int nodeNo, int depth, Long parentId,
                                           String nodeCode, String title, String sectionPath,
                                           String canonicalPath, String contentText) {
        return DocumentStructureNode.builder()
                .id(id).documentId(DOC_ID).parseTaskId(TASK_ID)
                .nodeNo(nodeNo).nodeType(depth == 0 ? 1 : 2) // ROOT=1, CHAPTER=2
                .parentNodeId(parentId).depth(depth)
                .nodeCode(nodeCode).title(title).anchorText(title)
                .sectionPath(sectionPath).canonicalPath(canonicalPath)
                .contentText(contentText)
                .build();
    }

    // ==================== 1. 环境就绪 ====================

    @Test
    @Order(0)
    @DisplayName("环境就绪检查")
    void shouldPrepareEnvironment() {
        assertThat(esClient).isNotNull();
        assertThat(navIndexService).isNotNull();

        try {
            assertThat(esClient.ping().value()).isTrue();
        } catch (Exception e) {
            fail("ES ping 失败: " + e.getMessage());
        }
        log.info("=== 导航索引 Docker 集成测试环境就绪 ===");
    }

    // ==================== 2. 索引构建与搜索 ====================

    @Test
    @Order(1)
    @DisplayName("reindexDocumentNodes + searchSections 按标题搜索")
    void shouldReindexAndSearchByTitle() {
        // 准备 document
        Document doc = Document.builder()
                .id(DOC_ID).documentName("Payment API 文档").originalFileName("payment.pdf")
                .fileType(1).mediaType("application/pdf").fileSize(1024L)
                .storageType(1).bucketName("test").objectName("test/payment.pdf")
                .parseStatus(3).strategyStatus(1).indexStatus(3)
                .build();
        documentMapper.insert(doc);

        // 准备 structure nodes
        Long id1 = uidGenerator.getUid();
        Long id2 = uidGenerator.getUid();
        Long id3 = uidGenerator.getUid();

        DocumentStructureNode root = section(id1, 1, 0, null,
                "1", "Overview", "Payment API / Overview",
                "/payment/overview", "Payment API overview introduction.");
        DocumentStructureNode ch1 = section(id2, 2, 1, id1,
                "1.1", "Payment Terms", "Payment API / Payment Terms",
                "/payment/terms", "Payment terms: net 30 days, invoices monthly.");
        DocumentStructureNode ch2 = section(id3, 3, 1, id1,
                "1.2", "Refund Policy", "Payment API / Refund Policy",
                "/payment/refund", "Refund policy: 14-day money back guarantee.");

        for (DocumentStructureNode node : List.of(root, ch1, ch2)) {
            structureNodeMapper.insert(node);
        }

        // 索引到 ES
        List<DocumentStructureNode> nodes = List.of(root, ch1, ch2);
        navIndexService.reindexDocumentNodes(DOC_ID, TASK_ID, nodes);
        refreshEsIndex();

        // 搜索 — 按 title
        List<NavigationSectionHit> hits = navIndexService.searchSections(
                DOC_ID, "Payment Terms", null, null, null, 5);
        assertThat(hits).isNotEmpty();
        assertThat(hits.get(0).getTitle()).isEqualTo("Payment Terms");
        log.info("导航搜索命中: {} 条, top={} score={}",
                hits.size(), hits.get(0).getTitle(), hits.get(0).getScore());
    }

    // ==================== 3. 多维度搜索 ====================

    @Test
    @Order(2)
    @DisplayName("searchSections — topic / facet / question 多维度搜索")
    void shouldSearchByMultipleDimensions() {
        // 准备 document
        Document doc = Document.builder()
                .id(DOC_ID).documentName("安全策略文档").originalFileName("security.pdf")
                .fileType(1).mediaType("application/pdf").fileSize(1024L)
                .storageType(1).bucketName("test").objectName("test/security.pdf")
                .parseStatus(3).strategyStatus(1).indexStatus(3)
                .build();
        documentMapper.insert(doc);

        Long id1 = uidGenerator.getUid();
        Long id2 = uidGenerator.getUid();
        Long id3 = uidGenerator.getUid();

        DocumentStructureNode root = section(id1, 1, 0, null,
                "1", "Security", "Security Policy / Overview", "/security", "Security policy overview.");
        DocumentStructureNode ch1 = section(id2, 2, 1, id1,
                "2.1", "Access Control", "Security / Access Control",
                "/security/access", "Role-based access control policy and MFA requirements.");
        DocumentStructureNode ch2 = section(id3, 3, 1, id1,
                "2.2", "Data Protection", "Security / Data Protection",
                "/security/data", "Data encryption at rest and in transit. GDPR compliance.");

        for (DocumentStructureNode node : List.of(root, ch1, ch2)) {
            structureNodeMapper.insert(node);
        }

        List<DocumentStructureNode> nodes = List.of(root, ch1, ch2);
        navIndexService.reindexDocumentNodes(DOC_ID, TASK_ID, nodes);
        refreshEsIndex();

        // 按 topic 搜索
        List<NavigationSectionHit> topicHits = navIndexService.searchSections(
                DOC_ID, "Access Control", null, null, null, 5);
        assertThat(topicHits).isNotEmpty();
        assertThat(topicHits.get(0).getTitle()).isEqualTo("Access Control");

        // 按 content 搜索
        List<NavigationSectionHit> contentHits = navIndexService.searchSections(
                DOC_ID, null, null, "encryption", null, 5);
        assertThat(contentHits).isNotEmpty();
        assertThat(contentHits.get(0).getTitle()).isEqualTo("Data Protection");

        // 按 question 搜索
        List<NavigationSectionHit> questionHits = navIndexService.searchSections(
                DOC_ID, null, null, null, "What about MFA?", 5);
        assertThat(questionHits).isNotEmpty();
        // MFA 内容在 Access Control 章节
        log.info("Question search hit: {}", questionHits.get(0).getTitle());
    }

    // ==================== 4. 删除索引 ====================

    @Test
    @Order(3)
    @DisplayName("deleteByDocumentId — 按文档删除导航索引")
    void shouldDeleteByDocumentId() {
        // 准备
        Document doc = Document.builder()
                .id(DOC_ID).documentName("Warranty 文档").originalFileName("warranty.pdf")
                .fileType(1).mediaType("application/pdf").fileSize(1024L)
                .storageType(1).bucketName("test").objectName("test/warranty.pdf")
                .parseStatus(3).strategyStatus(1).indexStatus(3)
                .build();
        documentMapper.insert(doc);

        Long id1 = uidGenerator.getUid();
        DocumentStructureNode section = section(id1, 1, 0, null,
                "1", "Warranty", "Warranty / Overview", "/warranty",
                "Product warranty covers 12 months.");
        structureNodeMapper.insert(section);

        navIndexService.reindexDocumentNodes(DOC_ID, TASK_ID, List.of(section));
        refreshEsIndex();

        // 确认可搜索
        List<NavigationSectionHit> before = navIndexService.searchSections(
                DOC_ID, "Warranty", null, null, null, 5);
        assertThat(before).isNotEmpty();

        // 删除
        navIndexService.deleteByDocumentId(DOC_ID);

        // 确认已删除
        List<NavigationSectionHit> after = navIndexService.searchSections(
                DOC_ID, "Warranty", null, null, null, 5);
        assertThat(after).isEmpty();
    }

    // ==================== 5. 仅索引 SECTION 节点 ====================

    @Test
    @Order(4)
    @DisplayName("reindexDocumentNodes — 仅索引 SECTION 类型，跳过 STEP/LIST_ITEM")
    void shouldOnlyIndexSectionNodes() {
        Document doc = Document.builder()
                .id(DOC_ID).documentName("Guide 文档").originalFileName("guide.pdf")
                .fileType(1).mediaType("application/pdf").fileSize(1024L)
                .storageType(1).bucketName("test").objectName("test/guide.pdf")
                .parseStatus(3).strategyStatus(1).indexStatus(3)
                .build();
        documentMapper.insert(doc);

        Long sectionId = uidGenerator.getUid();
        Long stepId = uidGenerator.getUid();
        Long itemId = uidGenerator.getUid();

        DocumentStructureNode section = DocumentStructureNode.builder()
                .id(sectionId).documentId(DOC_ID).parseTaskId(TASK_ID)
                .nodeNo(1).nodeType(1).depth(0)  // ROOT
                .nodeCode("1").title("Guide").anchorText("Guide")
                .sectionPath("/guide").canonicalPath("/guide")
                .contentText("Setup guide.").build();

        DocumentStructureNode step = DocumentStructureNode.builder()
                .id(stepId).documentId(DOC_ID).parseTaskId(TASK_ID)
                .nodeNo(2).nodeType(3).parentNodeId(sectionId).depth(1)  // STEP
                .title("Step 1").contentText("Do this.").itemIndex(1).build();

        DocumentStructureNode listItem = DocumentStructureNode.builder()
                .id(itemId).documentId(DOC_ID).parseTaskId(TASK_ID)
                .nodeNo(3).nodeType(4).parentNodeId(sectionId).depth(1)  // LIST_ITEM
                .title("Item A").contentText("Requirement A.").itemIndex(1).build();

        for (DocumentStructureNode n : List.of(section, step, listItem)) {
            structureNodeMapper.insert(n);
        }

        navIndexService.reindexDocumentNodes(DOC_ID, TASK_ID,
                List.of(section, step, listItem));
        refreshEsIndex();

        // 应该只搜索到 1 个 SECTION
        List<NavigationSectionHit> hits = navIndexService.searchSections(
                DOC_ID, "Guide", null, null, null, 10);
        assertThat(hits).hasSize(1);
        assertThat(hits.get(0).getNodeCode()).isEqualTo("1");
    }
}
