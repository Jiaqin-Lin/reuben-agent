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
 * 对话追踪阶段。
 *
 * @author reuben
 * @since 2026-06-23
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("reuben_agent_chat_trace_stage")
@EqualsAndHashCode(callSuper = true)
public class ChatTraceStage extends BaseTableData {

    @TableId(type = IdType.INPUT)
    private Long id;

    private String conversationId;

    private Long turnId;

    private String traceId;

    /** 阶段编码 ChatTraceStageCode */
    private Integer stageCode;

    private String stageName;

    private Integer stageOrder;

    private Integer stageLevel;

    private Long parentStageId;

    private Integer executionMode;

    /** 阶段状态 ChatTraceStageState */
    private Integer stageState;

    private Date startTime;

    private Date endTime;

    private Long durationMs;

    private String summaryText;

    private String errorMessage;

    private String snapshotJson;
}
