package com.reubenagent.document.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 文档分页列表查询入参。
 *
 * @author reuben
 * @since 2026-06-28
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "文档分页列表查询")
public class DocumentPageQueryDto {

    @Min(value = 1, message = "页码最小为 1")
    @Schema(description = "页码（1 起）", example = "1")
    private Integer pageNo;

    @Min(value = 1, message = "每页条数最小为 1")
    @Max(value = 100, message = "每页条数最大为 100")
    @Schema(description = "每页条数", example = "10")
    private Integer pageSize;

    @Schema(description = "文档名或原始文件名模糊关键词")
    private String keyword;
}
