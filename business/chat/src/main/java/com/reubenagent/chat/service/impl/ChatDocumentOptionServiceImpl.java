package com.reubenagent.chat.service.impl;

import com.reubenagent.chat.enums.ChatErrorCode;
import com.reubenagent.chat.exception.ChatException;
import com.reubenagent.chat.service.ChatDocumentOptionService;
import com.reubenagent.document.service.IDocumentOptionQueryService;
import com.reubenagent.document.vo.KnowledgeDocumentOptionVo;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;

/**
 * 文档选项查询适配实现 —— 委托 document 模块。
 *
 * <p>document 模块在 launcher 聚合时可见；用 {@link ObjectProvider} 容错，
 * 缺失时返回空列表并 warn（不抛异常阻断会话创建流程）。</p>
 *
 * @author reuben
 * @since 2026-06-24
 */
@Slf4j
@Service
@AllArgsConstructor
public class ChatDocumentOptionServiceImpl implements ChatDocumentOptionService {

    private final ObjectProvider<IDocumentOptionQueryService> documentOptionQueryProvider;

    @Override
    public List<KnowledgeDocumentOptionVo> listDocumentOptions() {
        IDocumentOptionQueryService queryService = documentOptionQueryProvider.getIfAvailable();
        if (queryService == null) {
            log.warn("document 模块未启用，返回空文档选项列表");
            return Collections.emptyList();
        }
        try {
            return queryService.listOptions();
        } catch (Exception e) {
            log.warn("查询文档选项失败 → err={}", e.getMessage());
            throw new ChatException(ChatErrorCode.RETRIEVE_FAILED, "文档选项查询失败", e);
        }
    }
}
