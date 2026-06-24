package com.reubenagent.chat.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 流式问答入参 —— 强类型（修正 super-agent 全 String）。
 *
 * @author reuben
 * @since 2026-06-24
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "流式问答")
public class ChatStreamDto {

    @NotBlank(message = "问题不能为空")
    @Size(max = 8000, message = "问题最长 8000 字符")
    @Schema(description = "用户提问", required = true)
    private String question;

    @Size(max = 64, message = "会话ID最长 64 字符")
    @Schema(description = "会话ID（为空则自动创建）")
    private String conversationId;

    @NotNull(message = "对话模式不能为空")
    @Schema(description = "对话模式：1=DOCUMENT 2=OPEN_CHAT 3=AUTO_DOCUMENT", required = true)
    private Integer chatMode;

    @Schema(description = "选中文档ID（DOCUMENT 模式必填）")
    private Long selectedDocumentId;

    @Schema(description = "选中文档名快照")
    private String selectedDocumentName;
}
