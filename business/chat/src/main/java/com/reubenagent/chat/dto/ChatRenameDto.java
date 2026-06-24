package com.reubenagent.chat.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 会话重命名入参。
 *
 * @author reuben
 * @since 2026-06-24
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "会话重命名")
public class ChatRenameDto {

    @NotBlank(message = "会话ID不能为空")
    @Schema(description = "会话ID", required = true)
    private String conversationId;

    @Size(max = 256, message = "标题最长 256 字符")
    @Schema(description = "新标题（为空则触发生成默认标题）")
    private String title;
}
