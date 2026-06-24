package com.reubenagent.chat.controller;

import com.reubenagent.chat.dto.ChatStreamDto;
import com.reubenagent.chat.dto.ChatStopDto;
import com.reubenagent.chat.orchestrate.ChatStreamOrchestrator;
import com.reubenagent.chat.vo.ChatStopVo;
import com.reubenagent.common.dto.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.AllArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

/**
 * 对话流式接口 —— SSE 流式回答 + 会话停止。
 *
 * <p>会话 CRUD / 列表 / 详情等接口在 Phase 10 补齐。Controller 内不 catch，
 * 异常交 {@link com.reubenagent.common.exception.GlobalExceptionHandler}。</p>
 *
 * @author reuben
 * @since 2026-06-24
 */
@RestController
@RequestMapping("/api/chat")
@AllArgsConstructor
@Tag(name = "对话", description = "流式问答 / 会话控制")
public class ChatController {

    private final ChatStreamOrchestrator streamOrchestrator;

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
}
