package com.reubenagent.document.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Chunk 详情 VO —— 含当前 chunk、所属 parent block、同父兄弟 chunks。
 *
 * @author reuben
 * @since 2026-06-28
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Chunk 详情")
public class DocumentChunkDetailVo {

    @Schema(description = "文档ID")
    private Long documentId;

    @Schema(description = "关联任务ID")
    private Long taskId;

    @Schema(description = "关联策略方案ID")
    private Long planId;

    @Schema(description = "当前 Chunk")
    private DocumentChunkVo chunk;

    @Schema(description = "所属父块")
    private DocumentParentBlockVo parentBlock;

    @Schema(description = "同父兄弟 Chunks")
    private List<DocumentChunkVo> siblingChunks;
}
