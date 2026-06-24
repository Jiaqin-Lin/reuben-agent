package com.reubenagent.chat.service;

import com.reubenagent.document.vo.KnowledgeDocumentOptionVo;

import java.util.List;

/**
 * 文档选项查询适配 —— chat 侧按类型注入 document 模块实现（不声明 Maven 依赖）。
 *
 * @author reuben
 * @since 2026-06-24
 */
public interface ChatDocumentOptionService {

    /** 列出可选文档（已索引成功）。 */
    List<KnowledgeDocumentOptionVo> listDocumentOptions();
}
