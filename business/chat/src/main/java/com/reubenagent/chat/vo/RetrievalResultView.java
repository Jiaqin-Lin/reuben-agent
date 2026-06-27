package com.reubenagent.chat.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.Date;

/**
 * 检索结果观测视图。
 *
 * @author reuben
 * @since 2026-06-27
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "检索结果观测")
public class RetrievalResultView {

    @Schema(description = "记录ID")
    private Long id;

    @Schema(description = "会话ID")
    private String conversationId;

    @Schema(description = "轮次ID")
    private Long turnId;

    @Schema(description = "子问题序号")
    private Integer subQuestionIndex;

    @Schema(description = "通道类型 vector/keyword/hybrid")
    private String channelType;

    @Schema(description = "向量排名")
    private Integer vectorRank;

    @Schema(description = "rerank 分数")
    private BigDecimal rerankScore;

    @Schema(description = "最终分数")
    private BigDecimal finalScore;

    @Schema(description = "是否通过门控")
    private Integer gatePassed;

    @Schema(description = "是否选中")
    private Integer isSelected;

    @Schema(description = "文档ID")
    private Long documentId;

    @Schema(description = "文档名")
    private String documentName;

    @Schema(description = "chunk ID")
    private Long chunkId;

    @Schema(description = "章节路径")
    private String sectionPath;

    @Schema(description = "chunk 文本预览")
    private String chunkTextPreview;

    @Schema(description = "创建时间")
    private Date createTime;
}
