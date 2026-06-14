package com.reubenagent.document.controller;

import com.reubenagent.common.dto.ApiResponse;
import com.reubenagent.document.dto.DocumentUploadDto;
import com.reubenagent.document.service.IDocumentManageService;
import com.reubenagent.document.vo.DocumentUploadVo;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import lombok.AllArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/document")
@AllArgsConstructor
public class DocumentController {

    private final IDocumentManageService documentManageService;

    @Operation(summary = "上传文档并投递解析任务")
    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<DocumentUploadVo> upload(@RequestPart("file") MultipartFile file,
                                                @Valid @RequestPart(value = "meta", required = false) DocumentUploadDto documentUploadDto) {

        return ApiResponse.ok(documentManageService.upload(file, documentUploadDto == null ? new DocumentUploadDto() : documentUploadDto));
    }
}
