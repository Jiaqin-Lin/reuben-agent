package com.reubenagent.chat.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.reubenagent.chat.entity.ChatTurn;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface IChatTurnMapper extends BaseMapper<ChatTurn> {
}
