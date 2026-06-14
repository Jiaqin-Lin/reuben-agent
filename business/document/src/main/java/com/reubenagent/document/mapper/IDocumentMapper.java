package com.reubenagent.document.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.reubenagent.document.entity.Document;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface IDocumentMapper extends BaseMapper<Document> {
}
