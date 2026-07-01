package com.reubenagent.chat.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Date;
import java.util.List;

/**
 * 会话详情视图 —— 包含基本信息 + 轮次数 + 最近一轮。
 *
 * @author reuben
 * @since 2026-06-24
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "会话详情")
public class ConversationView {

    @Schema(description = "会话ID")
    private String conversationId;

    @Schema(description = "对话模式")
    private Integer chatMode;

    @Schema(description = "会话状态")
    private Integer sessionStatus;

    @Schema(description = "会话标题")
    private String title;

    @Schema(description = "选中文档ID")
    private Long selectedDocumentId;

    @Schema(description = "选中文档名")
    private String selectedDocumentName;

    @Schema(description = "轮次数")
    private Long turnCount;

    @Schema(description = "最近一轮")
    private ChatTurnVo latestTurn;

    @Schema(description = "最近若干轮（用于前端渲染历史）")
    private List<ChatTurnVo> recentTurns;

    @Schema(description = "最近用户问题")
    private String latestUserMessage;

    @Schema(description = "最近助手回答")
    private String latestAssistantMessage;

    @Schema(description = "消息数（用户+助手）")
    private Integer messageCount;

    @Schema(description = "ReAct 检查点数")
    private Integer checkpointCount;

    @Schema(description = "长期摘要快照")
    private ConversationMemorySummaryVo memorySummary;

    @Schema(description = "创建时间")
    private Date createTime;

    @Schema(description = "更新时间")
    private Date updateTime;
}
