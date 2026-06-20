package com.reubenagent.document.service.impl;

import com.reubenagent.document.DocumentTestConfig;
import com.reubenagent.document.DocumentTestSchema;
import com.reubenagent.document.entity.Document;
import com.reubenagent.document.entity.DocumentChunk;
import com.reubenagent.document.entity.DocumentParentBlock;
import com.reubenagent.document.entity.DocumentStrategyPlan;
import com.reubenagent.document.entity.DocumentStrategyStep;
import com.reubenagent.document.entity.DocumentTask;
import com.reubenagent.document.entity.DocumentTaskLog;
import com.reubenagent.document.enums.DocumentChunkSourceTypeEnum;
import com.reubenagent.document.enums.DocumentIndexStatusEnum;
import com.reubenagent.document.enums.DocumentParseStatusEnum;
import com.reubenagent.document.enums.DocumentPlanStatusEnum;
import com.reubenagent.document.enums.DocumentStrategyExecuteStatusEnum;
import com.reubenagent.document.enums.DocumentStrategyStatusEnum;
import com.reubenagent.document.enums.DocumentTaskEventTypeEnum;
import com.reubenagent.document.enums.DocumentTaskStageEnum;
import com.reubenagent.document.enums.DocumentTaskStatusEnum;
import com.reubenagent.document.enums.DocumentTaskTypeEnum;
import com.reubenagent.document.enums.DocumentVectorStatusEnum;
import com.reubenagent.document.mapper.IDocumentChunkMapper;
import com.reubenagent.document.mapper.IDocumentMapper;
import com.reubenagent.document.mapper.IDocumentParentBlockMapper;
import com.reubenagent.document.mapper.IDocumentStrategyPlanMapper;
import com.reubenagent.document.mapper.IDocumentStrategyStepMapper;
import com.reubenagent.document.mapper.IDocumentTaskLogMapper;
import com.reubenagent.document.mapper.IDocumentTaskMapper;
import com.reubenagent.document.model.ChunkCandidate;
import com.reubenagent.document.model.DocumentParseResult;
import com.reubenagent.document.model.DocumentStrategyPlanDraft;
import com.reubenagent.document.model.DocumentStrategyStepDraft;
import com.reubenagent.document.model.DocumentVectorizationResult;
import com.reubenagent.document.model.ParentBlockCandidate;
import com.reubenagent.document.service.IDocumentParseResultService;
import com.reubenagent.document.service.IDocumentProfileService;
import com.reubenagent.document.service.keyword.IDocumentKeywordSearchGateway;
import com.reubenagent.document.service.IDocumentStorageService;
import com.reubenagent.document.service.IDocumentStrategyService;
import com.reubenagent.document.service.IDocumentStructureNodeService;
import com.reubenagent.document.service.IDocumentVectorGateway;
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
import org.springframework.context.annotation.Import;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;

@SpringBootTest(classes = DocumentTestConfig.TestApp.class)
@ActiveProfiles("test")
@Import(DocumentTestConfig.TestMetaConfig.class)
@DisplayName("DocumentAsyncProcessServiceImpl 集成测试")
class DocumentAsyncProcessServiceImplTest {

    @Autowired
    private DocumentAsyncProcessServiceImpl asyncProcessService;

    @Autowired
    private IDocumentMapper documentMapper;
    @Autowired
    private IDocumentTaskMapper taskMapper;
    @Autowired
    private IDocumentTaskLogMapper taskLogMapper;
    @Autowired
    private IDocumentStrategyPlanMapper strategyPlanMapper;
    @Autowired
    private IDocumentStrategyStepMapper strategyStepMapper;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockBean
    private UidGenerator uidGenerator;
    @MockBean
    private io.minio.MinioClient minioClient;
    @MockBean
    private IDocumentStorageService storageService;
    @MockBean
    private IDocumentParseResultService parseResultService;
    @MockBean
    private IDocumentStructureNodeService structureNodeService;
    @MockBean
    private IDocumentProfileService profileService;
    @MockBean
    private IDocumentStrategyService strategyService;
    @MockBean
    private IDocumentVectorGateway vectorGateway;
    @MockBean
    private IDocumentKeywordSearchGateway keywordSearchGateway;

    @Autowired
    private IDocumentParentBlockMapper parentBlockMapper;
    @Autowired
    private IDocumentChunkMapper chunkMapper;

    private final AtomicLong uidSeq = new AtomicLong(1000);

    @BeforeEach
    void setUp() {
        DocumentTestSchema.dropTables(jdbcTemplate);
        DocumentTestSchema.createAllTables(jdbcTemplate);

        when(uidGenerator.getUid()).thenAnswer(inv -> uidSeq.getAndAdd(10));
    }

    // ============ 测试数据工厂 ============

    private Document createDocument(Long id, String name, int fileType) {
        Document doc = Document.builder()
                .id(id)
                .documentName(name)
                .originalFileName(name)
                .fileType(fileType)
                .mediaType("text/plain")
                .fileSize(1024L)
                .storageType(1)
                .bucketName("test-bucket")
                .objectName("test/" + name)
                .parseStatus(DocumentParseStatusEnum.WAIT_TO_PARSE.getCode())
                .strategyStatus(DocumentStrategyStatusEnum.WAIT_TO_RECOMMEND.getCode())
                .build();
        documentMapper.insert(doc);
        return doc;
    }

    private DocumentTask createTask(Long id, Long documentId, int taskType) {
        DocumentTask task = DocumentTask.builder()
                .id(id)
                .documentId(documentId)
                .taskType(taskType)
                .taskStatus(DocumentTaskStatusEnum.NEW.getCode())
                .currentStage(DocumentTaskStageEnum.FILE_UPLOAD.getCode())
                .triggerSource(1)
                .retryCount(0)
                .build();
        taskMapper.insert(task);
        return task;
    }

    private DocumentParseResult createParseResult() {
        return DocumentParseResult.builder()
                .parsedText("解析后的纯文本内容")
                .charCount(5000)
                .tokenCount(1200)
                .structureLevel(3)
                .contentQualityLevel(4)
                .headingCount(10)
                .paragraphCount(25)
                .maxParagraphLength(500)
                .structureNodes(List.of())
                .build();
    }

    private void mockParseSuccess(DocumentParseResult result) {
        when(storageService.downloadObject(anyString())).thenReturn(new byte[]{1, 2, 3});
        when(parseResultService.parse(any(), anyString(), anyString(), any())).thenReturn(result);
        when(storageService.uploadParsedText(anyLong(), anyString())).thenReturn("parsed-text/doc-1.txt");
        when(structureNodeService.saveNodes(anyLong(), anyLong(), any())).thenReturn(List.of());
    }

    private DocumentStrategyPlanDraft createPlanDraft() {
        DocumentStrategyStepDraft parentStep = DocumentStrategyStepDraft.builder()
                .pipelineType("PARENT")
                .strategyType(1)
                .strategyRole(1)
                .sourceType(1)
                .recommendReason("结构化程度高")
                .build();
        DocumentStrategyStepDraft child1 = DocumentStrategyStepDraft.builder()
                .pipelineType("CHILD")
                .strategyType(3)
                .strategyRole(1)
                .sourceType(1)
                .recommendReason("语义边界清晰")
                .build();
        DocumentStrategyStepDraft child2 = DocumentStrategyStepDraft.builder()
                .pipelineType("CHILD")
                .strategyType(2)
                .strategyRole(3)
                .sourceType(1)
                .recommendReason("递归兜底")
                .build();
        return DocumentStrategyPlanDraft.builder()
                .strategySnapshot("PARENT:1;CHILD:3,2")
                .recommendReason("结构化程度高；语义边界清晰；递归兜底")
                .parentSteps(List.of(parentStep))
                .childSteps(List.of(child1, child2))
                .build();
    }

    // ============ 索引构建工厂方法 ============

    private DocumentStrategyPlan createPlan(Long id, Long documentId) {
        DocumentStrategyPlan plan = DocumentStrategyPlan.builder()
                .id(id)
                .documentId(documentId)
                .planVersion(1)
                .planSource(1)
                .planStatus(DocumentPlanStatusEnum.CONFIRMED.getCode())
                .strategyCount(3)
                .strategySnapshot("PARENT:1;CHILD:3,2")
                .recommendReason("测试方案")
                .build();
        strategyPlanMapper.insert(plan);
        return plan;
    }

    private void createStep(Long id, Long planId, Long documentId, int stepNo,
                            String pipelineType, int strategyType, int strategyRole) {
        DocumentStrategyStep step = DocumentStrategyStep.builder()
                .id(id)
                .planId(planId)
                .documentId(documentId)
                .stepNo(stepNo)
                .pipelineType(pipelineType)
                .strategyType(strategyType)
                .strategyRole(strategyRole)
                .sourceType(1)
                .executeStatus(DocumentStrategyExecuteStatusEnum.WAIT_EXECUTE.getCode())
                .recommendReason("测试推荐")
                .build();
        strategyStepMapper.insert(step);
    }

    private ParentBlockCandidate createParentCandidate(String sectionPath, String text,
                                                        List<ChunkCandidate> children) {
        return ParentBlockCandidate.builder()
                .sectionPath(sectionPath)
                .text(text)
                .sourceType(DocumentChunkSourceTypeEnum.ORIGINAL.getCode())
                .childChunks(children != null ? children : List.of())
                .build();
    }

    private ChunkCandidate createChildCandidate(String text) {
        return ChunkCandidate.builder()
                .text(text)
                .sourceType(DocumentChunkSourceTypeEnum.ORIGINAL.getCode())
                .build();
    }

    // ============ 正常流程 ============

    @Nested
    @DisplayName("完整解析管道")
    class HappyPath {

        @Test
        @DisplayName("正常文档 → parseStatus=SUCCESS, task=SUCCESS, plan 已持久化")
        void shouldCompleteFullPipeline() {
            Document doc = createDocument(1L, "test.pdf", 1);
            DocumentTask task = createTask(10L, 1L, 1);
            DocumentParseResult parseResult = createParseResult();
            mockParseSuccess(parseResult);
            when(strategyService.recommendStrategy(any(), any())).thenReturn(createPlanDraft());

            asyncProcessService.handleParseStrategyRoute(doc.getId(), task.getId());

            // 验证 document 状态
            Document updated = documentMapper.selectById(doc.getId());
            assertThat(updated.getParseStatus()).isEqualTo(DocumentParseStatusEnum.PARSE_SUCCESS.getCode());
            assertThat(updated.getStrategyStatus()).isEqualTo(DocumentStrategyStatusEnum.RECOMMENDED.getCode());
            assertThat(updated.getCharCount()).isEqualTo(5000);
            assertThat(updated.getTokenCount()).isEqualTo(1200);
            assertThat(updated.getStructureLevel()).isEqualTo(3);
            assertThat(updated.getContentQualityLevel()).isEqualTo(4);
            assertThat(updated.getParseSuccessTextPath()).isEqualTo("parsed-text/doc-1.txt");
            assertThat(updated.getParseErrorMsg()).isNull();
            assertThat(updated.getCurrentStrategyPlanId()).isNotNull();
            assertThat(updated.getLatestParseTaskId()).isEqualTo(task.getId());

            // 验证 task 状态
            DocumentTask updatedTask = taskMapper.selectById(task.getId());
            assertThat(updatedTask.getTaskStatus()).isEqualTo(DocumentTaskStatusEnum.SUCCESS.getCode());
            assertThat(updatedTask.getCurrentStage()).isEqualTo(DocumentTaskStageEnum.STRATEGY_ROUTE.getCode());
            assertThat(updatedTask.getFinishTime()).isNotNull();
            assertThat(updatedTask.getCostMillis()).isNotNull().isPositive();
            assertThat(updatedTask.getErrorCode()).isNull();
            assertThat(updatedTask.getErrorMsg()).isNull();

            // 验证 plan 已持久化
            List<DocumentStrategyPlan> plans = strategyPlanMapper.selectList(null);
            assertThat(plans).hasSize(1);
            DocumentStrategyPlan plan = plans.get(0);
            assertThat(plan.getDocumentId()).isEqualTo(doc.getId());
            assertThat(plan.getStrategySnapshot()).isEqualTo("PARENT:1;CHILD:3,2");
            assertThat(plan.getStrategyCount()).isEqualTo(3);
            assertThat(plan.getPlanVersion()).isEqualTo(1);

            // 验证 steps 已持久化（1 parent + 2 child）
            List<DocumentStrategyStep> steps = strategyStepMapper.selectList(null);
            assertThat(steps).hasSize(3);

            // 验证 taskLog: START + RECOMMEND_STRATEGY + COMPLETE = 3 条
            List<DocumentTaskLog> logs = taskLogMapper.selectList(null);
            assertThat(logs).hasSize(3);
            assertThat(logs).anyMatch(l -> l.getEventType().equals(DocumentTaskEventTypeEnum.START.getCode()));
            assertThat(logs).anyMatch(l -> l.getEventType().equals(DocumentTaskEventTypeEnum.RECOMMEND_STRATEGY.getCode()));
            assertThat(logs).anyMatch(l -> l.getEventType().equals(DocumentTaskEventTypeEnum.COMPLETE.getCode()));
        }

        @Test
        @DisplayName("策略方案版本号递增")
        void shouldIncrementPlanVersion() {
            Document doc = createDocument(2L, "doc2.pdf", 1);
            DocumentParseResult parseResult = createParseResult();
            mockParseSuccess(parseResult);
            when(strategyService.recommendStrategy(any(), any())).thenReturn(createPlanDraft());

            // 第一次
            DocumentTask task1 = createTask(20L, 2L, 1);
            asyncProcessService.handleParseStrategyRoute(doc.getId(), task1.getId());
            // 第二次
            DocumentTask task2 = createTask(30L, 2L, 1);
            asyncProcessService.handleParseStrategyRoute(doc.getId(), task2.getId());

            List<DocumentStrategyPlan> plans = strategyPlanMapper.selectList(null);
            assertThat(plans).hasSize(2);
            assertThat(plans).extracting(DocumentStrategyPlan::getPlanVersion)
                    .containsExactly(1, 2);
        }
    }

    // ============ 异常流程 ============

    @Nested
    @DisplayName("异常处理")
    class ErrorHandling {

        @Test
        @DisplayName("文档或任务不存在时静默返回")
        void shouldReturnSilentlyWhenDocumentOrTaskNotFound() {
            // 不创建任何 document/task
            asyncProcessService.handleParseStrategyRoute(999L, 999L);

            // 不应有任何记录写入
            List<DocumentTaskLog> logs = taskLogMapper.selectList(null);
            assertThat(logs).isEmpty();
        }

        @Test
        @DisplayName("解析异常 → document.parseStatus=FAIL, task.status=FAILED")
        void shouldHandleParseException() {
            Document doc = createDocument(3L, "bad.pdf", 1);
            DocumentTask task = createTask(40L, 3L, 1);

            when(storageService.downloadObject(anyString())).thenReturn(new byte[]{1, 2, 3});
            when(parseResultService.parse(any(), anyString(), anyString(), any()))
                    .thenThrow(new RuntimeException("Tika 解析失败"));

            asyncProcessService.handleParseStrategyRoute(doc.getId(), task.getId());

            // 验证 document
            Document updated = documentMapper.selectById(doc.getId());
            assertThat(updated.getParseStatus()).isEqualTo(DocumentParseStatusEnum.PARSE_FAIL.getCode());
            assertThat(updated.getParseErrorMsg()).isEqualTo("Tika 解析失败");

            // 验证 task
            DocumentTask updatedTask = taskMapper.selectById(task.getId());
            assertThat(updatedTask.getTaskStatus()).isEqualTo(DocumentTaskStatusEnum.FAILED.getCode());
            assertThat(updatedTask.getErrorCode()).isEqualTo("TASK_FAILED");
            assertThat(updatedTask.getErrorMsg()).isEqualTo("Tika 解析失败");
            assertThat(updatedTask.getFinishTime()).isNotNull();
            assertThat(updatedTask.getCostMillis()).isNotNull().isPositive();
        }

        @Test
        @DisplayName("策略推荐异常 → document/task 均标记失败")
        void shouldHandleStrategyException() {
            Document doc = createDocument(4L, "strategy-fail.pdf", 1);
            DocumentTask task = createTask(50L, 4L, 1);
            DocumentParseResult parseResult = createParseResult();
            mockParseSuccess(parseResult);
            when(strategyService.recommendStrategy(any(), any()))
                    .thenThrow(new RuntimeException("策略推荐超时"));

            asyncProcessService.handleParseStrategyRoute(doc.getId(), task.getId());

            Document updated = documentMapper.selectById(doc.getId());
            assertThat(updated.getParseStatus()).isEqualTo(DocumentParseStatusEnum.PARSE_FAIL.getCode());
            assertThat(updated.getParseErrorMsg()).contains("策略推荐超时");

            DocumentTask updatedTask = taskMapper.selectById(task.getId());
            assertThat(updatedTask.getTaskStatus()).isEqualTo(DocumentTaskStatusEnum.FAILED.getCode());
        }

        @Test
        @DisplayName("MinIO 下载异常 → 标记失败")
        void shouldHandleStorageException() {
            Document doc = createDocument(5L, "storage-fail.txt", 4);
            DocumentTask task = createTask(60L, 5L, 1);

            when(storageService.downloadObject(anyString()))
                    .thenThrow(new RuntimeException("MinIO 连接超时"));

            asyncProcessService.handleParseStrategyRoute(doc.getId(), task.getId());

            Document updated = documentMapper.selectById(doc.getId());
            assertThat(updated.getParseStatus()).isEqualTo(DocumentParseStatusEnum.PARSE_FAIL.getCode());
            assertThat(updated.getParseErrorMsg()).contains("MinIO 连接超时");
        }
    }

    // ============ 状态流转验证 ============

    @Nested
    @DisplayName("状态流转")
    class StateTransitions {

        @Test
        @DisplayName("task.currentStage 依次经过 CONTENT_PARSE → STRATEGY_ROUTE")
        void shouldTransitionThroughCorrectStages() {
            Document doc = createDocument(6L, "stages.pdf", 1);
            DocumentTask task = createTask(70L, 6L, 1);
            DocumentParseResult parseResult = createParseResult();
            mockParseSuccess(parseResult);
            when(strategyService.recommendStrategy(any(), any())).thenReturn(createPlanDraft());

            asyncProcessService.handleParseStrategyRoute(doc.getId(), task.getId());

            DocumentTask updatedTask = taskMapper.selectById(task.getId());
            assertThat(updatedTask.getCurrentStage()).isEqualTo(DocumentTaskStageEnum.STRATEGY_ROUTE.getCode());
        }

        @Test
        @DisplayName("taskLog 事件顺序正确: START → COMPLETE → RECOMMEND_STRATEGY")
        void shouldLogEventsInOrder() {
            Document doc = createDocument(7L, "log-order.pdf", 1);
            DocumentTask task = createTask(80L, 7L, 1);
            DocumentParseResult parseResult = createParseResult();
            mockParseSuccess(parseResult);
            when(strategyService.recommendStrategy(any(), any())).thenReturn(createPlanDraft());

            asyncProcessService.handleParseStrategyRoute(doc.getId(), task.getId());

            List<DocumentTaskLog> logs = taskLogMapper.selectList(null);
            assertThat(logs).hasSize(3);
            assertThat(logs.get(0).getEventType()).isEqualTo(DocumentTaskEventTypeEnum.START.getCode());
            assertThat(logs.get(1).getEventType()).isEqualTo(DocumentTaskEventTypeEnum.COMPLETE.getCode());
            assertThat(logs.get(2).getEventType()).isEqualTo(DocumentTaskEventTypeEnum.RECOMMEND_STRATEGY.getCode());
        }
    }

    // ============ 索引构建测试 ============

    @Nested
    @DisplayName("索引构建管道")
    class IndexBuildPipeline {

        @Test
        @DisplayName("正常流程 → document.indexStatus=BUILD_SUCCESS, task=SUCCESS, plan=EXECUTED")
        void shouldCompleteFullIndexBuildPipeline() {
            // 准备：文档（已解析，有 parseSuccessTextPath）
            Document doc = Document.builder()
                    .id(1L)
                    .documentName("test.pdf")
                    .originalFileName("test.pdf")
                    .fileType(1)
                    .mediaType("text/plain")
                    .fileSize(1024L)
                    .storageType(1)
                    .bucketName("test-bucket")
                    .objectName("test/test.pdf")
                    .parseStatus(DocumentParseStatusEnum.PARSE_SUCCESS.getCode())
                    .strategyStatus(DocumentStrategyStatusEnum.RECOMMENDED.getCode())
                    .indexStatus(DocumentIndexStatusEnum.WAIT_TO_BUILD.getCode())
                    .parseSuccessTextPath("parsed-text/doc-1.txt")
                    .build();
            documentMapper.insert(doc);

            // 准备：索引任务
            DocumentTask task = DocumentTask.builder()
                    .id(10L)
                    .documentId(doc.getId())
                    .taskType(DocumentTaskTypeEnum.BUILD_INDEX.getCode())
                    .taskStatus(DocumentTaskStatusEnum.NEW.getCode())
                    .currentStage(DocumentTaskStageEnum.STRATEGY_CONFIRM.getCode())
                    .triggerSource(1)
                    .retryCount(0)
                    .strategyPlanId(100L)
                    .build();
            taskMapper.insert(task);

            // 准备：已确认的方案 + 步骤
            DocumentStrategyPlan plan = createPlan(100L, doc.getId());
            createStep(110L, plan.getId(), doc.getId(), 1, "PARENT", 1, 1);
            createStep(120L, plan.getId(), doc.getId(), 2, "CHILD", 3, 1);
            createStep(130L, plan.getId(), doc.getId(), 3, "CHILD", 2, 3);

            // Mock：下载解析文本
            when(storageService.downloadObject("parsed-text/doc-1.txt"))
                    .thenReturn("第一章\n内容段落A\n第二章\n内容段落B\n".getBytes());

            // Mock：策略引擎产出 2 个 parent，各含 2 个 child
            ChunkCandidate c1a = createChildCandidate("内容段落A-1");
            ChunkCandidate c1b = createChildCandidate("内容段落A-2");
            ChunkCandidate c2a = createChildCandidate("内容段落B-1");
            ChunkCandidate c2b = createChildCandidate("内容段落B-2");
            ParentBlockCandidate p1 = createParentCandidate("第一章", "第一章\n内容段落A\n", List.of(c1a, c1b));
            ParentBlockCandidate p2 = createParentCandidate("第二章", "第二章\n内容段落B\n", List.of(c2a, c2b));
            when(strategyService.buildParentBlocks(any(), any(), any(), anyString()))
                    .thenReturn(List.of(p1, p2));

            // Mock：向量化全部成功
            when(vectorGateway.vectorize(any())).thenAnswer(inv -> {
                List<DocumentChunk> chunks = inv.getArgument(0);
                List<Long> allIds = chunks.stream().map(DocumentChunk::getId).toList();
                return DocumentVectorizationResult.builder()
                        .totalCount(chunks.size())
                        .successCount(chunks.size())
                        .failedCount(0)
                        .successChunkIds(allIds)
                        .failedChunkIds(List.of())
                        .build();
            });

            // 执行
            asyncProcessService.handleIndexBuild(doc.getId(), task.getId(), plan.getId());

            // 验证 document
            Document updatedDoc = documentMapper.selectById(doc.getId());
            assertThat(updatedDoc.getIndexStatus()).isEqualTo(DocumentIndexStatusEnum.BUILD_SUCCESS.getCode());
            assertThat(updatedDoc.getLatestIndexTaskId()).isEqualTo(task.getId());

            // 验证 task
            DocumentTask updatedTask = taskMapper.selectById(task.getId());
            assertThat(updatedTask.getTaskStatus()).isEqualTo(DocumentTaskStatusEnum.SUCCESS.getCode());
            assertThat(updatedTask.getCurrentStage()).isEqualTo(DocumentTaskStageEnum.STORE_COMPLETE.getCode());
            assertThat(updatedTask.getFinishTime()).isNotNull();
            assertThat(updatedTask.getCostMillis()).isNotNull().isPositive();
            assertThat(updatedTask.getErrorCode()).isNull();

            // 验证 plan → EXECUTED
            DocumentStrategyPlan updatedPlan = strategyPlanMapper.selectById(plan.getId());
            assertThat(updatedPlan.getPlanStatus()).isEqualTo(DocumentPlanStatusEnum.EXECUTED.getCode());

            // 验证 steps → SUCCESS
            List<DocumentStrategyStep> steps = strategyStepMapper.selectList(null);
            assertThat(steps).hasSize(3);
            assertThat(steps).allMatch(s ->
                    s.getExecuteStatus().equals(DocumentStrategyExecuteStatusEnum.SUCCESS.getCode()));

            // 验证 parentBlocks 持久化
            List<DocumentParentBlock> parentBlocks = parentBlockMapper.selectList(null);
            assertThat(parentBlocks).hasSize(2);
            assertThat(parentBlocks).extracting(DocumentParentBlock::getParentNo).containsExactly(1, 2);
            assertThat(parentBlocks).allMatch(pb -> pb.getDocumentId().equals(doc.getId()));
            assertThat(parentBlocks).allMatch(pb -> pb.getTaskId().equals(task.getId()));

            // 验证 chunks 持久化
            List<DocumentChunk> chunks = chunkMapper.selectList(null);
            assertThat(chunks).hasSize(4);
            assertThat(chunks).allMatch(c -> c.getDocumentId().equals(doc.getId()));
            assertThat(chunks).allMatch(c ->
                    c.getVectorStatus().equals(DocumentVectorStatusEnum.VECTOR_SUCCESS.getCode()));

            // 验证 taskLog 阶段流转: CHUNK_EXECUTE → CHUNK_POST_PROCESS → VECTORIZE → STORE_COMPLETE
            List<DocumentTaskLog> logs = taskLogMapper.selectList(null);
            assertThat(logs).hasSize(6); // 4×START/COMPLETE + 阶段2 COMPLETE + 阶段4 COMPLETE
            assertThat(logs).anyMatch(l ->
                    l.getStageType().equals(DocumentTaskStageEnum.CHUNK_EXECUTE.getCode())
                            && l.getEventType().equals(DocumentTaskEventTypeEnum.START.getCode()));
            assertThat(logs).anyMatch(l ->
                    l.getStageType().equals(DocumentTaskStageEnum.CHUNK_POST_PROCESS.getCode())
                            && l.getEventType().equals(DocumentTaskEventTypeEnum.COMPLETE.getCode()));
            assertThat(logs).anyMatch(l ->
                    l.getStageType().equals(DocumentTaskStageEnum.VECTORIZE.getCode())
                            && l.getEventType().equals(DocumentTaskEventTypeEnum.COMPLETE.getCode()));
            assertThat(logs).anyMatch(l ->
                    l.getStageType().equals(DocumentTaskStageEnum.STORE_COMPLETE.getCode())
                            && l.getEventType().equals(DocumentTaskEventTypeEnum.COMPLETE.getCode()));
        }

        @Test
        @DisplayName("数据校验：document/task/plan 任一为 null → 静默返回")
        void shouldReturnSilentlyWhenDataNotFound() {
            Document doc = createDocument(1L, "test.pdf", 1);
            // 不创建 task 和 plan

            asyncProcessService.handleIndexBuild(doc.getId(), 999L, 999L);

            // 不应有任何 taskLog
            List<DocumentTaskLog> logs = taskLogMapper.selectList(null);
            // 只有 setUp 清表，没有新写入
            assertThat(logs).isEmpty();

            // document 状态不应被修改
            Document updated = documentMapper.selectById(doc.getId());
            assertThat(updated.getIndexStatus()).isEqualTo(DocumentIndexStatusEnum.WAIT_TO_BUILD.getCode());
        }

        @Test
        @DisplayName("isValidParentBlock 过滤：空文本候选被剔除")
        void shouldFilterEmptyParentCandidates() {
            Document doc = Document.builder()
                    .id(1L)
                    .documentName("empty-filter.pdf")
                    .originalFileName("empty-filter.pdf")
                    .fileType(1).mediaType("text/plain").fileSize(1024L)
                    .storageType(1).bucketName("test-bucket").objectName("test/f.pdf")
                    .parseStatus(DocumentParseStatusEnum.PARSE_SUCCESS.getCode())
                    .indexStatus(DocumentIndexStatusEnum.WAIT_TO_BUILD.getCode())
                    .parseSuccessTextPath("parsed-text/doc-1.txt")
                    .build();
            documentMapper.insert(doc);

            DocumentTask task = DocumentTask.builder()
                    .id(10L).documentId(doc.getId())
                    .taskType(DocumentTaskTypeEnum.BUILD_INDEX.getCode())
                    .taskStatus(DocumentTaskStatusEnum.NEW.getCode())
                    .currentStage(DocumentTaskStageEnum.STRATEGY_CONFIRM.getCode())
                    .triggerSource(1).retryCount(0).strategyPlanId(100L)
                    .build();
            taskMapper.insert(task);

            DocumentStrategyPlan plan = createPlan(100L, doc.getId());
            createStep(110L, plan.getId(), doc.getId(), 1, "PARENT", 1, 1);
            createStep(120L, plan.getId(), doc.getId(), 2, "CHILD", 3, 1);

            when(storageService.downloadObject(anyString())).thenReturn("文本".getBytes());

            // 候选列表混入空文本和 null
            ParentBlockCandidate valid = createParentCandidate("第一章", "有效内容",
                    List.of(createChildCandidate("child1")));
            ParentBlockCandidate emptyText = createParentCandidate(null, "",
                    List.of());
            ParentBlockCandidate nullText = createParentCandidate(null, null,
                    List.of());
            when(strategyService.buildParentBlocks(any(), any(), any(), anyString()))
                    .thenReturn(List.of(valid, emptyText, nullText));

            when(vectorGateway.vectorize(any())).thenAnswer(inv -> {
                List<DocumentChunk> chunks = inv.getArgument(0);
                List<Long> ids = chunks.stream().map(DocumentChunk::getId).toList();
                return DocumentVectorizationResult.builder()
                        .totalCount(chunks.size()).successCount(chunks.size()).failedCount(0)
                        .successChunkIds(ids).failedChunkIds(List.of()).build();
            });

            asyncProcessService.handleIndexBuild(doc.getId(), task.getId(), plan.getId());

            // 只有 1 个有效 parent + 1 个 child 被持久化
            List<DocumentParentBlock> parentBlocks = parentBlockMapper.selectList(null);
            assertThat(parentBlocks).hasSize(1);
            assertThat(parentBlocks.get(0).getParentText()).isEqualTo("有效内容");

            List<DocumentChunk> chunks = chunkMapper.selectList(null);
            assertThat(chunks).hasSize(1);
        }

        @Test
        @DisplayName("策略执行异常 → document=BUILD_FAIL, chunk.markVectorFailedByTaskId, steps=FAILED")
        void shouldHandleStrategyExecutionException() {
            Document doc = Document.builder()
                    .id(1L)
                    .documentName("fail.pdf")
                    .originalFileName("fail.pdf")
                    .fileType(1).mediaType("text/plain").fileSize(1024L)
                    .storageType(1).bucketName("test-bucket").objectName("test/fail.pdf")
                    .parseStatus(DocumentParseStatusEnum.PARSE_SUCCESS.getCode())
                    .indexStatus(DocumentIndexStatusEnum.WAIT_TO_BUILD.getCode())
                    .parseSuccessTextPath("parsed-text/doc-1.txt")
                    .build();
            documentMapper.insert(doc);

            DocumentTask task = DocumentTask.builder()
                    .id(10L).documentId(doc.getId())
                    .taskType(DocumentTaskTypeEnum.BUILD_INDEX.getCode())
                    .taskStatus(DocumentTaskStatusEnum.NEW.getCode())
                    .currentStage(DocumentTaskStageEnum.STRATEGY_CONFIRM.getCode())
                    .triggerSource(1).retryCount(0).strategyPlanId(100L)
                    .build();
            taskMapper.insert(task);

            DocumentStrategyPlan plan = createPlan(100L, doc.getId());
            createStep(110L, plan.getId(), doc.getId(), 1, "PARENT", 1, 1);

            when(storageService.downloadObject(anyString())).thenReturn("文本".getBytes());
            when(strategyService.buildParentBlocks(any(), any(), any(), anyString()))
                    .thenThrow(new RuntimeException("切块执行失败: 文本无法解析"));

            asyncProcessService.handleIndexBuild(doc.getId(), task.getId(), plan.getId());

            // 验证 document → BUILD_FAIL
            Document updatedDoc = documentMapper.selectById(doc.getId());
            assertThat(updatedDoc.getIndexStatus()).isEqualTo(DocumentIndexStatusEnum.BUILD_FAIL.getCode());

            // 验证 task → FAILED
            DocumentTask updatedTask = taskMapper.selectById(task.getId());
            assertThat(updatedTask.getTaskStatus()).isEqualTo(DocumentTaskStatusEnum.FAILED.getCode());
            assertThat(updatedTask.getErrorCode()).isEqualTo("20008");
            assertThat(updatedTask.getErrorMsg()).contains("切块执行失败");

            // 验证 steps → FAILED
            List<DocumentStrategyStep> steps = strategyStepMapper.selectList(null);
            assertThat(steps).hasSize(1);
            assertThat(steps.get(0).getExecuteStatus())
                    .isEqualTo(DocumentStrategyExecuteStatusEnum.FAILED.getCode());

            // 验证 taskLog 有 FAILED 事件
            List<DocumentTaskLog> logs = taskLogMapper.selectList(null);
            assertThat(logs).anyMatch(l ->
                    l.getEventType().equals(DocumentTaskEventTypeEnum.FAILED.getCode()));
        }

        @Test
        @DisplayName("向量化部分失败 → 成功/失败 chunk 分别标记正确状态")
        void shouldHandlePartialVectorizationFailure() {
            Document doc = Document.builder()
                    .id(1L)
                    .documentName("partial-fail.pdf")
                    .originalFileName("partial-fail.pdf")
                    .fileType(1).mediaType("text/plain").fileSize(1024L)
                    .storageType(1).bucketName("test-bucket").objectName("test/pf.pdf")
                    .parseStatus(DocumentParseStatusEnum.PARSE_SUCCESS.getCode())
                    .indexStatus(DocumentIndexStatusEnum.WAIT_TO_BUILD.getCode())
                    .parseSuccessTextPath("parsed-text/doc-1.txt")
                    .build();
            documentMapper.insert(doc);

            DocumentTask task = DocumentTask.builder()
                    .id(10L).documentId(doc.getId())
                    .taskType(DocumentTaskTypeEnum.BUILD_INDEX.getCode())
                    .taskStatus(DocumentTaskStatusEnum.NEW.getCode())
                    .currentStage(DocumentTaskStageEnum.STRATEGY_CONFIRM.getCode())
                    .triggerSource(1).retryCount(0).strategyPlanId(100L)
                    .build();
            taskMapper.insert(task);

            DocumentStrategyPlan plan = createPlan(100L, doc.getId());
            createStep(110L, plan.getId(), doc.getId(), 1, "PARENT", 1, 1);
            createStep(120L, plan.getId(), doc.getId(), 2, "CHILD", 3, 1);

            when(storageService.downloadObject(anyString())).thenReturn("文本".getBytes());

            ChunkCandidate c1 = createChildCandidate("chunk-1");
            ChunkCandidate c2 = createChildCandidate("chunk-2");
            ParentBlockCandidate p1 = createParentCandidate("第一章", "第一章内容", List.of(c1, c2));
            when(strategyService.buildParentBlocks(any(), any(), any(), anyString()))
                    .thenReturn(List.of(p1));

            // Mock：第一个成功，第二个失败
            when(vectorGateway.vectorize(any())).thenAnswer(inv -> {
                List<DocumentChunk> chunks = inv.getArgument(0);
                // uidGenerator 分配: parentBlock=1020, chunk1=1030, chunk2=1040
                List<Long> allIds = chunks.stream().map(DocumentChunk::getId).toList();
                return DocumentVectorizationResult.builder()
                        .totalCount(chunks.size())
                        .successCount(1)
                        .failedCount(1)
                        .successChunkIds(List.of(allIds.get(0)))
                        .failedChunkIds(List.of(allIds.get(1)))
                        .build();
            });

            asyncProcessService.handleIndexBuild(doc.getId(), task.getId(), plan.getId());

            // 验证 chunk 状态
            List<DocumentChunk> chunks = chunkMapper.selectList(null);
            assertThat(chunks).hasSize(2);
            assertThat(chunks).anyMatch(c ->
                    c.getVectorStatus().equals(DocumentVectorStatusEnum.VECTOR_SUCCESS.getCode()));
            assertThat(chunks).anyMatch(c ->
                    c.getVectorStatus().equals(DocumentVectorStatusEnum.VECTOR_FAILED.getCode()));

            // 整体流程应成功（部分失败不阻塞管道）
            Document updatedDoc = documentMapper.selectById(doc.getId());
            assertThat(updatedDoc.getIndexStatus()).isEqualTo(DocumentIndexStatusEnum.BUILD_SUCCESS.getCode());
        }

        @Test
        @DisplayName("task.currentStage 依次经过 CHUNK_EXECUTE → POST_PROCESS → VECTORIZE → STORE_COMPLETE")
        void shouldTransitionThroughCorrectStages() {
            Document doc = Document.builder()
                    .id(1L)
                    .documentName("stages.pdf")
                    .originalFileName("stages.pdf")
                    .fileType(1).mediaType("text/plain").fileSize(1024L)
                    .storageType(1).bucketName("test-bucket").objectName("test/stages.pdf")
                    .parseStatus(DocumentParseStatusEnum.PARSE_SUCCESS.getCode())
                    .indexStatus(DocumentIndexStatusEnum.WAIT_TO_BUILD.getCode())
                    .parseSuccessTextPath("parsed-text/doc-1.txt")
                    .build();
            documentMapper.insert(doc);

            DocumentTask task = DocumentTask.builder()
                    .id(10L).documentId(doc.getId())
                    .taskType(DocumentTaskTypeEnum.BUILD_INDEX.getCode())
                    .taskStatus(DocumentTaskStatusEnum.NEW.getCode())
                    .currentStage(DocumentTaskStageEnum.STRATEGY_CONFIRM.getCode())
                    .triggerSource(1).retryCount(0).strategyPlanId(100L)
                    .build();
            taskMapper.insert(task);

            DocumentStrategyPlan plan = createPlan(100L, doc.getId());
            createStep(110L, plan.getId(), doc.getId(), 1, "PARENT", 1, 1);
            createStep(120L, plan.getId(), doc.getId(), 2, "CHILD", 3, 1);

            when(storageService.downloadObject(anyString())).thenReturn("文本".getBytes());

            ChunkCandidate c1 = createChildCandidate("child-1");
            ParentBlockCandidate p1 = createParentCandidate("第一章", "第一章内容", List.of(c1));
            when(strategyService.buildParentBlocks(any(), any(), any(), anyString()))
                    .thenReturn(List.of(p1));

            when(vectorGateway.vectorize(any())).thenAnswer(inv -> {
                List<DocumentChunk> chunks = inv.getArgument(0);
                List<Long> ids = chunks.stream().map(DocumentChunk::getId).toList();
                return DocumentVectorizationResult.builder()
                        .totalCount(chunks.size()).successCount(chunks.size()).failedCount(0)
                        .successChunkIds(ids).failedChunkIds(List.of()).build();
            });

            asyncProcessService.handleIndexBuild(doc.getId(), task.getId(), plan.getId());

            // 终态 stage = STORE_COMPLETE
            DocumentTask updatedTask = taskMapper.selectById(task.getId());
            assertThat(updatedTask.getCurrentStage()).isEqualTo(DocumentTaskStageEnum.STORE_COMPLETE.getCode());

            // taskLog 覆盖了四个阶段
            List<DocumentTaskLog> logs = taskLogMapper.selectList(null);
            assertThat(logs).extracting(DocumentTaskLog::getStageType)
                    .contains(DocumentTaskStageEnum.CHUNK_EXECUTE.getCode(),
                            DocumentTaskStageEnum.CHUNK_POST_PROCESS.getCode(),
                            DocumentTaskStageEnum.VECTORIZE.getCode(),
                            DocumentTaskStageEnum.STORE_COMPLETE.getCode());
        }
    }
}
