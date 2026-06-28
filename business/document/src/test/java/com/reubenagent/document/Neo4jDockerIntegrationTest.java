package com.reubenagent.document;

import com.reubenagent.document.entity.Document;
import com.reubenagent.document.entity.DocumentStructureNode;
import com.reubenagent.document.mapper.IDocumentMapper;
import com.reubenagent.document.mapper.IDocumentStructureNodeMapper;
import com.reubenagent.document.model.graph.*;
import com.reubenagent.document.service.DocumentStructureGraphProjectionService;
import com.reubenagent.document.service.DocumentStructureGraphService;
import com.reubenagent.document.service.IDocumentStructureNodeService;
import com.reubenagent.document.service.IDocumentVectorGateway;
import com.reubenagent.document.service.impl.CompositeGraphAvailabilityCache;
import com.reubenagent.framework.uid.UidGenerator;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.*;
import org.neo4j.driver.Driver;
import org.neo4j.driver.Session;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.when;

/**
 * Neo4j 图数据库 Docker 集成测试 —— 真实 MySQL + Neo4j。
 *
 * <p>前置条件: docker compose up -d mysql neo4j</p>
 * <p>运行: mvn test -pl business/document -am
 *    -Dtest=Neo4jDockerIntegrationTest
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
                "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.elasticsearch.ElasticsearchRestClientAutoConfiguration,org.springframework.boot.autoconfigure.elasticsearch.ElasticsearchDataAutoConfiguration",
                "reuben.document.pgvector.enabled=false",
                "reuben.document.neo4j.enabled=true",
                "reuben.document.neo4j.uri=bolt://127.0.0.1:7687",
                "reuben.document.neo4j.username=neo4j",
                "reuben.document.neo4j.password=neo4j123",
                "reuben.document.neo4j.database=neo4j",
                "reuben.document.neo4j.query-timeout-seconds=10",
                "reuben.document.neo4j.max-connection-pool-size=5",
                "reuben.document.neo4j.connection-acquisition-timeout-ms=5000"
        })
@Import(DocumentTestConfig.TestMetaConfig.class)
@ActiveProfiles("docker")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("Neo4j Docker 集成测试")
class Neo4jDockerIntegrationTest {

    // ==================== Mock ====================

    @MockBean
    private UidGenerator uidGenerator;
    @MockBean
    private IDocumentVectorGateway documentVectorGateway;

    // ==================== Autowired ====================

    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private Driver documentManageNeo4jDriver;
    @Autowired
    private DocumentStructureGraphProjectionService projectionService;
    @Autowired
    private DocumentStructureGraphService graphService;        // @Primary → Composite
    @Autowired
    private IDocumentStructureNodeService structureNodeService;
    @Autowired
    private IDocumentMapper documentMapper;
    @Autowired
    private IDocumentStructureNodeMapper structureNodeMapper;

    private final AtomicLong idSeq = new AtomicLong(20000);

    // Test data
    private static final Long DOC_ID = 90001L;
    private static final Long TASK_ID = 91001L;

    // Node IDs
    private Long rootId;
    private Long ch1Id;
    private Long ch2Id;
    private Long ch3Id;
    private Long step1Id;
    private Long step2Id;
    private Long item1Id;

    // ==================== Lifecycle ====================

    @BeforeEach
    void setUp() {
        DocumentTestSchema.dropTables(jdbcTemplate);
        DocumentTestSchema.createAllTables(jdbcTemplate);
        when(uidGenerator.getUid()).thenAnswer(inv -> idSeq.incrementAndGet());

        // 清除 Composite 缓存（避免跨测试 stale cache）
        CompositeGraphAvailabilityCache.evict(DOC_ID);

        // 清理 Neo4j（如果上次测试残留）
        try (Session session = documentManageNeo4jDriver.session()) {
            session.run("MATCH (n) WHERE n.documentId = $docId DETACH DELETE n",
                    org.neo4j.driver.Values.parameters("docId", DOC_ID));
        } catch (Exception e) {
            log.debug("Neo4j 清理跳过: {}", e.getMessage());
        }
    }

    @AfterAll
    void cleanupNeo4j() {
        try (Session session = documentManageNeo4jDriver.session()) {
            session.run("MATCH (n) WHERE n.documentId = $docId DETACH DELETE n",
                    org.neo4j.driver.Values.parameters("docId", DOC_ID));
        } catch (Exception e) {
            log.warn("Neo4j 清理失败: {}", e.getMessage());
        }
    }

    // ==================== Helpers ====================

    private void seedDocumentAndNodes() {
        // Document
        Document doc = Document.builder()
                .id(DOC_ID).documentName("Neo4j 测试文档").originalFileName("test.pdf")
                .fileType(1).mediaType("application/pdf").fileSize(1024L)
                .storageType(1).bucketName("test").objectName("test/neo4j-test.pdf")
                .parseStatus(3).strategyStatus(1).indexStatus(3)
                .build();
        documentMapper.insert(doc);

        // Structure nodes: 树形结构
        // ROOT (nodeId=rootId)
        //   ├── CHAPTER 1.1 "Introduction" (nodeId=ch1Id) — prev=null, next=ch2Id
        //   │   ├── STEP (step1Id, itemIndex=1)
        //   │   └── STEP (step2Id, itemIndex=2)
        //   ├── CHAPTER 1.2 "Requirements" (nodeId=ch2Id) — prev=ch1Id, next=ch3Id
        //   │   └── LIST_ITEM (item1Id, itemIndex=1)
        //   └── CHAPTER 1.3 "Deployment" (nodeId=ch3Id) — prev=ch2Id, next=null

        rootId = uidGenerator.getUid();
        ch1Id = uidGenerator.getUid();
        ch2Id = uidGenerator.getUid();
        ch3Id = uidGenerator.getUid();
        step1Id = uidGenerator.getUid();
        step2Id = uidGenerator.getUid();
        item1Id = uidGenerator.getUid();

        DocumentStructureNode root = section(rootId, DOC_ID, TASK_ID, 1, 0, null, null, "1", "Overview",
                "Document Overview", "/1", "This is the document overview.");
        DocumentStructureNode ch1 = section(ch1Id, DOC_ID, TASK_ID, 2, 1, rootId, null, "1.1", "Introduction",
                "Introduction", "/1/1.1", "Introduction to the product.");
        DocumentStructureNode ch2 = section(ch2Id, DOC_ID, TASK_ID, 3, 1, rootId, null, "1.2", "Requirements",
                "System Requirements", "/1/1.2", "Hardware and software requirements.");
        DocumentStructureNode ch3 = section(ch3Id, DOC_ID, TASK_ID, 4, 1, rootId, null, "1.3", "Deployment",
                "Deployment Guide", "/1/1.3", "How to deploy the application.");

        // Set sibling links
        ch1.setNextSiblingNodeId(ch2Id);
        ch2.setPrevSiblingNodeId(ch1Id);
        ch2.setNextSiblingNodeId(ch3Id);
        ch3.setPrevSiblingNodeId(ch2Id);

        DocumentStructureNode step1 = item(step1Id, DOC_ID, TASK_ID, 5, 3, ch1Id, null, 1,
                "Step 1: Install", "First installation step");
        DocumentStructureNode step2 = item(step2Id, DOC_ID, TASK_ID, 6, 3, ch1Id, null, 2,
                "Step 2: Configure", "Second configuration step");

        // Item sibling links
        step1.setNextSiblingNodeId(step2Id);
        step2.setPrevSiblingNodeId(step1Id);

        DocumentStructureNode item1 = item(item1Id, DOC_ID, TASK_ID, 7, 4, ch2Id, null, 1,
                "Item: Hardware requirements", "At least 8GB RAM");

        // Insert into MySQL
        for (DocumentStructureNode node : List.of(root, ch1, ch2, ch3, step1, step2, item1)) {
            structureNodeMapper.insert(node);
        }

        log.info("种子结构节点已插入: 4 sections + 3 items");
    }

    private DocumentStructureNode section(Long id, Long docId, Long taskId, int nodeNo, int depth,
                                           Long parentId, String nodeCode, String code,
                                           String title, String anchorText,
                                           String canonicalPath, String contentText) {
        return DocumentStructureNode.builder()
                .id(id).documentId(docId).parseTaskId(taskId)
                .nodeNo(nodeNo).nodeType(depth == 0 ? 1 : 2)  // ROOT=1, CHAPTER=2
                .parentNodeId(parentId).depth(depth)
                .nodeCode(code).title(title).anchorText(anchorText)
                .canonicalPath(canonicalPath).sectionPath(canonicalPath)
                .contentText(contentText)
                .build();
    }

    private DocumentStructureNode item(Long id, Long docId, Long taskId, int nodeNo, int nodeType,
                                        Long parentId, Long prevSiblingId, int itemIndex,
                                        String title, String contentText) {
        return DocumentStructureNode.builder()
                .id(id).documentId(docId).parseTaskId(taskId)
                .nodeNo(nodeNo).nodeType(nodeType)  // STEP=3, LIST_ITEM=4
                .parentNodeId(parentId).prevSiblingNodeId(prevSiblingId)
                .depth(1)
                .title(title).anchorText(title).contentText(contentText)
                .itemIndex(itemIndex)
                .build();
    }

    // ==================== 1. 环境就绪 ====================

    @Test
    @Order(0)
    @DisplayName("环境就绪检查")
    void shouldPrepareEnvironment() {
        assertThat(documentManageNeo4jDriver).isNotNull();
        assertThat(projectionService).isNotNull();
        assertThat(graphService).isNotNull();

        // 验证 Neo4j 连通性
        try (Session session = documentManageNeo4jDriver.session()) {
            var result = session.run("RETURN 1 AS ok");
            assertThat(result.single().get("ok").asInt()).isEqualTo(1);
        }
        log.info("=== Neo4j Docker 集成测试环境就绪 ===");
    }

    // ==================== 2. 图投影 ====================

    @Test
    @Order(1)
    @DisplayName("图投影 — MySQL 结构节点 → Neo4j")
    void shouldProjectToGraph() {
        seedDocumentAndNodes();
        projectionService.projectToGraph(DOC_ID, TASK_ID);

        // 验证节点落库
        assertThat(graphService.isGraphAvailable(DOC_ID)).isTrue();

        List<GraphSection> sections = graphService.listSections(DOC_ID);
        assertThat(sections).hasSize(4); // 1 root + 3 chapters

        log.info("图投影成功: {} 个 Section 节点", sections.size());
    }

    // ==================== 3. 图查询 — 按 code/title/children ====================

    @Test
    @Order(2)
    @DisplayName("图查询 — findSectionByCode / listChildren / findSectionByTitle")
    void shouldQuerySections() {
        seedDocumentAndNodes();
        projectionService.projectToGraph(DOC_ID, TASK_ID);

        // 按 code 查
        GraphSection s11 = graphService.findSectionByCode(DOC_ID, "1.1");
        assertThat(s11).isNotNull();
        assertThat(s11.getTitle()).isEqualTo("Introduction");

        // 按 title 查
        GraphSection s12 = graphService.findSectionByTitle(DOC_ID, "Requirements");
        assertThat(s12).isNotNull();
        assertThat(s12.getNodeCode()).isEqualTo("1.2");

        // 查 children
        List<GraphSection> children = graphService.listChildren(DOC_ID, rootId);
        assertThat(children).hasSize(3);
        assertThat(children.get(0).getNodeCode()).isEqualTo("1.1");
        assertThat(children.get(1).getNodeCode()).isEqualTo("1.2");
        assertThat(children.get(2).getNodeCode()).isEqualTo("1.3");
    }

    // ==================== 4. 兄弟遍历 ====================

    @Test
    @Order(3)
    @DisplayName("兄弟遍历 — previousSibling / nextSibling")
    void shouldTraverseSiblings() {
        seedDocumentAndNodes();
        projectionService.projectToGraph(DOC_ID, TASK_ID);

        GraphSection ch1 = graphService.findSectionByCode(DOC_ID, "1.1");
        GraphSection ch2 = graphService.findSectionByCode(DOC_ID, "1.2");
        GraphSection ch3 = graphService.findSectionByCode(DOC_ID, "1.3");

        // ch1 的下一个兄弟是 ch2
        GraphSection nextOfCh1 = graphService.nextSibling(DOC_ID, ch1.getNodeId());
        assertThat(nextOfCh1).isNotNull();
        assertThat(nextOfCh1.getNodeCode()).isEqualTo("1.2");

        // ch2 的前一个兄弟是 ch1
        GraphSection prevOfCh2 = graphService.previousSibling(DOC_ID, ch2.getNodeId());
        assertThat(prevOfCh2).isNotNull();
        assertThat(prevOfCh2.getNodeCode()).isEqualTo("1.1");

        // ch2 的下一个兄弟是 ch3
        GraphSection nextOfCh2 = graphService.nextSibling(DOC_ID, ch2.getNodeId());
        assertThat(nextOfCh2).isNotNull();
        assertThat(nextOfCh2.getNodeCode()).isEqualTo("1.3");

        // ch3 没有下一个兄弟
        GraphSection nextOfCh3 = graphService.nextSibling(DOC_ID, ch3.getNodeId());
        assertThat(nextOfCh3).isNull();

        // ch1 没有前一个兄弟
        GraphSection prevOfCh1 = graphService.previousSibling(DOC_ID, ch1.getNodeId());
        assertThat(prevOfCh1).isNull();
    }

    // ==================== 5. Item 查询 ====================

    @Test
    @Order(4)
    @DisplayName("Item 查询 — listItems / findItemByIndex")
    void shouldQueryItems() {
        seedDocumentAndNodes();
        projectionService.projectToGraph(DOC_ID, TASK_ID);

        GraphSection ch1 = graphService.findSectionByCode(DOC_ID, "1.1");

        // 列出 items
        List<GraphItem> items = graphService.listItems(DOC_ID, ch1.getNodeId());
        assertThat(items).hasSize(2);

        // 按 index 查找
        GraphItem step1 = graphService.findItemByIndex(DOC_ID, ch1.getNodeId(), 1);
        assertThat(step1).isNotNull();
        assertThat(step1.getTitle()).contains("Install");

        GraphItem step2 = graphService.findItemByIndex(DOC_ID, ch1.getNodeId(), 2);
        assertThat(step2).isNotNull();
        assertThat(step2.getTitle()).contains("Configure");

        // 搜索 items
        List<GraphItem> found = graphService.searchItemsInSection(DOC_ID, ch1.getNodeId(), "Install");
        assertThat(found).hasSize(1);
        assertThat(found.get(0).getItemIndex()).isEqualTo(1);
    }

    // ==================== 6. findBestSection ====================

    @Test
    @Order(5)
    @DisplayName("findBestSection — 主题匹配评分")
    void shouldFindBestSection() {
        seedDocumentAndNodes();
        projectionService.projectToGraph(DOC_ID, TASK_ID);

        // 按主题匹配
        GraphSection best = graphService.findBestSection(DOC_ID, "deployment", null);
        assertThat(best).isNotNull();
        assertThat(best.getTitle()).isEqualTo("Deployment");

        // 按内容匹配
        GraphSection byContent = graphService.findBestSection(DOC_ID, "hardware", null);
        assertThat(byContent).isNotNull();
        assertThat(byContent.getTitle()).isEqualTo("Requirements");
    }

    // ==================== 7. Composite 路由 ====================

    @Test
    @Order(6)
    @DisplayName("CompositeDocumentStructureGraphService — Neo4j 路由")
    void shouldCompositeRouteToNeo4j() {
        seedDocumentAndNodes();
        projectionService.projectToGraph(DOC_ID, TASK_ID);

        // 通过 Composite（@Primary）调用，应该走 Neo4j
        assertThat(graphService.isGraphAvailable(DOC_ID)).isTrue();
        GraphSection section = graphService.findSectionByCode(DOC_ID, "1.2");
        assertThat(section).isNotNull();
        assertThat(section.getTitle()).isEqualTo("Requirements");
    }

    // ==================== 8. 删除文档 ====================

    @Test
    @Order(7)
    @DisplayName("deleteByDocumentId — 级联清理，Neo4j 内无节点残留")
    void shouldDeleteByDocumentId() {
        seedDocumentAndNodes();
        projectionService.projectToGraph(DOC_ID, TASK_ID);
        assertThat(graphService.isGraphAvailable(DOC_ID)).isTrue();
        assertThat(graphService.listSections(DOC_ID)).isNotEmpty();

        projectionService.deleteByDocumentId(DOC_ID);

        // 直接查 Neo4j 确认无节点（绕过 Composite 缓存）
        try (Session session = documentManageNeo4jDriver.session()) {
            var result = session.run(
                    "MATCH (s:Section {documentId: $docId}) RETURN count(s) AS cnt",
                    org.neo4j.driver.Values.parameters("docId", DOC_ID));
            long count = result.single().get("cnt").asLong();
            assertThat(count).isZero();
        }
    }

    // ==================== 9. parentSection 查询 ====================

    @Test
    @Order(8)
    @DisplayName("parentSection — 父章节查询")
    void shouldFindParentSection() {
        seedDocumentAndNodes();
        projectionService.projectToGraph(DOC_ID, TASK_ID);

        // 确认 ch1 存在
        GraphSection ch1 = graphService.findSectionByCode(DOC_ID, "1.1");
        assertThat(ch1).as("ch1 via code lookup").isNotNull();
        assertThat(ch1.getNodeId()).as("ch1 nodeId").isNotNull();

        // 确认 root 存在
        GraphSection root = graphService.findSectionByCode(DOC_ID, "1");
        assertThat(root).as("root via code lookup").isNotNull();

        // 确认 children 包含 ch1
        List<GraphSection> children = graphService.listChildren(DOC_ID, root.getNodeId());
        assertThat(children).as("root's children").isNotEmpty();
        assertThat(children.stream().anyMatch(c -> c.getNodeId().equals(ch1.getNodeId())))
                .as("ch1 is child of root").isTrue();

        // parentSection 查询
        GraphSection parent = graphService.parentSection(DOC_ID, ch1.getNodeId());
        assertThat(parent).as("parent of ch1").isNotNull();
        assertThat(parent.getNodeCode()).isEqualTo("1");
        assertThat(parent.getTitle()).isEqualTo("Overview");
    }
}
