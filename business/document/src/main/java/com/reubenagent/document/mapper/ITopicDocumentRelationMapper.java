package com.reubenagent.document.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.reubenagent.document.entity.TopicDocumentRelation;
import org.apache.ibatis.annotations.Mapper;

/**
 * 主题-文档关联 Mapper。
 *
 * @author reuben
 * @since 2026-06-28
 */
@Mapper
public interface ITopicDocumentRelationMapper extends BaseMapper<TopicDocumentRelation> {
}
