package com.reubenagent.chat.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 会话停止结果。
 *
 * @author reuben
 * @since 2026-06-24
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "会话停止结果")
public class ChatStopVo {

    @Schema(description = "会话ID")
    private String conversationId;

    @Schema(description = "是否成功停止")
    private boolean stopped;

    @Schema(description = "说明")
    private String message;
}
