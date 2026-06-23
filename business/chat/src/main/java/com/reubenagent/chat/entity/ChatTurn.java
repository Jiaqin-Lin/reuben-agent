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
 * 对话轮次。JSON 列统一以 String 存储，由 Store 做 (de)serialize。
 *
 * @author reuben
 * @since 2026-06-23
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("reuben_agent_chat_turn")
@EqualsAndHashCode(callSuper = true)
public class ChatTurn extends BaseTableData {

    @TableId(type = IdType.INPUT)
    private Long id;

    /** 会话ID */
    private String conversationId;

    /** 用户提问 */
    private String userPrompt;

    /** 回答内容 */
    private String replyContent;

    /** 推理过程JSON */
    private String reasoningNoteList;

    /** 引用快照JSON */
    private String sourceSnapshotList;

    /** 追问建议JSON */
    private String followupSuggestionList;

    /** 工具调用追踪JSON */
    private String toolTraceList;

    /** 调试追踪JSON */
    private String debugTraceJson;

    /** 轮次状态：1=执行中 2=完成 3=失败 4=停止 */
    private Integer turnStatus;

    /** 执行模式 */
    private Integer executionMode;

    /** 收尾说明 */
    private String finishNote;

    /** 首字延迟毫秒 */
    private Long firstTokenLatencyMs;

    /** 总耗时毫秒 */
    private Long totalLatencyMs;
}
