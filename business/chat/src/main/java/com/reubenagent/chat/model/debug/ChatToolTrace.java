package com.reubenagent.chat.model.debug;

import com.reubenagent.chat.enums.ChatToolStatus;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

/**
 * 单次工具调用追踪（Phase 7 填充）。
 *
 * @author reuben
 * @since 2026-06-24
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChatToolTrace {

    /** 工具名（如 tavily_search） */
    private String toolName;
    /** 调用入参（JSON 字符串） */
    private String args;
    /** 调用结果（JSON 字符串 / 摘要） */
    private String result;
    /** 调用状态 */
    private ChatToolStatus status;
    /** 耗时毫秒 */
    private Long durationMs;
    /** 失败时的错误信息 */
    private String error;
}
