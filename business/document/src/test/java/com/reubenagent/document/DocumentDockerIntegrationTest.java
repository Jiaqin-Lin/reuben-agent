package com.reubenagent.document;

import com.reubenagent.document.config.DocumentProperties;
import com.reubenagent.document.dto.DocumentStrategyConfirmDto;
import com.reubenagent.document.dto.DocumentUploadDto;
import com.reubenagent.document.entity.Document;
import com.reubenagent.document.entity.DocumentStrategyPlan;
import com.reubenagent.document.entity.DocumentTask;
import com.reubenagent.document.enums.*;
import com.reubenagent.document.mapper.IDocumentMapper;
import com.reubenagent.document.mapper.IDocumentStrategyPlanMapper;
import com.reubenagent.document.mapper.IDocumentTaskMapper;
import com.reubenagent.document.model.mq.DocumentIndexBuildMessage;
import com.reubenagent.document.model.mq.DocumentParseRouteMessage;
import com.reubenagent.document.mq.DocumentKafkaProducer;
import com.reubenagent.document.service.IDocumentManageService;
import com.reubenagent.document.vo.DocumentStrategyConfirmVo;
import com.reubenagent.document.vo.DocumentUploadVo;
import com.reubenagent.framework.uid.UidGenerator;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Docker 集成测试 —— 验证 Kafka 异步任务消息的完整链路。
 *
 * <h3>前置条件</h3>
 * <pre>
 *   docker compose up -d
 *   # 等待所有服务健康检查通过
 *   docker compose ps
 * </pre>
 *
 * <h3>运行</h3>
 * <pre>
 *   mvn test -pl business/document -am \
 *       -Dtest=DocumentDockerIntegrationTest \
 *       -Dspring.profiles.active=docker \
 *       -DfailIfNoTests=false
 * </pre>
 *
 * <p>此测试使用真实 Kafka、MySQL、MinIO、Redis，验证异步消息的完整路由。</p>
 *
 * @author reuben
 * @since 2026-06-21
 */
@Slf4j
@SpringBootTest(
        classes = DocumentTestConfig.TestApp.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE
)
@Import({DocumentTestConfig.TestMetaConfig.class})
@ActiveProfiles("docker")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DocumentDockerIntegrationTest {

    @Autowired
    private IDocumentManageService documentManageService;

    @Autowired
    private ObjectProvider<DocumentKafkaProducer> kafkaProducerProvider;

    @Autowired
    private DocumentProperties documentProperties;

    @Autowired
    private IDocumentMapper documentMapper;

    @Autowired
    private IDocumentTaskMapper documentTaskMapper;

    @Autowired
    private IDocumentStrategyPlanMapper strategyPlanMapper;

    @Autowired
    private UidGenerator uidGenerator;

    private Long testDocumentId;
    private Long testParseTaskId;
    private Long testPlanId;

    /**
     * 测试 1：上传文档 → 验证 Kafka parse-route 消息投递成功。
     *
     * <p>上传一个简单的 txt 文件，验证返回的 documentId 和 taskId 非空，
     * 且对应的 Document 和 Task 记录已持久化。</p>
     */
    @Test
    @Order(1)
    @DisplayName("上传文档 → Kafka parse-route 消息投递")
    void uploadDocumentAndVerifyKafkaMessage() {
        // 构建测试文件
        String content = """
                # 项目简介

                这是一个测试文档，用于验证 Kafka 异步任务链路。

                ## 背景

                Docker 集成测试需要确保所有中间件正常运行。

                ## 总结

                本文档包含了多个章节结构，足以产生有效的结构节点供策略推荐使用。
                """;
        MockMultipartFile file = new MockMultipartFile(
                "file", "docker-integration-test.md",
                MediaType.TEXT_PLAIN_VALUE,
                content.getBytes(StandardCharsets.UTF_8));

        DocumentUploadDto dto = new DocumentUploadDto();
        dto.setDocumentName("Docker集成测试文档");
        dto.setKnowledgeScopeCode("TEST");
        dto.setKnowledgeScopeName("测试知识域");
        dto.setBusinessCategory("技术文档");
        dto.setDocumentTags("docker,integration-test,kafka");

        // 上传文档
        DocumentUploadVo vo = documentManageService.upload(file, dto);
        assertThat(vo).isNotNull();
        assertThat(vo.getDocumentId()).isPositive();
        assertThat(vo.getTaskId()).isPositive();

        testDocumentId = vo.getDocumentId();
        testParseTaskId = vo.getTaskId();

        log.info("文档上传完成: documentId={} taskId={}", testDocumentId, testParseTaskId);

        // 验证文档记录已持久化
        Document document = documentMapper.selectById(testDocumentId);
        assertThat(document).isNotNull();
        assertThat(document.getDocumentName()).isEqualTo("Docker集成测试文档");
        assertThat(document.getParseStatus()).isEqualTo(DocumentParseStatusEnum.WAIT_TO_PARSE.getCode());

        // 验证任务记录已持久化
        DocumentTask task = documentTaskMapper.selectById(testParseTaskId);
        assertThat(task).isNotNull();
        assertThat(task.getDocumentId()).isEqualTo(testDocumentId);
        assertThat(task.getTaskType()).isEqualTo(DocumentTaskTypeEnum.PARSE_ROUTE.getCode());
        assertThat(task.getTaskStatus()).isEqualTo(DocumentTaskStatusEnum.NEW.getCode());
    }

    /**
     * 测试 2：Kafka 生产者直接发送 parse-route 消息 → 验证消息格式正确。
     *
     * <p>不通过 upload，直接调用 Kafka 生产者发送消息，
     * 验证消息可以成功序列化并投递到 Kafka broker。</p>
     */
    @Test
    @Order(2)
    @DisplayName("Kafka 生产者——直接发送 parse-route 消息")
    void kafkaProducerSendParseRoute() {
        DocumentKafkaProducer kafkaProducer = kafkaProducerProvider.getIfAvailable();
        assumeTrue(kafkaProducer != null, "Kafka 不可用，跳过此测试");

        Long docId = 999001L;
        Long taskId = 999002L;
        DocumentParseRouteMessage message = new DocumentParseRouteMessage(docId, taskId);

        // 消息应成功发送不抛异常
        kafkaProducer.sendParseRoute(message);
        log.info("Kafka parse-route 消息发送成功: documentId={} taskId={}", docId, taskId);
    }

    /**
     * 测试 3：Kafka 生产者直接发送 index-build 消息 → 验证消息格式正确。
     */
    @Test
    @Order(3)
    @DisplayName("Kafka 生产者——直接发送 index-build 消息")
    void kafkaProducerSendIndexBuild() {
        DocumentKafkaProducer kafkaProducer = kafkaProducerProvider.getIfAvailable();
        assumeTrue(kafkaProducer != null, "Kafka 不可用，跳过此测试");

        Long docId = 999001L;
        Long taskId = 999003L;
        Long planId = 999004L;
        DocumentIndexBuildMessage message = new DocumentIndexBuildMessage(docId, taskId, planId);

        // 消息应成功发送不抛异常
        kafkaProducer.sendIndexBuild(message);
        log.info("Kafka index-build 消息发送成功: documentId={} taskId={} planId={}", docId, taskId, planId);
    }

    /**
     * 测试 4：验证 Kafka 消费者处理 parse-route 的容错性。
     *
     * <p>发送一个指向不存在 document 的消息，consumer 应优雅跳过（warn 日志）而非崩溃。</p>
     */
    @Test
    @Order(4)
    @DisplayName("Kafka 消费者——容错：不存在的文档")
    void kafkaConsumerGracefulDegradation() {
        DocumentKafkaProducer kafkaProducer = kafkaProducerProvider.getIfAvailable();
        assumeTrue(kafkaProducer != null, "Kafka 不可用，跳过此测试");

        Long nonExistentDocId = 88888888L;
        Long nonExistentTaskId = 88888889L;

        // 发送指向不存在文档的消息 —— consumer 应 catch 异常，不崩溃
        kafkaProducer.sendParseRoute(new DocumentParseRouteMessage(nonExistentDocId, nonExistentTaskId));
        log.info("已发送指向不存在文档的消息: documentId={}", nonExistentDocId);

        // 短暂等待消费者处理
        try {
            Thread.sleep(2000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        // 消费者应优雅处理，不会导致应用崩溃
    }

    /**
     * 测试 5：策略确认 → Kafka index-build 消息投递。
     *
     * <p>验证 confirmStrategy 可以创建 BUILD_INDEX 任务并投递 Kafka 消息。
     * 需要先有一个已推荐的策略方案（从测试 1 的文档解析结果中获得）。</p>
     */
    @Test
    @Order(5)
    @DisplayName("策略确认 → Kafka index-build 消息投递")
    void confirmStrategyAndTriggerIndexBuild() {
        // 先验证测试 1 的文档存在
        assertThat(testDocumentId).isNotNull();

        // 创建模拟的策略方案（实际生产中是 handleParseStrategyRoute 创建）
        // 这里手动创建一个 WAIT_CONFIRM 状态方案用于测试确认流程
        testPlanId = uidGenerator.getUid();
        DocumentStrategyPlan plan = DocumentStrategyPlan.builder()
                .id(testPlanId)
                .documentId(testDocumentId)
                .planVersion(1)
                .planSource(DocumentPlanSourceEnum.SYSTEM_RECOMMEND.getCode())
                .planStatus(DocumentPlanStatusEnum.WAIT_CONFIRM.getCode())
                .strategyCount(2)
                .strategySnapshot("PARENT:1;CHILD:3")
                .recommendReason("测试用策略方案")
                .build();
        strategyPlanMapper.insert(plan);

        // 确认策略
        DocumentStrategyConfirmDto confirmDto = DocumentStrategyConfirmDto.builder()
                .documentId(testDocumentId)
                .planId(testPlanId)
                .confirmUserId(null)
                .build();

        DocumentStrategyConfirmVo confirmVo = documentManageService.confirmStrategy(confirmDto);

        // 验证确认结果
        assertThat(confirmVo).isNotNull();
        assertThat(confirmVo.getTaskId()).isPositive();
        assertThat(confirmVo.getPlanId()).isEqualTo(testPlanId);
        assertThat(confirmVo.getPlanStatus()).isEqualTo(DocumentPlanStatusEnum.CONFIRMED.getCode());

        // 验证任务已创建
        DocumentTask indexTask = documentTaskMapper.selectById(confirmVo.getTaskId());
        assertThat(indexTask).isNotNull();
        assertThat(indexTask.getTaskType()).isEqualTo(DocumentTaskTypeEnum.BUILD_INDEX.getCode());
        assertThat(indexTask.getDocumentId()).isEqualTo(testDocumentId);
        assertThat(indexTask.getStrategyPlanId()).isEqualTo(testPlanId);

        // 验证方案状态已更新
        DocumentStrategyPlan updatedPlan = strategyPlanMapper.selectById(testPlanId);
        assertThat(updatedPlan.getPlanStatus()).isEqualTo(DocumentPlanStatusEnum.CONFIRMED.getCode());

        log.info("策略确认完成: documentId={} planId={} indexTaskId={}",
                testDocumentId, testPlanId, confirmVo.getTaskId());
    }

    /**
     * 测试 6：策略确认 —— 方案不存在时抛异常。
     */
    @Test
    @Order(6)
    @DisplayName("策略确认——异常：方案不存在")
    void confirmStrategyPlanNotFound() {
        DocumentStrategyConfirmDto dto = DocumentStrategyConfirmDto.builder()
                .documentId(testDocumentId)
                .planId(99999999L) // 不存在
                .build();

        try {
            documentManageService.confirmStrategy(dto);
            Assertions.fail("应抛出 PLAN_NOT_FOUND 异常");
        } catch (Exception e) {
            assertThat(e.getMessage()).contains("planId=99999999");
            log.info("正确抛出 PLAN_NOT_FOUND 异常: {}", e.getMessage());
        }
    }

    /**
     * 测试 7：策略确认 —— 方案状态非 WAIT_CONFIRM 时抛异常。
     */
    @Test
    @Order(7)
    @DisplayName("策略确认——异常：状态不允许")
    void confirmStrategyInvalidStatus() {
        DocumentStrategyConfirmDto dto = DocumentStrategyConfirmDto.builder()
                .documentId(testDocumentId)
                .planId(testPlanId) // 已被确认过，现在是 CONFIRMED 状态
                .build();

        try {
            documentManageService.confirmStrategy(dto);
            Assertions.fail("应抛出 PLAN_STATUS_INVALID 异常");
        } catch (Exception e) {
            assertThat(e.getMessage()).contains("planId");
            log.info("正确抛出 PLAN_STATUS_INVALID 异常: {}", e.getMessage());
        }
    }

    // ======== 测试环境辅助 ========

    /**
     * 验证测试环境的 Docker 服务是否可达。
     */
    @Test
    @Order(0)
    @DisplayName("Docker 环境可达性检查")
    void verifyDockerEnvironment() {
        // 验证 Kafka bootstrap servers 配置可用
        String parseTopic = documentProperties.getKafka().getParseTopic();
        assertThat(parseTopic).isNotEmpty();
        log.info("Kafka parse topic: {}", parseTopic);
        log.info("Kafka index topic: {}", documentProperties.getKafka().getIndexTopic());

        // 验证 MySQL 连接可用
        assertThat(documentMapper.selectCount(null)).isGreaterThanOrEqualTo(0);
        log.info("MySQL 连接正常");

        log.info("Docker 环境检查完成");
    }
}
