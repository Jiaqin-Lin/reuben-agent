package com.reubenagent.document.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Date;

/**
 * 任务日志条目 VO。
 *
 * @author reuben
 * @since 2026-06-28
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "任务日志条目")
public class DocumentTaskLogVo {

    @Schema(description = "日志ID")
    private Long id;

    @Schema(description = "阶段类型：1~8")
    private Integer stageType;

    @Schema(description = "事件类型：1=开始 2=完成 3=失败 4=推荐 5=用户调整 6=用户确认")
    private Integer eventType;

    @Schema(description = "日志级别：1=INFO 2=WARN 3=ERROR")
    private Integer logLevel;

    @Schema(description = "日志内容")
    private String content;

    @Schema(description = "结构化详情 JSON")
    private String detailJson;

    @Schema(description = "创建时间")
    private Date createTime;
}
