package com.reubenagent.chat.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 会话列表查询入参 —— 强类型（修正 super-agent 全 String + 防御性 parse）。
 *
 * @author reuben
 * @since 2026-06-24
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "会话列表查询")
public class ChatSessionListDto {

    @Min(value = 1, message = "页码最小为 1")
    @Schema(description = "页码（1 起）", example = "1")
    private Integer pageNo;

    @Min(value = 1, message = "每页条数最小为 1")
    @Max(value = 100, message = "每页条数最大为 100")
    @Schema(description = "每页条数", example = "10")
    private Integer pageSize;

    @Schema(description = "标题模糊关键词")
    private String keyword;

    @Schema(description = "对话模式：1=DOCUMENT 2=OPEN_CHAT 3=AUTO_DOCUMENT")
    private Integer chatMode;

    @Schema(description = "最近轮次状态过滤：1=RUNNING 2=COMPLETED 3=FAILED 4=STOPPED")
    private Integer turnStatus;
}
