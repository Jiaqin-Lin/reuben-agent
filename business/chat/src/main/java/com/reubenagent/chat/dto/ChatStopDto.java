package com.reubenagent.chat.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 会话停止入参。
 *
 * @author reuben
 * @since 2026-06-24
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "会话停止")
public class ChatStopDto {

    @NotBlank(message = "会话ID不能为空")
    @Schema(description = "会话ID", required = true)
    private String conversationId;
}
