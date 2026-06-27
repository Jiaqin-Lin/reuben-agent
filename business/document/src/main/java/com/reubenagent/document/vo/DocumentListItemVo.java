package com.reubenagent.document.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Date;

/**
 * 文档列表项 VO。
 *
 * @author reuben
 * @since 2026-06-28
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "文档列表项")
public class DocumentListItemVo {

    @Schema(description = "文档ID")
    private Long documentId;

    @Schema(description = "文档名称")
    private String documentName;

    @Schema(description = "原始文件名")
    private String originalFileName;

    @Schema(description = "文件类型：1=PDF 2=DOC 3=DOCX 4=TXT 5=MD 6=HTML")
    private Integer fileType;

    @Schema(description = "文件大小（字节）")
    private Long fileSize;

    @Schema(description = "总字符数")
    private Integer charCount;

    @Schema(description = "估算Token数")
    private Integer tokenCount;

    @Schema(description = "解析状态：1=待解析 2=解析中 3=成功 4=失败")
    private Integer parseStatus;

    @Schema(description = "策略状态：1=待推荐 2=已推荐 3=已确认 4=已失效")
    private Integer strategyStatus;

    @Schema(description = "索引状态：1=待构建 2=构建中 3=成功 4=失败")
    private Integer indexStatus;

    @Schema(description = "解析失败信息")
    private String parseErrorMsg;

    @Schema(description = "知识范围编码")
    private String knowledgeScopeCode;

    @Schema(description = "知识范围名称")
    private String knowledgeScopeName;

    @Schema(description = "业务分类")
    private String businessCategory;

    @Schema(description = "文档标签")
    private String documentTags;

    @Schema(description = "当前策略方案ID")
    private Long currentPlanId;

    @Schema(description = "最近索引任务ID")
    private Long latestIndexTaskId;

    @Schema(description = "最近任务ID")
    private Long latestTaskId;

    @Schema(description = "最近任务类型：1=解析路由 2=构建索引")
    private Integer latestTaskType;

    @Schema(description = "最近任务状态")
    private Integer latestTaskStatus;

    @Schema(description = "创建时间")
    private Date createTime;

    @Schema(description = "更新时间")
    private Date updateTime;
}
