package com.reubenagent.chat.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 创建会话入参。
 *
 * @author reuben
 * @since 2026-06-24
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "创建会话")
public class ChatSessionCreateDto {

    @NotNull(message = "对话模式不能为空")
    @Schema(description = "对话模式：1=DOCUMENT 2=OPEN_CHAT 3=AUTO_DOCUMENT", required = true)
    private Integer chatMode;

    @Schema(description = "选中文档ID（DOCUMENT 模式必填）")
    private Long selectedDocumentId;

    @Schema(description = "选中文档名快照")
    private String selectedDocumentName;

    @Schema(description = "会话标题（为空则自动生成）")
    private String title;
}
