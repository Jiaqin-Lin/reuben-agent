package com.reubenagent.document.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Chunk 列表项 VO。
 *
 * @author reuben
 * @since 2026-06-28
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Chunk 列表项")
public class DocumentChunkVo {

    @Schema(description = "Chunk ID")
    private Long chunkId;

    @Schema(description = "父块ID")
    private Long parentBlockId;

    @Schema(description = "父块序号")
    private Integer parentBlockNo;

    @Schema(description = "父块子节点数")
    private Integer parentChildCount;

    @Schema(description = "父块起始Chunk序号")
    private Integer parentStartChunkNo;

    @Schema(description = "父块结束Chunk序号")
    private Integer parentEndChunkNo;

    @Schema(description = "Chunk序号")
    private Integer chunkNo;

    @Schema(description = "章节路径")
    private String sectionPath;

    @Schema(description = "来源类型：1=原文 2=后处理")
    private Integer sourceType;

    @Schema(description = "字符数")
    private Integer charCount;

    @Schema(description = "Token数")
    private Integer tokenCount;

    @Schema(description = "向量状态：1=待向量化 2=向量化中 3=成功 4=失败")
    private Integer vectorStatus;

    @Schema(description = "Chunk 内容")
    private String chunkText;
}
