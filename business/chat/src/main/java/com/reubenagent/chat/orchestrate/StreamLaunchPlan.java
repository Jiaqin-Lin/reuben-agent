package com.reubenagent.chat.orchestrate;

import com.reubenagent.chat.enums.ChatMode;
import lombok.Builder;
import lombok.Value;

import java.time.LocalDate;

/**
 * 流式启动计划 —— 由 {@link com.reubenagent.chat.dto.ChatStreamDto} 解析而来的强类型入参。
 *
 * <p>修正 super-agent 问题 2：DTO 全 String + 防御性 parse，这里直接用强类型字段。</p>
 *
 * @author reuben
 * @since 2026-06-24
 */
@Value
@Builder
public class StreamLaunchPlan {

    String question;
    String conversationId;
    ChatMode chatMode;
    Long selectedDocumentId;
    String selectedDocumentName;
    LocalDate currentDate;
    String currentDateText;
}
