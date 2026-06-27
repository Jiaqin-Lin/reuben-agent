package com.reubenagent.document.controller;

import com.reubenagent.common.dto.ApiResponse;
import com.reubenagent.common.dto.PageVo;
import com.reubenagent.document.dto.DocumentIndexBuildDto;
import com.reubenagent.document.dto.DocumentPageQueryDto;
import com.reubenagent.document.dto.DocumentStrategyConfirmDto;
import com.reubenagent.document.dto.DocumentUploadDto;
import com.reubenagent.document.service.IDocumentManageService;
import com.reubenagent.document.vo.DocumentChunkDetailVo;
import com.reubenagent.document.vo.DocumentChunkVo;
import com.reubenagent.document.vo.DocumentDeleteVo;
import com.reubenagent.document.vo.DocumentDetailVo;
import com.reubenagent.document.vo.DocumentIndexBuildVo;
import com.reubenagent.document.vo.DocumentListItemVo;
import com.reubenagent.document.vo.DocumentStrategyConfirmVo;
import com.reubenagent.document.vo.DocumentStrategyPlanVo;
import com.reubenagent.document.vo.DocumentTaskLogQueryVo;
import com.reubenagent.document.vo.DocumentUploadVo;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.AllArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * 文档管理 REST 接口 —— 上传 / 查询 / 删除 / 策略确认 / 索引构建 / Chunk &amp; 日志观测。
 *
 * @author reuben
 * @since 2026-06-14
 */
@RestController
@RequestMapping("/api/document")
@AllArgsConstructor
@Tag(name = "文档管理", description = "文档上传 / 查询 / 删除 / 策略确认 / 索引构建 / Chunk & 日志观测")
public class DocumentController {

    private final IDocumentManageService documentManageService;

    // ======================== 写入 ========================

    @Operation(summary = "上传文档并投递解析任务")
    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<DocumentUploadVo> upload(@RequestPart("file") MultipartFile file,
                                                @RequestPart(value = "meta", required = false) DocumentUploadDto documentUploadDto) {
        return ApiResponse.ok(documentManageService.upload(file,
                documentUploadDto == null ? new DocumentUploadDto() : documentUploadDto));
    }

    @Operation(summary = "确认策略方案并触发索引构建")
    @PostMapping("/strategy/confirm")
    public ApiResponse<DocumentStrategyConfirmVo> confirmStrategy(@RequestBody DocumentStrategyConfirmDto dto) {
        return ApiResponse.ok(documentManageService.confirmStrategy(dto));
    }

    @Operation(summary = "手动触发索引构建")
    @PostMapping("/index/build")
    public ApiResponse<DocumentIndexBuildVo> buildIndex(@Valid @RequestBody DocumentIndexBuildDto dto) {
        return ApiResponse.ok(documentManageService.buildIndex(dto));
    }

    @Operation(summary = "删除文档（级联清理 MinIO / PGVector / ES / DB）")
    @DeleteMapping("/{id}")
    public ApiResponse<DocumentDeleteVo> delete(@PathVariable("id") Long id) {
        return ApiResponse.ok(documentManageService.deleteDocument(id));
    }

    // ======================== 查询 ========================

    @Operation(summary = "文档分页列表")
    @GetMapping("/page")
    public ApiResponse<PageVo<DocumentListItemVo>> pageQuery(@Valid DocumentPageQueryDto dto) {
        return ApiResponse.ok(documentManageService.pageQuery(dto));
    }

    @Operation(summary = "查询文档详情")
    @GetMapping("/{id}")
    public ApiResponse<DocumentDetailVo> getDocument(@PathVariable("id") Long id) {
        return ApiResponse.ok(documentManageService.getDocument(id));
    }

    @Operation(summary = "获取策略方案列表")
    @GetMapping("/strategy/plan")
    public ApiResponse<List<DocumentStrategyPlanVo>> getPlans(@RequestParam("documentId") Long documentId) {
        return ApiResponse.ok(documentManageService.getPlans(documentId));
    }

    @Operation(summary = "文档 Chunk 列表")
    @GetMapping("/{id}/chunks")
    public ApiResponse<PageVo<DocumentChunkVo>> listChunks(
            @PathVariable("id") Long documentId,
            @RequestParam(value = "taskId", required = false) Long taskId,
            @RequestParam(value = "pageNo", defaultValue = "1") int pageNo,
            @RequestParam(value = "pageSize", defaultValue = "20") int pageSize) {
        return ApiResponse.ok(documentManageService.listChunks(documentId, taskId, pageNo, pageSize));
    }

    @Operation(summary = "Chunk 详情（含父块 + 兄弟 Chunks）")
    @GetMapping("/chunk/{chunkId}")
    public ApiResponse<DocumentChunkDetailVo> getChunkDetail(@PathVariable("chunkId") Long chunkId) {
        return ApiResponse.ok(documentManageService.getChunkDetail(chunkId));
    }

    @Operation(summary = "任务日志查询")
    @GetMapping("/task/{taskId}/logs")
    public ApiResponse<DocumentTaskLogQueryVo> getTaskLogs(
            @PathVariable("taskId") Long taskId,
            @RequestParam(value = "pageNo", defaultValue = "1") int pageNo,
            @RequestParam(value = "pageSize", defaultValue = "50") int pageSize) {
        return ApiResponse.ok(documentManageService.getTaskLogs(taskId, pageNo, pageSize));
    }
}
