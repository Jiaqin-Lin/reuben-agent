package com.reubenagent.document.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 父块简要 VO —— 供 Chunk 详情中嵌入。
 *
 * @author reuben
 * @since 2026-06-28
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "父块简要信息")
public class DocumentParentBlockVo {

    @Schema(description = "父块ID")
    private Long parentBlockId;

    @Schema(description = "父块序号")
    private Integer parentBlockNo;

    @Schema(description = "章节路径")
    private String sectionPath;

    @Schema(description = "来源类型：1=原文 2=后处理")
    private Integer sourceType;

    @Schema(description = "字符数")
    private Integer charCount;

    @Schema(description = "子节点数")
    private Integer childCount;

    @Schema(description = "起始Chunk序号")
    private Integer startChunkNo;

    @Schema(description = "结束Chunk序号")
    private Integer endChunkNo;

    @Schema(description = "父块完整内容")
    private String parentText;
}
