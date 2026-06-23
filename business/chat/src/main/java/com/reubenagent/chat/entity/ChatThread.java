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
 * ReAct 线程（一个 conversation 一个线程）。
 *
 * @author reuben
 * @since 2026-06-23
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("reuben_agent_chat_thread")
@EqualsAndHashCode(callSuper = true)
public class ChatThread extends BaseTableData {

    @TableId(type = IdType.INPUT)
    private Long id;

    /** 线程ID（=conversationId） */
    private String threadId;

    /** 线程名（=conversationId） */
    private String threadName;

    private String latestCheckpointId;
}
