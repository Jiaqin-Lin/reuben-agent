package com.reubenagent.document.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Date;

/**
 * 知识文档选项 VO —— 会话创建时的文档下拉项。
 *
 * @author reuben
 * @since 2026-06-24
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "知识文档选项")
public class KnowledgeDocumentOptionVo {

    @Schema(description = "文档ID")
    private Long documentId;

    @Schema(description = "文档名称")
    private String documentName;

    @Schema(description = "文件类型")
    private Integer fileType;

    @Schema(description = "索引状态：1=待构建 2=构建中 3=成功 4=失败")
    private Integer indexStatus;

    @Schema(description = "知识范围名称")
    private String knowledgeScopeName;

    @Schema(description = "创建时间")
    private Date createTime;
}
