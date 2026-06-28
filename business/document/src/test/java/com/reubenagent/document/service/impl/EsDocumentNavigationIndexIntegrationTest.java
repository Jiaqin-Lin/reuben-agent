package com.reubenagent.document.service.impl;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import co.elastic.clients.transport.rest_client.RestClientTransport;
import com.reubenagent.document.config.DocumentProperties;
import com.reubenagent.document.config.DocumentNavigationElasticsearchIndexInitializer;
import com.reubenagent.document.entity.DocumentStructureNode;
import com.reubenagent.document.enums.DocumentStructureNodeTypeEnum;
import com.reubenagent.document.model.es.NavigationSectionHit;
import org.apache.http.HttpHost;
import org.elasticsearch.client.RestClient;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 导航章节 ES 索引集成测试 —— 需要本地 Elasticsearch（localhost:9200）。
 *
 * <pre>docker compose up -d elasticsearch</pre>
 *
 * <p>用英文文本验证 reindex → searchSections 四维搜索 → deleteByDocumentId 全链路。</p>
 */
@DisplayName("导航章节 ES 索引集成测试 (真实 ES)")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class EsDocumentNavigationIndexIntegrationTest {

    private static final String TEST_INDEX = "reuben_test_navigation_integration";
    private static final Long DOC_ID = 7701L;
    private static final Long TASK_ID = 7801L;

    private ElasticsearchClient esClient;
    private DocumentProperties properties;
    private EsDocumentNavigationIndexService navigationService;

    @BeforeAll
    void setup() throws Exception {
        RestClient restClient = RestClient.builder(HttpHost.create("http://localhost:9200")).build();
        RestClientTransport transport = new RestClientTransport(restClient, new JacksonJsonpMapper());
        esClient = new ElasticsearchClient(transport);

        properties = new DocumentProperties();
        properties.getElasticsearch().setNavigationIndexName(TEST_INDEX);
        properties.getElasticsearch().setAnalyzer("standard");
        properties.getElasticsearch().setSearchAnalyzer("standard");

        // 用初始化器建索引（standard analyzer）
        ObjectProvider<ElasticsearchClient> provider = new ObjectProvider<>() {
            @Override public ElasticsearchClient getObject() { return esClient; }
            @Override public ElasticsearchClient getIfAvailable() { return esClient; }
            @Override public ElasticsearchClient getIfUnique() { return esClient; }
        };
        DocumentNavigationElasticsearchIndexInitializer initializer =
                new DocumentNavigationElasticsearchIndexInitializer(provider, properties);
        // 先删再建，保证干净
        try {
            esClient.indices().delete(d -> d.index(TEST_INDEX).ignoreUnavailable(true));
        } catch (Exception ignored) {
        }
        initializer.initialize();

        navigationService = new EsDocumentNavigationIndexService(provider, properties);
    }

    @AfterAll
    void teardown() throws Exception {
        try {
            esClient.indices().delete(d -> d.index(TEST_INDEX).ignoreUnavailable(true));
        } catch (Exception ignored) {
        }
        esClient._transport().close();
    }

    @BeforeEach
    void clearIndex() throws Exception {
        try {
            esClient.deleteByQuery(d -> d.index(TEST_INDEX).refresh(true)
                    .query(q -> q.matchAll(m -> m)));
        } catch (Exception ignored) {
        }
    }

    @AfterEach
    void refresh() throws Exception {
        esClient.indices().refresh(r -> r.index(TEST_INDEX));
    }

    private DocumentStructureNode section(long id, Long parent, Long prev, Long next,
                                          String code, String title, String sectionPath, String content, int depth) {
        return DocumentStructureNode.builder()
                .id(id).documentId(DOC_ID).parseTaskId(TASK_ID)
                .nodeNo((int) (id % 10000))
                .nodeType(DocumentStructureNodeTypeEnum.CHAPTER.getCode())
                .parentNodeId(parent).prevSiblingNodeId(prev).nextSiblingNodeId(next)
                .depth(depth).nodeCode(code).title(title).anchorText(code + " " + title)
                .canonicalPath("/doc/" + id).sectionPath(sectionPath).contentText(content)
                .build();
    }

    @Test
    @Order(1)
    @DisplayName("reindexDocumentNodes：章节入索引并可搜索")
    void shouldReindexAndSearch() throws Exception {
        List<DocumentStructureNode> nodes = List.of(
                section(201L, null, null, 202L, "1", "Overview", "Chapter 1 Overview",
                        "This chapter introduces the contract overview and scope", 1),
                section(202L, null, 201L, null, "2", "Payment Terms", "Chapter 2 Payment Terms",
                        "Payment terms and late penalty rules are described here", 1));
        navigationService.reindexDocumentNodes(DOC_ID, TASK_ID, nodes);
        esClient.indices().refresh(r -> r.index(TEST_INDEX));

        List<NavigationSectionHit> hits = navigationService.searchSections(
                DOC_ID, "payment", null, null, null, 10);
        assertThat(hits).isNotEmpty();
        assertThat(hits.get(0).getTitle()).isEqualTo("Payment Terms");
    }

    @Test
    @Order(2)
    @DisplayName("searchSections 四维：title/sectionPath/contentText 均可命中")
    void shouldSearchByMultipleDimensions() throws Exception {
        List<DocumentStructureNode> nodes = List.of(
                section(301L, null, null, null, "3", "Safety Policy", "Chapter 3 Safety Policy",
                        "Occupational safety and health compliance requirements", 1));
        navigationService.reindexDocumentNodes(DOC_ID, TASK_ID, nodes);
        esClient.indices().refresh(r -> r.index(TEST_INDEX));

        List<NavigationSectionHit> byTitle = navigationService.searchSections(
                DOC_ID, null, null, null, "Safety Policy", 5);
        assertThat(byTitle).isNotEmpty();

        List<NavigationSectionHit> byContent = navigationService.searchSections(
                DOC_ID, "occupational safety", null, null, null, 5);
        assertThat(byContent).isNotEmpty();
    }

    @Test
    @Order(3)
    @DisplayName("deleteByDocumentId 清空文档章节索引")
    void shouldDeleteByDocumentId() throws Exception {
        List<DocumentStructureNode> nodes = List.of(
                section(401L, null, null, null, "4", "Warranty", "Chapter 4 Warranty",
                        "Product warranty terms", 1));
        navigationService.reindexDocumentNodes(DOC_ID, TASK_ID, nodes);
        esClient.indices().refresh(r -> r.index(TEST_INDEX));
        assertThat(navigationService.searchSections(DOC_ID, "warranty", null, null, null, 5)).isNotEmpty();

        navigationService.deleteByDocumentId(DOC_ID);
        esClient.indices().refresh(r -> r.index(TEST_INDEX));
        assertThat(navigationService.searchSections(DOC_ID, "warranty", null, null, null, 5)).isEmpty();
    }
}
