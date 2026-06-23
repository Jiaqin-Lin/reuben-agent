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
 * ReAct 工作记忆检查点（自建轻量表，脱离 Alibaba MysqlSaver）。
 *
 * @author reuben
 * @since 2026-06-23
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("reuben_agent_chat_checkpoint")
@EqualsAndHashCode(callSuper = true)
public class ChatCheckpoint extends BaseTableData {

    @TableId(type = IdType.INPUT)
    private Long id;

    /** 线程ID（=conversationId） */
    private String threadId;

    private String checkpointId;

    private String parentCheckpointId;

    /** 消息列表JSON */
    private String messagesJson;

    /** 状态JSON */
    private String stateJson;
}
