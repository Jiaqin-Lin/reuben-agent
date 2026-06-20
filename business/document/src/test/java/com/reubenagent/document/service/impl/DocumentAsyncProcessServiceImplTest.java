package com.reubenagent.document.service.impl;

import com.reubenagent.document.DocumentTestConfig;
import com.reubenagent.document.DocumentTestSchema;
import com.reubenagent.document.entity.Document;
import com.reubenagent.document.entity.DocumentStrategyPlan;
import com.reubenagent.document.entity.DocumentStrategyStep;
import com.reubenagent.document.entity.DocumentTask;
import com.reubenagent.document.entity.DocumentTaskLog;
import com.reubenagent.document.enums.DocumentParseStatusEnum;
import com.reubenagent.document.enums.DocumentStrategyStatusEnum;
import com.reubenagent.document.enums.DocumentTaskEventTypeEnum;
import com.reubenagent.document.enums.DocumentTaskStageEnum;
import com.reubenagent.document.enums.DocumentTaskStatusEnum;
import com.reubenagent.document.mapper.IDocumentMapper;
import com.reubenagent.document.mapper.IDocumentStrategyPlanMapper;
import com.reubenagent.document.mapper.IDocumentStrategyStepMapper;
import com.reubenagent.document.mapper.IDocumentTaskLogMapper;
import com.reubenagent.document.mapper.IDocumentTaskMapper;
import com.reubenagent.document.model.DocumentParseResult;
import com.reubenagent.document.model.DocumentStrategyPlanDraft;
import com.reubenagent.document.model.DocumentStrategyStepDraft;
import com.reubenagent.document.service.IDocumentParseResultService;
import com.reubenagent.document.service.IDocumentProfileService;
import com.reubenagent.document.service.IDocumentStorageService;
import com.reubenagent.document.service.IDocumentStrategyService;
import com.reubenagent.document.service.IDocumentStructureNodeService;
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
}
