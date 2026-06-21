package com.reubenagent.document.controller;

import com.reubenagent.common.dto.ApiResponse;
import com.reubenagent.document.dto.DocumentStrategyConfirmDto;
import com.reubenagent.document.dto.DocumentUploadDto;
import com.reubenagent.document.service.IDocumentManageService;
import com.reubenagent.document.vo.DocumentStrategyConfirmVo;
import com.reubenagent.document.vo.DocumentUploadVo;
import io.swagger.v3.oas.annotations.Operation;
import lombok.AllArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * 文档管理 REST 接口 —— 提供文档上传、策略确认等入口。
 *
 * @author reuben
 * @since 2026-06-14
 */
@RestController
@RequestMapping("/api/document")
@AllArgsConstructor
public class DocumentController {

    private final IDocumentManageService documentManageService;

    /**
     * 上传文档并投递解析任务。file 必传，meta 可选。
     */
    @Operation(summary = "上传文档并投递解析任务")
    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<DocumentUploadVo> upload(@RequestPart("file") MultipartFile file,
                                                @RequestPart(value = "meta", required = false) DocumentUploadDto documentUploadDto) {

        return ApiResponse.ok(documentManageService.upload(file,
                documentUploadDto == null ? new DocumentUploadDto() : documentUploadDto));
    }

    /**
     * 确认策略方案并触发索引构建。
     */
    @Operation(summary = "确认策略方案并触发索引构建")
    @PostMapping("/strategy/confirm")
    public ApiResponse<DocumentStrategyConfirmVo> confirmStrategy(
            @RequestBody DocumentStrategyConfirmDto dto) {
        return ApiResponse.ok(documentManageService.confirmStrategy(dto));
    }
}
