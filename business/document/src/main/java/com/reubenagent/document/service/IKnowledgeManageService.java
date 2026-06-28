package com.reubenagent.document.service;

import com.reubenagent.common.dto.PageVo;
import com.reubenagent.document.dto.KnowledgeRouteTraceQueryDto;
import com.reubenagent.document.dto.KnowledgeScopeDeleteDto;
import com.reubenagent.document.dto.KnowledgeScopeSaveDto;
import com.reubenagent.document.dto.KnowledgeTopicDeleteDto;
import com.reubenagent.document.dto.KnowledgeTopicSaveDto;
import com.reubenagent.document.dto.TopicDocumentRelationRemoveDto;
import com.reubenagent.document.dto.TopicDocumentRelationSaveDto;
import com.reubenagent.document.vo.DocumentProfileVo;
import com.reubenagent.document.vo.KnowledgeRouteTraceItemVo;
import com.reubenagent.document.vo.KnowledgeScopeItemVo;
import com.reubenagent.document.vo.KnowledgeTopicItemVo;
import com.reubenagent.document.vo.TopicDocumentRelationItemVo;

import java.util.List;

/**
 * 知识管理服务 —— Scope / Topic / Relation CRUD + 文档画像管理。
 *
 * @author reuben
 * @since 2026-06-28
 */
public interface IKnowledgeManageService {

    // ============ Scope ============

    KnowledgeScopeItemVo saveScope(KnowledgeScopeSaveDto dto);
    void deleteScope(KnowledgeScopeDeleteDto dto);
    List<KnowledgeScopeItemVo> listScopes();

    // ============ Topic ============

    KnowledgeTopicItemVo saveTopic(KnowledgeTopicSaveDto dto);
    void deleteTopic(KnowledgeTopicDeleteDto dto);
    List<KnowledgeTopicItemVo> listTopics(String scopeCode);

    // ============ Relation ============

    TopicDocumentRelationItemVo saveRelation(TopicDocumentRelationSaveDto dto);
    void removeRelation(TopicDocumentRelationRemoveDto dto);
    List<TopicDocumentRelationItemVo> listRelations(String topicCode);

    // ============ Profile ============

    DocumentProfileVo getProfile(Long documentId);
    DocumentProfileVo regenerateProfile(Long documentId);
    List<DocumentProfileVo> batchRegenerateProfiles(List<Long> documentIds);

    // ============ Route Trace ============

    PageVo<KnowledgeRouteTraceItemVo> pageQueryRouteTrace(KnowledgeRouteTraceQueryDto dto);
}
