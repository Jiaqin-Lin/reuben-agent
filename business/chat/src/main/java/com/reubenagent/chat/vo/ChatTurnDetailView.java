package com.reubenagent.chat.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 轮次详情视图 —— 轮次基本信息 + 全链路 stage 追踪。
 *
 * @author reuben
 * @since 2026-06-27
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "轮次详情（含追踪）")
public class ChatTurnDetailView {

    @Schema(description = "轮次基本信息")
    private ChatTurnVo turn;

    @Schema(description = "全链路 stage 追踪")
    private List<ChatTraceStageView> stageTraces;
}
