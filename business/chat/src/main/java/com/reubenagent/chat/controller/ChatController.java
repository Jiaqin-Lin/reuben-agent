package com.reubenagent.chat.controller;

import com.reubenagent.chat.dto.ChatRenameDto;
import com.reubenagent.chat.dto.ChatSessionCreateDto;
import com.reubenagent.chat.dto.ChatSessionListDto;
import com.reubenagent.chat.dto.ChatStopDto;
import com.reubenagent.chat.dto.ChatStreamDto;
import com.reubenagent.chat.orchestrate.ChatStreamOrchestrator;
import com.reubenagent.chat.service.ChatDocumentOptionService;
import com.reubenagent.chat.service.IChatSessionService;
import com.reubenagent.chat.vo.ChannelExecutionView;
import com.reubenagent.chat.vo.ChatResetVo;
import com.reubenagent.chat.vo.ChatStopVo;
import com.reubenagent.chat.vo.ChatTurnDetailView;
import com.reubenagent.chat.vo.ConversationSessionListVo;
import com.reubenagent.chat.vo.ConversationView;
import com.reubenagent.chat.vo.RetrievalResultView;
import com.reubenagent.chat.vo.StageBenchmarkView;
import com.reubenagent.common.dto.ApiResponse;
import com.reubenagent.common.dto.PageVo;
import com.reubenagent.document.vo.KnowledgeDocumentOptionVo;
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
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

import java.util.List;

/**
 * 对话接口 —— SSE 流式问答 + 会话控制 + 观测查询。
 *
 * <p>Controller 内不 catch，异常交 {@link com.reubenagent.common.exception.GlobalExceptionHandler}。</p>
 *
 * @author reuben
 * @since 2026-06-24
 */
@RestController
@RequestMapping("/api/chat")
@AllArgsConstructor
@Tag(name = "对话", description = "流式问答 / 会话控制 / 观测查询")
public class ChatController {

    private final ChatStreamOrchestrator streamOrchestrator;
    private final IChatSessionService sessionService;
    private final ChatDocumentOptionService documentOptionService;

    // ======================== 流式问答 ========================

    @Operation(summary = "流式问答（SSE）")
    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE + ";charset=UTF-8")
    public Flux<String> stream(@Valid @RequestBody ChatStreamDto dto) {
        return streamOrchestrator.openStream(dto);
    }

    @Operation(summary = "停止会话生成")
    @PostMapping("/session/stop")
    public ApiResponse<ChatStopVo> stop(@Valid @RequestBody ChatStopDto dto) {
        return ApiResponse.ok(streamOrchestrator.stopStream(dto.getConversationId(), "用户已停止生成"));
    }

    // ======================== 会话 CRUD ========================

    @Operation(summary = "创建会话")
    @PostMapping("/session")
    public ApiResponse<ConversationView> create(@Valid @RequestBody ChatSessionCreateDto dto) {
        return ApiResponse.ok(sessionService.createConversation(dto));
    }

    @Operation(summary = "会话列表（分页）")
    @GetMapping("/session/list")
    public ApiResponse<PageVo<ConversationSessionListVo>> list(@Valid ChatSessionListDto dto) {
        return ApiResponse.ok(sessionService.listConversations(dto));
    }

    @Operation(summary = "会话详情")
    @GetMapping("/session/detail")
    public ApiResponse<ConversationView> detail(@RequestParam String conversationId) {
        return ApiResponse.ok(sessionService.getConversationDetail(conversationId));
    }

    @Operation(summary = "重命名会话")
    @PostMapping("/session/rename")
    public ApiResponse<String> rename(@Valid @RequestBody ChatRenameDto dto) {
        return ApiResponse.ok(sessionService.renameConversation(dto));
    }

    @Operation(summary = "重置会话（删轮次+清检查点+删摘要，会话保留）")
    @PostMapping("/session/reset")
    public ApiResponse<ChatResetVo> reset(@RequestParam String conversationId) {
        return ApiResponse.ok(sessionService.resetConversation(conversationId));
    }

    @Operation(summary = "删除会话（软删）")
    @DeleteMapping("/session/{conversationId}")
    public ApiResponse<Void> delete(@PathVariable String conversationId) {
        sessionService.deleteConversation(conversationId);
        return ApiResponse.ok(null);
    }

    @Operation(summary = "重建会话长期摘要")
    @PostMapping("/session/summary/rebuild")
    public ApiResponse<Void> rebuildSummary(@RequestParam String conversationId) {
        sessionService.rebuildSummary(conversationId);
        return ApiResponse.ok(null);
    }

    // ======================== 观测查询 ========================

    @Operation(summary = "轮次详情（含全链路 stage 追踪）")
    @GetMapping("/exchange/detail")
    public ApiResponse<ChatTurnDetailView> turnDetail(@RequestParam String conversationId,
                                                      @RequestParam Long turnId) {
        return ApiResponse.ok(sessionService.getTurnDetail(conversationId, turnId));
    }

    @Operation(summary = "轮次检索结果观测")
    @GetMapping("/exchange/retrieval/results")
    public ApiResponse<List<RetrievalResultView>> retrievalResults(@RequestParam String conversationId,
                                                                   @RequestParam Long turnId) {
        return ApiResponse.ok(sessionService.getRetrievalResults(conversationId, turnId));
    }

    @Operation(summary = "轮次通道执行观测")
    @GetMapping("/exchange/channel/executions")
    public ApiResponse<List<ChannelExecutionView>> channelExecutions(@RequestParam String conversationId,
                                                                     @RequestParam Long turnId) {
        return ApiResponse.ok(sessionService.getChannelExecutions(conversationId, turnId));
    }

    @Operation(summary = "stage 基准（P50/P90/P99）")
    @GetMapping("/stage/benchmarks")
    public ApiResponse<List<StageBenchmarkView>> stageBenchmarks(
            @RequestParam(required = false) Integer executionMode) {
        return ApiResponse.ok(sessionService.getStageBenchmarks(executionMode));
    }

    // ======================== 文档选项 ========================

    @Operation(summary = "可选文档列表（已索引）")
    @GetMapping("/document/options")
    public ApiResponse<List<KnowledgeDocumentOptionVo>> documentOptions() {
        return ApiResponse.ok(documentOptionService.listDocumentOptions());
    }
}
