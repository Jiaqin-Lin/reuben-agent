package com.reubenagent.document.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.reubenagent.document.entity.KnowledgeTopicNode;
import org.apache.ibatis.annotations.Mapper;

/**
 * 知识主题节点 Mapper。
 *
 * @author reuben
 * @since 2026-06-28
 */
@Mapper
public interface IKnowledgeTopicNodeMapper extends BaseMapper<KnowledgeTopicNode> {
}
