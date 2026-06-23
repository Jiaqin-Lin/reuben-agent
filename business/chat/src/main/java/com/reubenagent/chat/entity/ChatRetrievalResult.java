package com.reubenagent.chat.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.reubenagent.common.data.BaseTableData;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * 对话检索结果。
 *
 * @author reuben
 * @since 2026-06-23
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("reuben_agent_chat_retrieval_result")
@EqualsAndHashCode(callSuper = true)
public class ChatRetrievalResult extends BaseTableData {

    @TableId(type = IdType.INPUT)
    private Long id;

    private String conversationId;

    private Long turnId;

    private String traceId;

    private Integer subQuestionIndex;

    /** 通道类型: vector/keyword/hybrid */
    private String channelType;

    private Integer vectorRank;
    private BigDecimal vectorScore;
    private Integer keywordRank;
    private BigDecimal keywordScore;
    private BigDecimal rerankScore;
    private BigDecimal finalScore;

    private Integer gatePassed;
    private Integer isElevated;
    private Integer isSelected;
    private String selectionReason;

    private Long documentId;
    private String documentName;
    private Long chunkId;
    private Long parentBlockId;
    private String sectionPath;
    private String chunkTextPreview;
}
