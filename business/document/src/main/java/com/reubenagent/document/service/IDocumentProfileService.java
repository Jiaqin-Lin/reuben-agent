package com.reubenagent.document.service;

import com.reubenagent.document.entity.DocumentProfile;
import com.reubenagent.document.entity.DocumentStructureNode;
import com.reubenagent.document.model.DocumentParseResult;

import java.util.List;
import java.util.Optional;

/**
 * 文档画像服务 —— 为已解析的文档自动生成结构化画像。
 *
 * <p>画像包含文档摘要、类型分类、核心主题、示例问题及图结构适配能力，
 * 用于知识路由的语义匹配。</p>
 *
 * @author reuben
 * @since 2026-06-20
 */
public interface IDocumentProfileService {

    /**
     * 根据解析结果和结构节点生成文档画像。
     *
     * <p>在文档首次解析完成后调用，综合文档文本内容和结构信息生成画像，
     * 同时回填 Document 表的元数据字段（knowledgeScopeCode 等）。</p>
     *
     * @param documentId     文档 ID
     * @param parseResult    解析结果（含纯文本）
     * @param structureNodes 持久化后的结构节点列表
     * @return 生成的文档画像
     */
    DocumentProfile generateProfile(Long documentId,
                                     DocumentParseResult parseResult,
                                     List<DocumentStructureNode> structureNodes);

    /**
     * 根据文档 ID 查询画像。
     *
     * @param documentId 文档 ID
     * @return 文档画像，不存在时返回 {@code Optional.empty()}
     */
    Optional<DocumentProfile> getByDocumentId(Long documentId);
}
