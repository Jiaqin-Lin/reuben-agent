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
 * 对话会话。
 *
 * @author reuben
 * @since 2026-06-23
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("reuben_agent_chat_conversation")
@EqualsAndHashCode(callSuper = true)
public class ChatConversation extends BaseTableData {

    @TableId(type = IdType.INPUT)
    private Long id;

    /** 会话业务ID */
    private String conversationId;

    /** 会话状态：1=空闲 2=执行中 */
    private Integer sessionStatus;

    /** 对话模式：1=DOCUMENT 2=OPEN_CHAT 3=AUTO_DOCUMENT */
    private Integer chatMode;

    /** 会话标题 */
    private String title;

    /** 选中文档ID */
    private Long selectedDocumentId;

    /** 选中文档名快照 */
    private String selectedDocumentName;
}
