package com.reubenagent.document.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 手动索引构建响应 VO。
 *
 * @author reuben
 * @since 2026-06-28
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "索引构建触发结果")
public class DocumentIndexBuildVo {

    @Schema(description = "文档ID")
    private Long documentId;

    @Schema(description = "新创建的任务ID")
    private Long taskId;

    @Schema(description = "任务类型：2=构建索引")
    private Integer taskType;

    @Schema(description = "任务状态")
    private Integer taskStatus;

    @Schema(description = "文档索引状态")
    private Integer indexStatus;
}
