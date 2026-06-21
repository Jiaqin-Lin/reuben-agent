package com.reubenagent.rag.controller;

import com.reubenagent.common.dto.ApiResponse;
import com.reubenagent.rag.dto.RagRetrieveRequest;
import com.reubenagent.rag.service.IRagRetrievalService;
import com.reubenagent.rag.vo.RagRetrieveResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * RAG 检索接口 —— 混合检索（向量 + 关键词 → RRF 融合）入口。
 *
 * <p>参数校验失败由 {@code GlobalExceptionHandler} 统一处理，
 * Controller 层不手动 catch。</p>
 *
 * @author reuben
 * @since 2026-06-21
 */
@Slf4j
@RestController
@RequestMapping("/api/rag")
@AllArgsConstructor
@Tag(name = "RAG检索", description = "混合检索召回接口")
public class RagController {

    private final IRagRetrievalService ragRetrievalService;

    @PostMapping("/retrieve")
    @Operation(summary = "混合检索召回", description = "向量检索 + 关键词检索 → RRF 融合返回 Top-K 结果")
    public ApiResponse<RagRetrieveResponse> retrieve(@RequestBody @Valid RagRetrieveRequest request) {
        log.info("RAG 检索请求: query={}, topK={}", request.getQuery(), request.getTopK());
        return ApiResponse.ok(ragRetrievalService.retrieve(request));
    }
}
