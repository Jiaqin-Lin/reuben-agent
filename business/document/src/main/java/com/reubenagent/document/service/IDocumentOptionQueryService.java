package com.reubenagent.document.service;

import com.reubenagent.document.vo.KnowledgeDocumentOptionVo;

import java.util.List;

/**
 * 知识文档轻量查询服务 —— 供 chat 等下游模块按类型注入，列出可选文档。
 *
 * <p>不引入 chat 对 document 的编译期依赖（business 模块间禁止相互依赖），
 * 靠 launcher 聚合到同一 classpath。</p>
 *
 * @author reuben
 * @since 2026-06-24
 */
public interface IDocumentOptionQueryService {

    /**
     * 列出可选文档（已索引成功），用于会话创建时的文档下拉。
     *
     * @return 文档选项列表
     */
    List<KnowledgeDocumentOptionVo> listOptions();
}
