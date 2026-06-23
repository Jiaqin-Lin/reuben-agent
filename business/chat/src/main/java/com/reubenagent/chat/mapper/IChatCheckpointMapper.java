package com.reubenagent.chat.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.reubenagent.chat.entity.ChatCheckpoint;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface IChatCheckpointMapper extends BaseMapper<ChatCheckpoint> {
}
