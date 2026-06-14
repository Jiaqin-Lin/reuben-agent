package com.reubenagent.document;

import com.reubenagent.common.dto.ApiResponse;
import com.reubenagent.document.dto.DocumentUploadDto;
import com.reubenagent.document.entity.Document;
import com.reubenagent.document.entity.DocumentTask;
import com.reubenagent.document.entity.DocumentTaskLog;
import com.reubenagent.document.mapper.IDocumentMapper;
import com.reubenagent.document.mapper.IDocumentTaskLogMapper;
import com.reubenagent.document.mapper.IDocumentTaskMapper;
import com.reubenagent.framework.uid.UidGenerator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import com.reubenagent.common.exception.GlobalExceptionHandler;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * HTTP 接口集成测试 — 通过 TestRestTemplate 调 POST /api/document/upload
 *
 * <h3>前置</h3>
 * <pre>
 * docker compose up -d mysql minio
 * </pre>
 */
@SpringBootTest(
    classes = DocumentManageServiceImplTest.TestApp.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT
)
@ActiveProfiles("test")
@Import({DocumentManageServiceImplTest.TestMetaConfig.class, GlobalExceptionHandler.class})
class DocumentControllerApiTest {

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    @MockBean
    private UidGenerator uidGenerator;

    @Autowired
    private IDocumentMapper documentMapper;

    @Autowired
    private IDocumentTaskMapper documentTaskMapper;

    @Autowired
    private IDocumentTaskLogMapper documentTaskLogMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final AtomicLong uidSeq = new AtomicLong(1000);

    @BeforeEach
    void setUp() {
        jdbcTemplate.execute("DROP TABLE IF EXISTS reuben_agent_document_task_log");
        jdbcTemplate.execute("DROP TABLE IF EXISTS reuben_agent_document_task");
        jdbcTemplate.execute("DROP TABLE IF EXISTS reuben_agent_document");

        jdbcTemplate.execute("""
            CREATE TABLE reuben_agent_document (
                id              BIGINT        NOT NULL PRIMARY KEY,
                document_name   VARCHAR(512)  DEFAULT NULL,
                original_file_name VARCHAR(512) DEFAULT NULL,
                file_type       TINYINT       DEFAULT NULL,
                media_type      VARCHAR(128)  DEFAULT NULL,
                file_size       BIGINT        DEFAULT NULL,
                storage_type    TINYINT       DEFAULT NULL,
                bucket_name     VARCHAR(128)  DEFAULT NULL,
                object_name     VARCHAR(512)  DEFAULT NULL,
                object_url      VARCHAR(1024) DEFAULT NULL,
                parse_status    TINYINT       DEFAULT 1,
                strategy_status TINYINT       DEFAULT 1,
                index_status    TINYINT       DEFAULT 1,
                char_count      INT           DEFAULT NULL,
                token_count     INT           DEFAULT NULL,
                structure_level TINYINT       DEFAULT 0,
                content_quality_level TINYINT DEFAULT 0,
                parse_success_text_path VARCHAR(512) DEFAULT NULL,
                parse_error_msg VARCHAR(1024) DEFAULT NULL,
                knowledge_scope_code VARCHAR(128) DEFAULT NULL,
                knowledge_scope_name VARCHAR(256) DEFAULT NULL,
                business_category VARCHAR(256) DEFAULT NULL,
                document_tags   VARCHAR(1024) DEFAULT NULL,
                current_strategy_plan_id BIGINT DEFAULT NULL,
                latest_parse_task_id BIGINT DEFAULT NULL,
                structure_node_count INT DEFAULT NULL,
                latest_index_task_id BIGINT DEFAULT NULL,
                create_time     DATETIME      DEFAULT NULL,
                update_time     DATETIME      DEFAULT NULL,
                is_deleted      TINYINT       DEFAULT 0
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
            """);

        jdbcTemplate.execute("""
            CREATE TABLE reuben_agent_document_task (
                id              BIGINT        NOT NULL PRIMARY KEY,
                document_id     BIGINT        NOT NULL,
                strategy_plan_id BIGINT       DEFAULT NULL,
                task_type       TINYINT       DEFAULT NULL,
                task_status     TINYINT       DEFAULT 1,
                current_stage   TINYINT       DEFAULT NULL,
                trigger_source  TINYINT       DEFAULT 1,
                strategy_snapshot VARCHAR(256) DEFAULT NULL,
                retry_count     INT           DEFAULT 0,
                start_time      DATETIME      DEFAULT NULL,
                finish_time     DATETIME      DEFAULT NULL,
                cost_millis     BIGINT        DEFAULT NULL,
                error_code      VARCHAR(64)   DEFAULT NULL,
                error_msg       VARCHAR(1024) DEFAULT NULL,
                ext_json        TEXT          DEFAULT NULL,
                create_time     DATETIME      DEFAULT NULL,
                update_time     DATETIME      DEFAULT NULL,
                is_deleted      TINYINT       DEFAULT 0
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
            """);

        jdbcTemplate.execute("""
            CREATE TABLE reuben_agent_document_task_log (
                id              BIGINT        NOT NULL PRIMARY KEY,
                task_id         BIGINT        NOT NULL,
                document_id     BIGINT        NOT NULL,
                stage_type      TINYINT       DEFAULT NULL,
                event_type      TINYINT       DEFAULT NULL,
                log_level       TINYINT       DEFAULT 1,
                operator_type   TINYINT       DEFAULT 1,
                operator_id     BIGINT        DEFAULT NULL,
                content         VARCHAR(2048) DEFAULT NULL,
                detail_json     TEXT          DEFAULT NULL,
                create_time     DATETIME      DEFAULT NULL,
                update_time     DATETIME      DEFAULT NULL,
                is_deleted      TINYINT       DEFAULT 0
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
            """);

        uidSeq.set(1000);
        when(uidGenerator.getUid()).thenAnswer(inv -> uidSeq.getAndAdd(100));
    }

    // =========================================================================
    // HTTP 接口测试
    // =========================================================================

    @Test
    @DisplayName("POST /api/document/upload — 正常上传 PDF → 返回 ApiResponse code=0")
    void shouldUploadViaHttpAndReturnOk() {
        String url = "http://localhost:" + port + "/api/document/upload";

        // 构建 multipart: file + meta
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", new ByteArrayResource("Hello Reuben Agent API!".getBytes()) {
            @Override
            public String getFilename() {
                return "api_test.pdf";
            }
        });

        DocumentUploadDto meta = new DocumentUploadDto();
        meta.setDocumentName("API测试文档");
        meta.setOperatorId("99");
        meta.setKnowledgeScopeCode("API_TEST");
        meta.setKnowledgeScopeName("接口测试");
        meta.setBusinessCategory("测试");
        meta.setDocumentTags("api,test");

        HttpHeaders jsonHeaders = new HttpHeaders();
        jsonHeaders.setContentType(MediaType.APPLICATION_JSON);
        body.add("meta", new HttpEntity<>(meta, jsonHeaders));

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        HttpEntity<MultiValueMap<String, Object>> request = new HttpEntity<>(body, headers);

        ResponseEntity<ApiResponse> response = restTemplate.postForEntity(url, request, ApiResponse.class);

        // HTTP 200
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);

        // ApiResponse code=0
        ApiResponse apiResponse = response.getBody();
        assertThat(apiResponse).isNotNull();
        assertThat(apiResponse.getCode()).isEqualTo(0);
        assertThat(apiResponse.getData()).isNotNull();

        // 验证 DB 写入
        Document doc = documentMapper.selectById(1000L);
        assertThat(doc).isNotNull();
        assertThat(doc.getDocumentName()).isEqualTo("API测试文档");
        assertThat(doc.getOriginalFileName()).isEqualTo("api_test.pdf");
        assertThat(doc.getFileType()).isEqualTo(1);
        assertThat(doc.getKnowledgeScopeCode()).isEqualTo("API_TEST");
        assertThat(doc.getIsDeleted()).isEqualTo(0);
        assertThat(doc.getCreateTime()).isNotNull();
        assertThat(doc.getUpdateTime()).isNotNull();

        DocumentTask task = documentTaskMapper.selectById(1100L);
        assertThat(task).isNotNull();
        assertThat(task.getDocumentId()).isEqualTo(1000L);
        assertThat(task.getTriggerSource()).isEqualTo(2); // USER

        DocumentTaskLog taskLog = documentTaskLogMapper.selectById(1200L);
        assertThat(taskLog).isNotNull();
        assertThat(taskLog.getTaskId()).isEqualTo(1100L);
        assertThat(taskLog.getOperatorId()).isEqualTo(99L);
        assertThat(taskLog.getContent()).contains("文件上传完成");
    }

    @Test
    @DisplayName("POST /api/document/upload — 空文件 → ApiResponse code!=0 (业务异常)")
    void shouldReturnErrorOnEmptyFile() {
        String url = "http://localhost:" + port + "/api/document/upload";

        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", new ByteArrayResource(new byte[0]) {
            @Override
            public String getFilename() {
                return "empty.pdf";
            }
        });

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        HttpEntity<MultiValueMap<String, Object>> request = new HttpEntity<>(body, headers);

        ResponseEntity<ApiResponse> response = restTemplate.postForEntity(url, request, ApiResponse.class);

        // 异常被 GlobalExceptionHandler 捕获后仍返回 HTTP 200（Spring 默认），但 code != 0
        ApiResponse apiResponse = response.getBody();
        assertThat(apiResponse).isNotNull();
        assertThat(apiResponse.getCode()).isNotEqualTo(0);
        assertThat(apiResponse.getMessage()).contains("文件内容为空");
    }

    @Test
    @DisplayName("POST /api/document/upload — 不传 meta → 使用空 DTO，系统触发")
    void shouldWorkWithoutMeta() {
        String url = "http://localhost:" + port + "/api/document/upload";

        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", new ByteArrayResource("no meta".getBytes()) {
            @Override
            public String getFilename() {
                return "no_meta.md";
            }
        });

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        HttpEntity<MultiValueMap<String, Object>> request = new HttpEntity<>(body, headers);

        ResponseEntity<ApiResponse> response = restTemplate.postForEntity(url, request, ApiResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        ApiResponse apiResponse = response.getBody();
        assertThat(apiResponse).isNotNull();
        assertThat(apiResponse.getCode()).isEqualTo(0);

        // 系统触发
        DocumentTask task = documentTaskMapper.selectById(1100L);
        assertThat(task.getTriggerSource()).isEqualTo(1); // SYSTEM
    }
}
