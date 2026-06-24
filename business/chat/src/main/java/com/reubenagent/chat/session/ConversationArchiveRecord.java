package com.reubenagent.chat.session;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Date;

/**
 * 会话归档记录 —— Store 层与 Service 层之间的会话数据载体。
 *
 * @author reuben
 * @since 2026-06-24
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ConversationArchiveRecord {

    private Long id;
    private String conversationId;
    private Integer sessionStatus;
    private Integer chatMode;
    private String title;
    private Long selectedDocumentId;
    private String selectedDocumentName;
    private Date createTime;
    private Date updateTime;
}
