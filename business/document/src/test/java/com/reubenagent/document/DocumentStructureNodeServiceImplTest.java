package com.reubenagent.document;

import com.reubenagent.document.entity.DocumentStructureNode;
import com.reubenagent.document.enums.DocumentStructureNodeTypeEnum;
import com.reubenagent.document.mapper.IDocumentStructureNodeMapper;
import com.reubenagent.document.model.DocumentIntermediateStructureNode;
import com.reubenagent.document.service.IDocumentStructureNodeService;
import com.reubenagent.framework.uid.UidGenerator;
import io.minio.MinioClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * {@link com.reubenagent.document.service.impl.DocumentStructureNodeServiceImpl#saveNodes}
 * MySQL 集成测试。
 *
 * <h3>前置</h3>
 * <pre>docker compose up -d mysql</pre>
 */
@SpringBootTest(classes = DocumentTestConfig.TestApp.class)
@ActiveProfiles("test")
@Import(DocumentTestConfig.TestMetaConfig.class)
@DisplayName("DocumentStructureNodeServiceImpl saveNodes 集成测试")
class DocumentStructureNodeServiceImplTest {

    @MockBean
    private UidGenerator uidGenerator;

    @MockBean
    private MinioClient minioClient;

    @Autowired
    private IDocumentStructureNodeService structureNodeService;

    @Autowired
    private IDocumentStructureNodeMapper structureNodeMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final AtomicLong uidSeq = new AtomicLong(1000);

    @BeforeEach
    void setUp() {
        DocumentTestSchema.dropTables(jdbcTemplate);
        DocumentTestSchema.createDocumentStructureNodeTable(jdbcTemplate);
        uidSeq.set(1000);
        when(uidGenerator.getUid()).thenAnswer(inv -> uidSeq.getAndAdd(100));
    }

    private List<DocumentIntermediateStructureNode> simpleTree() {
        DocumentIntermediateStructureNode root = DocumentIntermediateStructureNode.builder()
                .nodeNo(1).nodeType(DocumentStructureNodeTypeEnum.ROOT.getCode())
                .depth(0).title("文档根").canonicalPath("/document").sectionPath("文档")
                .build();

        DocumentIntermediateStructureNode chapter1 = DocumentIntermediateStructureNode.builder()
                .nodeNo(2).nodeType(DocumentStructureNodeTypeEnum.CHAPTER.getCode())
                .parentNodeNo(1).depth(1)
                .nodeCode("1").title("第一章").anchorText("1 第一章")
                .canonicalPath("/document/h1").sectionPath("第一章")
                .build();

        DocumentIntermediateStructureNode chapter2 = DocumentIntermediateStructureNode.builder()
                .nodeNo(3).nodeType(DocumentStructureNodeTypeEnum.CHAPTER.getCode())
                .parentNodeNo(1).prevSiblingNodeNo(2).depth(1)
                .nodeCode("2").title("第二章").anchorText("2 第二章")
                .canonicalPath("/document/h2").sectionPath("第二章")
                .build();

        chapter1.setNextSiblingNodeNo(3);

        List<DocumentIntermediateStructureNode> nodes = new ArrayList<>();
        nodes.add(root);
        nodes.add(chapter1);
        nodes.add(chapter2);
        return nodes;
    }

    @Test
    @DisplayName("正常替换 → 返回实体含正确 ID 和引用关系")
    void shouldPersistAndResolveReferences() {
        List<DocumentIntermediateStructureNode> candidates = simpleTree();

        List<DocumentStructureNode> result = structureNodeService.saveNodes(
                1L, 10L, candidates);

        assertThat(result).hasSize(3);

        // root (id=1000)
        DocumentStructureNode root = result.get(0);
        assertThat(root.getId()).isEqualTo(1000L);
        assertThat(root.getDocumentId()).isEqualTo(1L);
        assertThat(root.getParseTaskId()).isEqualTo(10L);
        assertThat(root.getNodeNo()).isEqualTo(1);
        assertThat(root.getNodeType()).isEqualTo(DocumentStructureNodeTypeEnum.ROOT.getCode());
        assertThat(root.getDepth()).isEqualTo(0);
        assertThat(root.getParentNodeId()).isNull();

        // chapter1 (id=1100) → parentNodeId=1000 (root), nextSiblingNodeId=1200
        DocumentStructureNode chapter1 = result.get(1);
        assertThat(chapter1.getId()).isEqualTo(1100L);
        assertThat(chapter1.getParentNodeId()).isEqualTo(1000L);
        assertThat(chapter1.getNextSiblingNodeId()).isEqualTo(1200L);

        // chapter2 (id=1200) → parentNodeId=1000 (root), prevSiblingNodeId=1100
        DocumentStructureNode chapter2 = result.get(2);
        assertThat(chapter2.getId()).isEqualTo(1200L);
        assertThat(chapter2.getParentNodeId()).isEqualTo(1000L);
        assertThat(chapter2.getPrevSiblingNodeId()).isEqualTo(1100L);

        // DB 中可查回
        List<DocumentStructureNode> fromDb = structureNodeService.listDocumentNodes(1L, 10L);
        assertThat(fromDb).hasSize(3);
    }

    @Test
    @DisplayName("重复调用 → 幂等替换，旧数据被清空")
    void shouldReplaceOnSecondCall() {
        structureNodeService.saveNodes(1L, 10L, simpleTree());

        DocumentIntermediateStructureNode single = DocumentIntermediateStructureNode.builder()
                .nodeNo(1).nodeType(DocumentStructureNodeTypeEnum.ROOT.getCode())
                .depth(0).title("新根").build();
        List<DocumentStructureNode> result = structureNodeService.saveNodes(
                1L, 10L, List.of(single));

        assertThat(result).hasSize(1);
        assertThat(structureNodeService.listDocumentNodes(1L, 10L)).hasSize(1);
    }

    @Test
    @DisplayName("documentId 为 null → 返回空列表")
    void shouldReturnEmptyForNullDocumentId() {
        List<DocumentStructureNode> result = structureNodeService.saveNodes(
                null, 10L, simpleTree());
        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("candidates 为空 → 返回空列表")
    void shouldReturnEmptyForEmptyList() {
        structureNodeService.saveNodes(1L, 10L, simpleTree());

        List<DocumentStructureNode> result = structureNodeService.saveNodes(
                1L, 10L, List.of());
        assertThat(result).isEmpty();
        assertThat(structureNodeService.listDocumentNodes(1L, 10L)).isEmpty();
    }

    @Test
    @DisplayName("candidates 含 null 元素 → 跳过")
    void shouldSkipNullCandidates() {
        List<DocumentIntermediateStructureNode> mixed = new ArrayList<>();
        mixed.add(simpleTree().get(0));
        mixed.add(null);

        List<DocumentStructureNode> result = structureNodeService.saveNodes(
                1L, 10L, mixed);
        assertThat(result).hasSize(1);
    }

    @Test
    @DisplayName("listDocumentNodes → 按 nodeNo 升序")
    void shouldListOrderedByNodeNo() {
        structureNodeService.saveNodes(1L, 10L, simpleTree());

        List<DocumentStructureNode> nodes = structureNodeService.listDocumentNodes(1L, 10L);
        assertThat(nodes).extracting(DocumentStructureNode::getNodeNo)
                .containsExactly(1, 2, 3);
    }
}
