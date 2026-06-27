package com.reubenagent.document.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 手动触发索引构建入参。
 *
 * @author reuben
 * @since 2026-06-28
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "手动触发索引构建")
public class DocumentIndexBuildDto {

    @NotNull(message = "文档ID不能为空")
    @Schema(description = "文档ID", required = true)
    private Long documentId;

    @NotNull(message = "策略方案ID不能为空")
    @Schema(description = "策略方案ID", required = true)
    private Long planId;

    @Schema(description = "操作人ID（可选，为空时记为系统操作）")
    private Long operatorId;
}
