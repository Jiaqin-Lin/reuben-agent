package com.reubenagent.document.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Date;
import java.util.List;

/**
 * 任务日志查询响应 VO —— 含任务头信息 + 日志列表。
 *
 * @author reuben
 * @since 2026-06-28
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "任务日志查询结果")
public class DocumentTaskLogQueryVo {

    @Schema(description = "任务ID")
    private Long taskId;

    @Schema(description = "文档ID")
    private Long documentId;

    @Schema(description = "任务类型：1=解析路由 2=构建索引")
    private Integer taskType;

    @Schema(description = "任务状态")
    private Integer taskStatus;

    @Schema(description = "当前阶段")
    private Integer currentStage;

    @Schema(description = "开始时间")
    private Date startTime;

    @Schema(description = "结束时间")
    private Date finishTime;

    @Schema(description = "耗时（毫秒）")
    private Long costMillis;

    @Schema(description = "错误码")
    private String errorCode;

    @Schema(description = "错误信息")
    private String errorMsg;

    @Schema(description = "日志总数")
    private Long total;

    @Schema(description = "日志列表")
    private List<DocumentTaskLogVo> logs;
}
