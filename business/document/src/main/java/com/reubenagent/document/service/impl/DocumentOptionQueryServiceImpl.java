package com.reubenagent.document.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.reubenagent.document.entity.Document;
import com.reubenagent.document.enums.DocumentIndexStatusEnum;
import com.reubenagent.document.mapper.IDocumentMapper;
import com.reubenagent.document.service.IDocumentOptionQueryService;
import com.reubenagent.document.vo.KnowledgeDocumentOptionVo;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 知识文档轻量查询实现 —— 列出索引成功的文档供 chat 下拉。
 *
 * @author reuben
 * @since 2026-06-24
 */
@Slf4j
@Service
@AllArgsConstructor
public class DocumentOptionQueryServiceImpl implements IDocumentOptionQueryService {

    private final IDocumentMapper documentMapper;

    @Override
    public List<KnowledgeDocumentOptionVo> listOptions() {
        LambdaQueryWrapper<Document> wrapper = new LambdaQueryWrapper<Document>()
                .eq(Document::getIndexStatus, DocumentIndexStatusEnum.BUILD_SUCCESS.getCode())
                .orderByDesc(Document::getId);
        return documentMapper.selectList(wrapper).stream()
                .map(this::toOption)
                .toList();
    }

    private KnowledgeDocumentOptionVo toOption(Document doc) {
        return KnowledgeDocumentOptionVo.builder()
                .documentId(doc.getId())
                .documentName(doc.getDocumentName())
                .fileType(doc.getFileType())
                .indexStatus(doc.getIndexStatus())
                .knowledgeScopeName(doc.getKnowledgeScopeName())
                .createTime(doc.getCreateTime())
                .build();
    }
}
