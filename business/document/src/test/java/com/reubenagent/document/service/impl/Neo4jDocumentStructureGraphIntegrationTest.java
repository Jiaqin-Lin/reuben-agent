package com.reubenagent.document.service.impl;

import com.reubenagent.document.config.DocumentProperties;
import com.reubenagent.document.entity.DocumentStructureNode;
import com.reubenagent.document.enums.DocumentStructureNodeTypeEnum;
import com.reubenagent.document.model.graph.GraphItem;
import com.reubenagent.document.model.graph.GraphSection;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.neo4j.driver.AuthTokens;
import org.neo4j.driver.Config;
import org.neo4j.driver.Driver;
import org.neo4j.driver.GraphDatabase;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Neo4j 图投影 + 查询服务集成测试 —— 需要本地 Neo4j（bolt://127.0.0.1:7687）。
 *
 * <pre>docker compose up -d neo4j</pre>
 *
 * <p>用英文文本构建结构树，验证 projectToGraph → findSectionByCode/listChildren/siblings → deleteByDocumentId 全链路。</p>
 */
@DisplayName("Neo4j 图投影+查询集成测试 (真实 Neo4j)")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class Neo4jDocumentStructureGraphIntegrationTest {

    private static final String URI = "bolt://127.0.0.1:7687";
    private static final String USER = "neo4j";
    private static final String PASS = "neo4j123";
    private static final Long DOC_ID = 9001L;
    private static final Long TASK_ID = 9101L;

    private Driver driver;
    private DocumentProperties properties;
    private Neo4jDocumentStructureGraphProjectionService projectionService;
    private Neo4jDocumentStructureGraphService graphService;

    @BeforeAll
    void setup() {
        Config config = Config.builder()
                .withConnectionTimeout(5, TimeUnit.SECONDS)
                .withMaxConnectionPoolSize(5)
                .build();
        driver = GraphDatabase.driver(URI, AuthTokens.basic(USER, PASS), config);

        properties = new DocumentProperties();
        properties.getNeo4j().setDatabase("neo4j");

        projectionService = new Neo4jDocumentStructureGraphProjectionService(
                driver, properties, null, null, null);
        projectionService.initSchema();

        graphService = new Neo4jDocumentStructureGraphService(driver, properties);

        // 清理旧数据
        projectionService.deleteByDocumentId(DOC_ID);
    }

    @AfterAll
    void teardown() {
        try {
            projectionService.deleteByDocumentId(DOC_ID);
        } catch (Exception ignored) {
        }
        if (driver != null) {
            driver.close();
        }
    }

    private DocumentStructureNode node(long id, Integer nodeType, Long parent, Long prev, Long next,
                                       String code, String title, String sectionPath, String content,
                                       Integer depth, Integer itemIndex) {
        return DocumentStructureNode.builder()
                .id(id).documentId(DOC_ID).parseTaskId(TASK_ID)
                .nodeNo((int) (id % 10000)).nodeType(nodeType)
                .parentNodeId(parent).prevSiblingNodeId(prev).nextSiblingNodeId(next)
                .depth(depth).nodeCode(code).title(title).anchorText(code + " " + title)
                .canonicalPath("/doc/" + id).sectionPath(sectionPath).contentText(content)
                .itemIndex(itemIndex)
                .build();
    }

    @Test
    @Order(1)
    @DisplayName("projectToGraph：章节+条目+关系落 Neo4j")
    void shouldProjectToGraph() {
        // ROOT > CHAPTER1 > (STEP1, STEP2) ; CHAPTER1 -[NEXT_SIBLING]-> CHAPTER2
        DocumentStructureNode root = node(101L, DocumentStructureNodeTypeEnum.ROOT.getCode(),
                null, null, null, "root", "Document Root", "Root", "root content", 0, null);
        DocumentStructureNode ch1 = node(102L, DocumentStructureNodeTypeEnum.CHAPTER.getCode(),
                101L, null, 103L, "1", "Overview", "Chapter 1 Overview", "overview body", 1, null);
        DocumentStructureNode ch2 = node(103L, DocumentStructureNodeTypeEnum.CHAPTER.getCode(),
                101L, 102L, null, "2", "Requirements", "Chapter 2 Requirements", "requirements body", 1, null);
        DocumentStructureNode step1 = node(104L, DocumentStructureNodeTypeEnum.STEP.getCode(),
                102L, null, 105L, "1.1", "Step One", "Chapter 1 Overview > Step One",
                "do the first thing", 2, 1);
        DocumentStructureNode step2 = node(105L, DocumentStructureNodeTypeEnum.STEP.getCode(),
                102L, 104L, null, "1.2", "Step Two", "Chapter 1 Overview > Step Two",
                "do the second thing", 2, 2);

        projectionService.projectToGraph(DOC_ID, TASK_ID, "Test Document",
                List.of(root, ch1, ch2, step1, step2));

        assertThat(graphService.isGraphAvailable(DOC_ID)).isTrue();
        assertThat(graphService.listSections(DOC_ID)).hasSize(3);
    }

    @Test
    @Order(2)
    @DisplayName("findSectionByCode / listChildren / parentSection")
    void shouldQuerySections() {
        GraphSection ch1 = graphService.findSectionByCode(DOC_ID, "1");
        assertThat(ch1).isNotNull();
        assertThat(ch1.getTitle()).isEqualTo("Overview");

        List<GraphSection> children = graphService.listChildren(DOC_ID, ch1.getNodeId());
        assertThat(children).isEmpty();

        GraphSection root = graphService.findSectionByCode(DOC_ID, "root");
        assertThat(root).isNotNull();
        List<GraphSection> rootChildren = graphService.listChildren(DOC_ID, root.getNodeId());
        assertThat(rootChildren).hasSize(2);
    }

    @Test
    @Order(3)
    @DisplayName("previousSibling / nextSibling 链表遍历")
    void shouldTraverseSiblings() {
        GraphSection ch1 = graphService.findSectionByCode(DOC_ID, "1");
        GraphSection next = graphService.nextSibling(DOC_ID, ch1.getNodeId());
        assertThat(next).isNotNull();
        assertThat(next.getTitle()).isEqualTo("Requirements");

        GraphSection back = graphService.previousSibling(DOC_ID, next.getNodeId());
        assertThat(back).isNotNull();
        assertThat(back.getTitle()).isEqualTo("Overview");

        // 末项 nextSibling 为 null
        assertThat(graphService.nextSibling(DOC_ID, next.getNodeId())).isNull();
    }

    @Test
    @Order(4)
    @DisplayName("listItems / findItemByIndex 条目查询")
    void shouldQueryItems() {
        GraphSection ch1 = graphService.findSectionByCode(DOC_ID, "1");
        List<GraphItem> items = graphService.listItems(DOC_ID, ch1.getNodeId());
        assertThat(items).hasSize(2);

        GraphItem first = graphService.findItemByIndex(DOC_ID, ch1.getNodeId(), 1);
        assertThat(first).isNotNull();
        assertThat(first.getContentText()).isEqualTo("do the first thing");
    }

    @Test
    @Order(5)
    @DisplayName("findBestSection 按主题打分定位")
    void shouldFindBestSection() {
        GraphSection best = graphService.findBestSection(DOC_ID, "requirements", null);
        assertThat(best).isNotNull();
        assertThat(best.getTitle()).isEqualTo("Requirements");
    }

    @Test
    @Order(6)
    @DisplayName("deleteByDocumentId 清空图数据")
    void shouldDeleteByDocumentId() {
        projectionService.deleteByDocumentId(DOC_ID);
        assertThat(graphService.isGraphAvailable(DOC_ID)).isFalse();
        assertThat(graphService.listSections(DOC_ID)).isEmpty();
    }
}
