package com.reubenagent.document;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.reubenagent.document.dto.DocumentUploadDto;
import com.reubenagent.document.entity.Document;
import com.reubenagent.document.entity.DocumentTask;
import com.reubenagent.document.entity.DocumentTaskLog;
import com.reubenagent.document.mapper.IDocumentMapper;
import com.reubenagent.document.mapper.IDocumentTaskLogMapper;
import com.reubenagent.document.mapper.IDocumentTaskMapper;
import com.reubenagent.document.service.IDocumentManageService;
import com.reubenagent.document.vo.DocumentUploadVo;
import com.reubenagent.framework.uid.UidGenerator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.multipart.MultipartFile;

import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

/**
 * {@link com.reubenagent.document.service.impl.DocumentManageServiceImpl#upload}
 * MySQL 集成测试。
 *
 * <h3>前置</h3>
 * <pre>
 * docker compose up -d mysql minio
 * </pre>
 *
 * <h3>中间件</h3>
 * <ul>
 *   <li>MySQL 8.0 — Docker</li>
 *   <li>MinIO    — Docker（真实上传）</li>
 *   <li>UidGenerator — Mock</li>
 * </ul>
 */
@SpringBootTest(classes = DocumentTestConfig.TestApp.class)
@ActiveProfiles("test")
@Import(DocumentTestConfig.TestMetaConfig.class)
class DocumentManageServiceImplTest {

    @MockBean
    private UidGenerator uidGenerator;

    @Autowired
    private IDocumentManageService documentManageService;

    @Autowired
    private IDocumentMapper documentMapper;

    @Autowired
    private IDocumentTaskMapper documentTaskMapper;

    @Autowired
    private IDocumentTaskLogMapper documentTaskLogMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final AtomicLong uidSeq = new AtomicLong(100);

    @BeforeEach
    void setUp() {
        DocumentTestSchema.dropTables(jdbcTemplate);
        DocumentTestSchema.createAllTables(jdbcTemplate);

        uidSeq.set(100);
        when(uidGenerator.getUid()).thenAnswer(inv -> uidSeq.getAndAdd(100));
    }

    // =========================================================================
    // Tests
    // =========================================================================

    @Test
    @DisplayName("正常上传 PDF → 3 张 MySQL 表正确写入")
    void shouldInsertIntoAllThreeTables() {
        MultipartFile file = new MockMultipartFile(
                "file", "user_guide.pdf", "application/pdf",
                "Hello Reuben Agent!".getBytes());

        DocumentUploadDto dto = new DocumentUploadDto();
        dto.setDocumentName("用户手册");
        dto.setOperatorId("42");
        dto.setKnowledgeScopeCode("TECH_SUPPORT");
        dto.setKnowledgeScopeName("技术支持");
        dto.setBusinessCategory("IT运维");
        dto.setDocumentTags("pdf,manual,v1.0");

        DocumentUploadVo result = documentManageService.upload(file, dto);

        // VO 返回值验证
        assertThat(result).isNotNull();
        assertThat(result.getDocumentId()).isEqualTo(100L);
        assertThat(result.getTaskId()).isEqualTo(200L);
        assertThat(result.getDocumentName()).isEqualTo("用户手册");
        assertThat(result.getParseStatus()).isEqualTo(1);
        assertThat(result.getStrategyStatus()).isEqualTo(1);
        assertThat(result.getIndexStatus()).isEqualTo(1);

        // document 表
        Document doc = documentMapper.selectById(100L);
        assertThat(doc).isNotNull();
        assertThat(doc.getDocumentName()).isEqualTo("用户手册");
        assertThat(doc.getOriginalFileName()).isEqualTo("user_guide.pdf");
        assertThat(doc.getFileType()).isEqualTo(1);
        assertThat(doc.getMediaType()).isEqualTo("application/pdf");
        assertThat(doc.getFileSize()).isEqualTo(19L);
        assertThat(doc.getStorageType()).isEqualTo(1);
        assertThat(doc.getBucketName()).isEqualTo("reuben-agent-document");
        assertThat(doc.getObjectName()).startsWith("rag/document/100/");
        assertThat(doc.getObjectName()).contains("user_guide.pdf");
        assertThat(doc.getObjectUrl()).contains("reuben-agent-document");
        assertThat(doc.getParseStatus()).isEqualTo(1);
        assertThat(doc.getStrategyStatus()).isEqualTo(1);
        assertThat(doc.getIndexStatus()).isEqualTo(1);
        assertThat(doc.getCharCount()).isZero();
        assertThat(doc.getTokenCount()).isZero();
        assertThat(doc.getKnowledgeScopeCode()).isEqualTo("TECH_SUPPORT");
        assertThat(doc.getKnowledgeScopeName()).isEqualTo("技术支持");
        assertThat(doc.getBusinessCategory()).isEqualTo("IT运维");
        assertThat(doc.getDocumentTags()).isEqualTo("pdf,manual,v1.0");
        assertThat(doc.getCreateTime()).isNotNull();
        assertThat(doc.getUpdateTime()).isNotNull();
        assertThat(doc.getIsDeleted()).isEqualTo(0);

        // document_task 表
        DocumentTask task = documentTaskMapper.selectById(200L);
        assertThat(task).isNotNull();
        assertThat(task.getDocumentId()).isEqualTo(100L);
        assertThat(task.getTaskType()).isEqualTo(1);
        assertThat(task.getTaskStatus()).isEqualTo(1);
        assertThat(task.getCurrentStage()).isEqualTo(1);
        assertThat(task.getTriggerSource()).isEqualTo(2);
        assertThat(task.getRetryCount()).isZero();
        assertThat(task.getCreateTime()).isNotNull();

        // document_task_log 表
        DocumentTaskLog taskLog = documentTaskLogMapper.selectOne(
                new LambdaQueryWrapper<DocumentTaskLog>()
                        .eq(DocumentTaskLog::getTaskId, 200L));
        assertThat(taskLog).isNotNull();
        assertThat(taskLog.getId()).isEqualTo(300L);
        assertThat(taskLog.getDocumentId()).isEqualTo(100L);
        assertThat(taskLog.getStageType()).isEqualTo(1);
        assertThat(taskLog.getEventType()).isEqualTo(2);
        assertThat(taskLog.getLogLevel()).isEqualTo(1);
        assertThat(taskLog.getOperatorType()).isEqualTo(2);
        assertThat(taskLog.getOperatorId()).isEqualTo(42L);
        assertThat(taskLog.getContent()).contains("文件上传完成");
        assertThat(taskLog.getDetailJson()).contains("user_guide.pdf");
        assertThat(taskLog.getCreateTime()).isNotNull();
    }

    @Test
    @DisplayName("系统触发 → triggerSource=SYSTEM, operatorType=SYSTEM")
    void shouldUseSystemDefaultsWhenNoOperator() {
        MultipartFile file = new MockMultipartFile(
                "file", "auto.md", "text/markdown", "# Hello".getBytes());
        DocumentUploadDto dto = new DocumentUploadDto();
        dto.setDocumentName("自动导入");

        documentManageService.upload(file, dto);

        DocumentTask task = documentTaskMapper.selectById(200L);
        assertThat(task.getTriggerSource()).isEqualTo(1);

        DocumentTaskLog taskLog = documentTaskLogMapper.selectOne(
                new LambdaQueryWrapper<DocumentTaskLog>()
                        .eq(DocumentTaskLog::getTaskId, 200L));
        assertThat(taskLog.getOperatorType()).isEqualTo(1);
        assertThat(taskLog.getOperatorId()).isNull();
    }

    @Test
    @DisplayName("空文件 → DocumentException")
    void shouldRejectEmptyFile() {
        MultipartFile file = new MockMultipartFile(
                "file", "empty.pdf", "application/pdf", new byte[0]);
        assertThatThrownBy(() -> documentManageService.upload(file, new DocumentUploadDto()))
                .isInstanceOf(com.reubenagent.common.exception.DocumentException.class)
                .hasMessageContaining("文件内容为空");
    }

    @Test
    @DisplayName("文件名为空 → DocumentException")
    void shouldRejectMissingOriginalFileName() {
        MultipartFile file = new MockMultipartFile(
                "file", null, "application/pdf", "data".getBytes());
        assertThatThrownBy(() -> documentManageService.upload(file, new DocumentUploadDto()))
                .isInstanceOf(com.reubenagent.common.exception.DocumentException.class)
                .hasMessageContaining("原始文件名为空");
    }

    @Test
    @DisplayName("不支持的类型 (.mp4) → DocumentException")
    void shouldRejectUnsupportedFileType() {
        MultipartFile file = new MockMultipartFile(
                "file", "movie.mp4", "video/mp4", "fake".getBytes());
        assertThatThrownBy(() -> documentManageService.upload(file, new DocumentUploadDto()))
                .isInstanceOf(com.reubenagent.common.exception.DocumentException.class)
                .hasMessageContaining("仅支持 PDF");
    }

    @Test
    @DisplayName("主键冲突 → 事务回滚，已提交数据不受影响")
    void shouldRollbackOnDuplicateKey() {
        // first upload
        when(uidGenerator.getUid()).thenReturn(500L, 501L, 502L);
        MultipartFile f1 = new MockMultipartFile(
                "file", "first.pdf", "application/pdf", "first".getBytes());
        documentManageService.upload(f1, new DocumentUploadDto());

        // second — duplicate IDs
        when(uidGenerator.getUid()).thenReturn(500L, 501L, 502L);
        MultipartFile f2 = new MockMultipartFile(
                "file", "second.pdf", "application/pdf", "second".getBytes());

        assertThatThrownBy(() -> documentManageService.upload(f2, new DocumentUploadDto()))
                .isInstanceOf(Exception.class);

        Document first = documentMapper.selectById(500L);
        assertThat(first).isNotNull();
        assertThat(first.getOriginalFileName()).isEqualTo("first.pdf");
    }
}