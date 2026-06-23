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
import java.util.Date;

/**
 * 对话通道执行。
 *
 * @author reuben
 * @since 2026-06-23
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("reuben_agent_chat_channel_execution")
@EqualsAndHashCode(callSuper = true)
public class ChatChannelExecution extends BaseTableData {

    @TableId(type = IdType.INPUT)
    private Long id;

    private String conversationId;

    private Long turnId;

    private String traceId;

    private Integer subQuestionIndex;

    private String channelType;

    private String executionState;

    private Date startTime;

    private Date endTime;

    private Long durationMs;

    private Integer recalledCount;

    private Integer acceptedCount;

    private Integer finalSelectedCount;

    private BigDecimal maxScore;

    private BigDecimal minScore;

    private BigDecimal avgScore;

    private String configSnapshot;
}
