package com.reubenagent.document.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 策略确认结果 VO。
 *
 * @author reuben
 * @since 2026-06-21
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DocumentStrategyConfirmVo {

    @Schema(description = "索引构建任务ID")
    private Long taskId;

    @Schema(description = "策略方案ID")
    private Long planId;

    @Schema(description = "文档ID")
    private Long documentId;

    @Schema(description = "方案状态（确认后 = CONFIRMED）")
    private Integer planStatus;
}
