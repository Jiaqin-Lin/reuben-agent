package com.reubenagent.document.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.reubenagent.document.entity.DocumentStructureNode;
import org.apache.ibatis.annotations.Mapper;

/**
 * 文档结构节点 Mapper。
 *
 * @author reuben
 * @since 2026-06-19
 */
@Mapper
public interface IDocumentStructureNodeMapper extends BaseMapper<DocumentStructureNode> {
}
