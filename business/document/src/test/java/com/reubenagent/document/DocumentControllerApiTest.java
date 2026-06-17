package com.reubenagent.document;

import com.reubenagent.common.dto.ApiResponse;
import com.reubenagent.common.exception.GlobalExceptionHandler;
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
        classes = DocumentTestConfig.TestApp.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT
)
@ActiveProfiles("test")
@Import({DocumentTestConfig.TestMetaConfig.class, GlobalExceptionHandler.class})
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
        DocumentTestSchema.dropTables(jdbcTemplate);
        DocumentTestSchema.createAllTables(jdbcTemplate);

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

        // 异常被 GlobalExceptionHandler 捕获后仍返回 HTTP 200，但 code != 0
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