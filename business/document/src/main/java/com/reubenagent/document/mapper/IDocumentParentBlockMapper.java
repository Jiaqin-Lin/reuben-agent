package com.reubenagent.document.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.reubenagent.document.entity.DocumentParentBlock;
import org.apache.ibatis.annotations.Mapper;

/**
 * 文档父块 Mapper —— 映射 {@code reuben_agent_document_parent_block} 表。
 *
 * @author reuben
 * @since 2026-06-20
 */
@Mapper
public interface IDocumentParentBlockMapper extends BaseMapper<DocumentParentBlock> {
}
