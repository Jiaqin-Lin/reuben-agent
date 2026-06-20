package com.reubenagent.document.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.reubenagent.document.entity.DocumentProfile;
import org.apache.ibatis.annotations.Mapper;

/**
 * 文档画像 Mapper —— 映射 {@code reuben_agent_document_profile} 表。
 *
 * @author reuben
 * @since 2026-06-20
 */
@Mapper
public interface IDocumentProfileMapper extends BaseMapper<DocumentProfile> {
}
