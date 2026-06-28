package com.reubenagent.document.controller;

import com.reubenagent.common.dto.ApiResponse;
import com.reubenagent.common.dto.PageVo;
import com.reubenagent.document.dto.KnowledgeRouteTraceQueryDto;
import com.reubenagent.document.dto.KnowledgeScopeDeleteDto;
import com.reubenagent.document.dto.KnowledgeScopeSaveDto;
import com.reubenagent.document.dto.KnowledgeTopicDeleteDto;
import com.reubenagent.document.dto.KnowledgeTopicSaveDto;
import com.reubenagent.document.dto.TopicDocumentRelationRemoveDto;
import com.reubenagent.document.dto.TopicDocumentRelationSaveDto;
import com.reubenagent.document.service.IKnowledgeManageService;
import com.reubenagent.document.vo.DocumentProfileVo;
import com.reubenagent.document.vo.KnowledgeRouteTraceItemVo;
import com.reubenagent.document.vo.KnowledgeScopeItemVo;
import com.reubenagent.document.vo.KnowledgeTopicItemVo;
import com.reubenagent.document.vo.TopicDocumentRelationItemVo;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.AllArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 知识管理 REST 接口 —— Scope / Topic / Relation CRUD + 文档画像 + 路由追踪。
 *
 * @author reuben
 * @since 2026-06-28
 */
@RestController
@RequestMapping("/api/document/knowledge")
@AllArgsConstructor
@Tag(name = "知识管理")
public class KnowledgeManageController {

    private final IKnowledgeManageService knowledgeManageService;

    // ==================== Scope ====================

    @GetMapping("/scope/list")
    @Operation(summary = "查询所有知识范围")
    public ApiResponse<List<KnowledgeScopeItemVo>> listScopes() {
        return ApiResponse.ok(knowledgeManageService.listScopes());
    }

    @PostMapping("/scope/save")
    @Operation(summary = "保存知识范围（创建或更新）")
    public ApiResponse<KnowledgeScopeItemVo> saveScope(@Valid @RequestBody KnowledgeScopeSaveDto dto) {
        return ApiResponse.ok(knowledgeManageService.saveScope(dto));
    }

    @PostMapping("/scope/delete")
    @Operation(summary = "删除知识范围（软删除）")
    public ApiResponse<Void> deleteScope(@Valid @RequestBody KnowledgeScopeDeleteDto dto) {
        knowledgeManageService.deleteScope(dto);
        return ApiResponse.ok(null);
    }

    // ==================== Topic ====================

    @GetMapping("/topic/list")
    @Operation(summary = "查询知识主题列表（可按 scopeCode 筛选）")
    public ApiResponse<List<KnowledgeTopicItemVo>> listTopics(@RequestParam(required = false) String scopeCode) {
        return ApiResponse.ok(knowledgeManageService.listTopics(scopeCode));
    }

    @PostMapping("/topic/save")
    @Operation(summary = "保存知识主题（创建或更新）")
    public ApiResponse<KnowledgeTopicItemVo> saveTopic(@Valid @RequestBody KnowledgeTopicSaveDto dto) {
        return ApiResponse.ok(knowledgeManageService.saveTopic(dto));
    }

    @PostMapping("/topic/delete")
    @Operation(summary = "删除知识主题（软删除）")
    public ApiResponse<Void> deleteTopic(@Valid @RequestBody KnowledgeTopicDeleteDto dto) {
        knowledgeManageService.deleteTopic(dto);
        return ApiResponse.ok(null);
    }

    // ==================== Topic-Document Relation ====================

    @GetMapping("/topic/document/list")
    @Operation(summary = "查询主题关联的文档列表")
    public ApiResponse<List<TopicDocumentRelationItemVo>> listRelations(@RequestParam String topicCode) {
        return ApiResponse.ok(knowledgeManageService.listRelations(topicCode));
    }

    @PostMapping("/topic/document/save")
    @Operation(summary = "保存主题-文档关联（创建或更新）")
    public ApiResponse<TopicDocumentRelationItemVo> saveRelation(@Valid @RequestBody TopicDocumentRelationSaveDto dto) {
        return ApiResponse.ok(knowledgeManageService.saveRelation(dto));
    }

    @PostMapping("/topic/document/remove")
    @Operation(summary = "删除主题-文档关联（软删除）")
    public ApiResponse<Void> removeRelation(@Valid @RequestBody TopicDocumentRelationRemoveDto dto) {
        knowledgeManageService.removeRelation(dto);
        return ApiResponse.ok(null);
    }

    // ==================== Document Profile ====================

    @GetMapping("/document/profile/detail")
    @Operation(summary = "查询文档画像")
    public ApiResponse<DocumentProfileVo> getProfile(@RequestParam Long documentId) {
        return ApiResponse.ok(knowledgeManageService.getProfile(documentId));
    }

    @PostMapping("/document/profile/regenerate")
    @Operation(summary = "重新生成文档画像")
    public ApiResponse<DocumentProfileVo> regenerateProfile(@RequestParam Long documentId) {
        return ApiResponse.ok(knowledgeManageService.regenerateProfile(documentId));
    }

    @PostMapping("/document/profile/batch-regenerate")
    @Operation(summary = "批量重新生成文档画像")
    public ApiResponse<List<DocumentProfileVo>> batchRegenerateProfiles(@RequestBody List<Long> documentIds) {
        return ApiResponse.ok(knowledgeManageService.batchRegenerateProfiles(documentIds));
    }

    // ==================== Route Trace ====================

    @GetMapping("/route/trace/page")
    @Operation(summary = "分页查询路由追踪记录")
    public ApiResponse<PageVo<KnowledgeRouteTraceItemVo>> pageQueryRouteTrace(
            @Valid KnowledgeRouteTraceQueryDto dto) {
        return ApiResponse.ok(knowledgeManageService.pageQueryRouteTrace(dto));
    }
}
