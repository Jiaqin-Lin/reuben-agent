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

/**
 * 对话阶段基准（P50/P90/P99 滑窗）。
 *
 * @author reuben
 * @since 2026-06-23
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("reuben_agent_chat_stage_benchmark")
@EqualsAndHashCode(callSuper = true)
public class ChatStageBenchmark extends BaseTableData {

    @TableId(type = IdType.INPUT)
    private Long id;

    private Integer stageCode;

    private Integer executionMode;

    private Long p50;

    private Long p90;

    private Long p99;

    private Long avg;

    private Long max;

    private Long min;

    private Integer sampleCount;

    private String recentDurations;
}
