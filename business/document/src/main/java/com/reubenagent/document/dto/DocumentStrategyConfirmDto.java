package com.reubenagent.document.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 策略方案确认请求 DTO。
 *
 * @author reuben
 * @since 2026-06-21
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DocumentStrategyConfirmDto {

    @Schema(description = "文档ID", required = true)
    private Long documentId;

    @Schema(description = "策略方案ID", required = true)
    private Long planId;

    @Schema(description = "确认人ID（可选）")
    private Long confirmUserId;
}
