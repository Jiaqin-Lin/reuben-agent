package com.reubenagent.rag.model;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 单条检索结果。
 *
 * @author reuben
 * @since 2026-06-21
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "检索结果")
public class RetrievalResult {

    /** chunk ID */
    @Schema(description = "chunk ID")
    private Long chunkId;

    /** chunk 文本 */
    @Schema(description = "chunk 文本")
    private String chunkText;

    /** 融合后分数 */
    @Schema(description = "融合后分数")
    private Double score;

    /** 章节路径 */
    @Schema(description = "章节路径")
    private String sectionPath;

    /** 来源文档 ID */
    @Schema(description = "来源文档 ID")
    private Long documentId;

    /** 父块 ID */
    @Schema(description = "父块 ID")
    private Long parentBlockId;

    /** 来源标记：vector / keyword / hybrid */
    @Schema(description = "来源标记", example = "hybrid")
    private String source;
}
