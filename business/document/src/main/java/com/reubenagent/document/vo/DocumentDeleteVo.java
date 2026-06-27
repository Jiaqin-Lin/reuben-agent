package com.reubenagent.document.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 文档删除响应 VO。
 *
 * @author reuben
 * @since 2026-06-28
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "文档删除结果")
public class DocumentDeleteVo {

    @Schema(description = "文档ID")
    private Long documentId;

    @Schema(description = "文档名称")
    private String documentName;

    @Schema(description = "是否已级联清理存储")
    private Boolean storageCleaned;

    @Schema(description = "是否已级联清理向量库")
    private Boolean vectorCleaned;

    @Schema(description = "是否已级联清理关键词索引")
    private Boolean keywordCleaned;
}
