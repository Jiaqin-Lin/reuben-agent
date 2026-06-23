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

import java.util.Date;

/**
 * 会话记忆摘要（一 conversation 一行）。
 *
 * @author reuben
 * @since 2026-06-23
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("reuben_agent_chat_memory_summary")
@EqualsAndHashCode(callSuper = true)
public class ChatMemorySummary extends BaseTableData {

    @TableId(type = IdType.INPUT)
    private Long id;

    /** 会话ID */
    private String conversationId;

    /** 已覆盖到的最近轮次ID */
    private Long coveredTurnId;

    /** 已覆盖轮次数 */
    private Integer coveredTurnCount;

    /** 压缩次数 */
    private Integer compressionCount;

    /** 摘要版本 */
    private Integer summaryVersion;

    /** 摘要文本 */
    private String summaryText;

    /** 摘要结构JSON */
    private String summaryJson;

    /** 最近源轮次时间 */
    private Date lastSourceEditTime;
}
