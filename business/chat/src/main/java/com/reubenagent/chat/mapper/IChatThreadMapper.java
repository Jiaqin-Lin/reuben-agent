package com.reubenagent.chat.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.reubenagent.chat.entity.ChatThread;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface IChatThreadMapper extends BaseMapper<ChatThread> {
}
