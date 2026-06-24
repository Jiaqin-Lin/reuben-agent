package com.reubenagent.chat.session;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 会话删除结果。
 *
 * @author reuben
 * @since 2026-06-24
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ConversationRemovalResult {

    private String conversationId;
    private boolean conversationRemoved;
    private int removedTurnCount;
}
