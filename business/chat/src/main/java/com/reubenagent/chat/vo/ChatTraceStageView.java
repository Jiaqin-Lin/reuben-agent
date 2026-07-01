package com.reubenagent.chat.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Date;
import java.util.Map;

/**
 * 追踪 stage 观测视图。
 *
 * @author reuben
 * @since 2026-06-27
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "追踪阶段观测")
public class ChatTraceStageView {

    @Schema(description = "记录ID")
    private Long id;

    @Schema(description = "会话ID")
    private String conversationId;

    @Schema(description = "轮次ID")
    private Long turnId;

    @Schema(description = "trace ID")
    private String traceId;

    @Schema(description = "阶段编码")
    private Integer stageCode;

    @Schema(description = "阶段名称")
    private String stageName;

    @Schema(description = "阶段顺序")
    private Integer stageOrder;

    @Schema(description = "执行模式")
    private Integer executionMode;

    @Schema(description = "阶段状态 1=RUNNING 2=COMPLETED 3=FAILED 4=SKIPPED")
    private Integer stageState;

    @Schema(description = "开始时间")
    private Date startTime;

    @Schema(description = "结束时间")
    private Date endTime;

    @Schema(description = "耗时毫秒")
    private Long durationMs;

    @Schema(description = "摘要")
    private String summaryText;

    @Schema(description = "错误信息")
    private String errorMessage;

    /**
     * 阶段结构化快照 —— 由 snapshotJson 反序列化得到，供观测页 Inspector 渲染
     * （子问题检索链路、证据门控决策、模型用量等）。无快照时为 null。
     */
    @Schema(description = "阶段结构化快照")
    private Map<String, Object> snapshot;
}
