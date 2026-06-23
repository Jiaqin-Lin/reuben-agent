package com.reubenagent.chat.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.reubenagent.chat.entity.ChatConversation;
import org.apache.ibatis.annotations.Mapper;

/**
 * 对话会话 Mapper。
 *
 * @author reuben
 * @since 2026-06-23
 */
@Mapper
public interface IChatConversationMapper extends BaseMapper<ChatConversation> {
}
