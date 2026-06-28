package com.reubenagent.document.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.reubenagent.common.dto.PageVo;
import com.reubenagent.common.enums.DocumentManageCode;
import com.reubenagent.common.exception.DocumentException;
import com.reubenagent.document.dto.KnowledgeRouteTraceQueryDto;
import com.reubenagent.document.dto.KnowledgeScopeDeleteDto;
import com.reubenagent.document.dto.KnowledgeScopeSaveDto;
import com.reubenagent.document.dto.KnowledgeTopicDeleteDto;
import com.reubenagent.document.dto.KnowledgeTopicSaveDto;
import com.reubenagent.document.dto.TopicDocumentRelationRemoveDto;
import com.reubenagent.document.dto.TopicDocumentRelationSaveDto;
import com.reubenagent.document.entity.Document;
import com.reubenagent.document.entity.DocumentProfile;
import com.reubenagent.document.entity.KnowledgeRouteTrace;
import com.reubenagent.document.entity.KnowledgeScopeNode;
import com.reubenagent.document.entity.KnowledgeTopicNode;
import com.reubenagent.document.entity.TopicDocumentRelation;
import com.reubenagent.document.mapper.IDocumentMapper;
import com.reubenagent.document.mapper.IDocumentProfileMapper;
import com.reubenagent.document.mapper.IKnowledgeRouteTraceMapper;
import com.reubenagent.document.mapper.IKnowledgeScopeNodeMapper;
import com.reubenagent.document.mapper.IKnowledgeTopicNodeMapper;
import com.reubenagent.document.mapper.ITopicDocumentRelationMapper;
import com.reubenagent.document.service.IKnowledgeManageService;
import com.reubenagent.document.service.KnowledgeRouteIndexService;
import com.reubenagent.document.vo.DocumentProfileVo;
import com.reubenagent.document.vo.KnowledgeRouteTraceItemVo;
import com.reubenagent.document.vo.KnowledgeScopeItemVo;
import com.reubenagent.document.vo.KnowledgeTopicItemVo;
import com.reubenagent.document.vo.TopicDocumentRelationItemVo;
import com.reubenagent.framework.uid.UidGenerator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 知识管理服务实现。
 *
 * @author reuben
 * @since 2026-06-28
 */
@Slf4j
@Service
public class KnowledgeManageServiceImpl implements IKnowledgeManageService {

    private final IKnowledgeScopeNodeMapper scopeNodeMapper;
    private final IKnowledgeTopicNodeMapper topicNodeMapper;
    private final ITopicDocumentRelationMapper relationMapper;
    private final IDocumentMapper documentMapper;
    private final IDocumentProfileMapper documentProfileMapper;
    private final IKnowledgeRouteTraceMapper traceMapper;
    private final UidGenerator uidGenerator;
    private final ObjectProvider<KnowledgeRouteIndexService> routeIndexServiceProvider;

    public KnowledgeManageServiceImpl(IKnowledgeScopeNodeMapper scopeNodeMapper,
                                       IKnowledgeTopicNodeMapper topicNodeMapper,
                                       ITopicDocumentRelationMapper relationMapper,
                                       IDocumentMapper documentMapper,
                                       IDocumentProfileMapper documentProfileMapper,
                                       IKnowledgeRouteTraceMapper traceMapper,
                                       UidGenerator uidGenerator,
                                       ObjectProvider<KnowledgeRouteIndexService> routeIndexServiceProvider) {
        this.scopeNodeMapper = scopeNodeMapper;
        this.topicNodeMapper = topicNodeMapper;
        this.relationMapper = relationMapper;
        this.documentMapper = documentMapper;
        this.documentProfileMapper = documentProfileMapper;
        this.traceMapper = traceMapper;
        this.uidGenerator = uidGenerator;
        this.routeIndexServiceProvider = routeIndexServiceProvider;
    }

    // ==================== Scope CRUD ====================

    @Override
    @Transactional
    public KnowledgeScopeItemVo saveScope(KnowledgeScopeSaveDto dto) {
        KnowledgeScopeNode existing = scopeNodeMapper.selectOne(
                new LambdaQueryWrapper<KnowledgeScopeNode>()
                        .eq(KnowledgeScopeNode::getScopeCode, dto.getScopeCode())
                        .eq(KnowledgeScopeNode::getIsDeleted, 0));

        KnowledgeScopeNode node;
        if (existing != null) {
            node = existing;
            node.setScopeName(dto.getScopeName());
            node.setParentScopeCode(dto.getParentScopeCode());
            node.setDescription(dto.getDescription());
            node.setAliases(dto.getAliases());
            node.setExamples(dto.getExamples());
            node.setSortOrder(dto.getSortOrder() != null ? dto.getSortOrder() : 0);
            scopeNodeMapper.updateById(node);
            log.info("scope 已更新: scopeCode={}", dto.getScopeCode());
        } else {
            node = KnowledgeScopeNode.builder()
                    .id(uidGenerator.getUid())
                    .scopeCode(dto.getScopeCode())
                    .scopeName(dto.getScopeName())
                    .parentScopeCode(dto.getParentScopeCode())
                    .description(dto.getDescription())
                    .aliases(dto.getAliases())
                    .examples(dto.getExamples())
                    .sortOrder(dto.getSortOrder() != null ? dto.getSortOrder() : 0)
                    .build();
            scopeNodeMapper.insert(node);
            log.info("scope 已创建: scopeCode={}", dto.getScopeCode());
        }

        triggerRouteIndexRefresh();
        return toScopeVo(node);
    }

    @Override
    public void deleteScope(KnowledgeScopeDeleteDto dto) {
        KnowledgeScopeNode existing = scopeNodeMapper.selectOne(
                new LambdaQueryWrapper<KnowledgeScopeNode>()
                        .eq(KnowledgeScopeNode::getScopeCode, dto.getScopeCode())
                        .eq(KnowledgeScopeNode::getIsDeleted, 0));
        if (existing == null) {
            throw new DocumentException(DocumentManageCode.SCOPE_NOT_FOUND, dto.getScopeCode());
        }
        existing.setIsDeleted(1);
        scopeNodeMapper.updateById(existing);
        log.info("scope 已删除: scopeCode={}", dto.getScopeCode());
        triggerRouteIndexRefresh();
    }

    @Override
    public List<KnowledgeScopeItemVo> listScopes() {
        List<KnowledgeScopeNode> nodes = scopeNodeMapper.selectList(
                new LambdaQueryWrapper<KnowledgeScopeNode>()
                        .eq(KnowledgeScopeNode::getIsDeleted, 0)
                        .orderByAsc(KnowledgeScopeNode::getSortOrder));
        return nodes.stream().map(this::toScopeVo).toList();
    }

    // ==================== Topic CRUD ====================

    @Override
    @Transactional
    public KnowledgeTopicItemVo saveTopic(KnowledgeTopicSaveDto dto) {
        // 校验 scope 存在
        KnowledgeScopeNode scope = scopeNodeMapper.selectOne(
                new LambdaQueryWrapper<KnowledgeScopeNode>()
                        .eq(KnowledgeScopeNode::getScopeCode, dto.getScopeCode())
                        .eq(KnowledgeScopeNode::getIsDeleted, 0));
        if (scope == null) {
            throw new DocumentException(DocumentManageCode.SCOPE_NOT_FOUND, dto.getScopeCode());
        }

        KnowledgeTopicNode existing = topicNodeMapper.selectOne(
                new LambdaQueryWrapper<KnowledgeTopicNode>()
                        .eq(KnowledgeTopicNode::getTopicCode, dto.getTopicCode())
                        .eq(KnowledgeTopicNode::getIsDeleted, 0));

        KnowledgeTopicNode node;
        if (existing != null) {
            node = existing;
            node.setTopicName(dto.getTopicName());
            node.setScopeCode(dto.getScopeCode());
            node.setDescription(dto.getDescription());
            node.setAliases(dto.getAliases());
            node.setExamples(dto.getExamples());
            node.setAnswerShape(dto.getAnswerShape());
            node.setExecutionPreference(dto.getExecutionPreference());
            node.setSortOrder(dto.getSortOrder() != null ? dto.getSortOrder() : 0);
            topicNodeMapper.updateById(node);
            log.info("topic 已更新: topicCode={}", dto.getTopicCode());
        } else {
            node = KnowledgeTopicNode.builder()
                    .id(uidGenerator.getUid())
                    .topicCode(dto.getTopicCode())
                    .topicName(dto.getTopicName())
                    .scopeCode(dto.getScopeCode())
                    .description(dto.getDescription())
                    .aliases(dto.getAliases())
                    .examples(dto.getExamples())
                    .answerShape(dto.getAnswerShape())
                    .executionPreference(dto.getExecutionPreference())
                    .sortOrder(dto.getSortOrder() != null ? dto.getSortOrder() : 0)
                    .build();
            topicNodeMapper.insert(node);
            log.info("topic 已创建: topicCode={}", dto.getTopicCode());
        }

        triggerRouteIndexRefresh();
        return toTopicVo(node);
    }

    @Override
    public void deleteTopic(KnowledgeTopicDeleteDto dto) {
        KnowledgeTopicNode existing = topicNodeMapper.selectOne(
                new LambdaQueryWrapper<KnowledgeTopicNode>()
                        .eq(KnowledgeTopicNode::getTopicCode, dto.getTopicCode())
                        .eq(KnowledgeTopicNode::getIsDeleted, 0));
        if (existing == null) {
            throw new DocumentException(DocumentManageCode.TOPIC_NOT_FOUND, dto.getTopicCode());
        }
        existing.setIsDeleted(1);
        topicNodeMapper.updateById(existing);
        log.info("topic 已删除: topicCode={}", dto.getTopicCode());
        triggerRouteIndexRefresh();
    }

    @Override
    public List<KnowledgeTopicItemVo> listTopics(String scopeCode) {
        LambdaQueryWrapper<KnowledgeTopicNode> wrapper = new LambdaQueryWrapper<KnowledgeTopicNode>()
                .eq(KnowledgeTopicNode::getIsDeleted, 0)
                .orderByAsc(KnowledgeTopicNode::getSortOrder);
        if (scopeCode != null && !scopeCode.isBlank()) {
            wrapper.eq(KnowledgeTopicNode::getScopeCode, scopeCode);
        }
        return topicNodeMapper.selectList(wrapper).stream().map(this::toTopicVo).toList();
    }

    // ==================== Relation CRUD ====================

    @Override
    @Transactional
    public TopicDocumentRelationItemVo saveRelation(TopicDocumentRelationSaveDto dto) {
        KnowledgeTopicNode topic = topicNodeMapper.selectOne(
                new LambdaQueryWrapper<KnowledgeTopicNode>()
                        .eq(KnowledgeTopicNode::getTopicCode, dto.getTopicCode())
                        .eq(KnowledgeTopicNode::getIsDeleted, 0));
        if (topic == null) {
            throw new DocumentException(DocumentManageCode.TOPIC_NOT_FOUND, dto.getTopicCode());
        }

        Document doc = documentMapper.selectOne(
                new LambdaQueryWrapper<Document>()
                        .eq(Document::getId, dto.getDocumentId())
                        .eq(Document::getIsDeleted, 0));
        if (doc == null) {
            throw new DocumentException(DocumentManageCode.DOCUMENT_NOT_FOUND,
                    String.valueOf(dto.getDocumentId()));
        }

        TopicDocumentRelation existing = relationMapper.selectOne(
                new LambdaQueryWrapper<TopicDocumentRelation>()
                        .eq(TopicDocumentRelation::getTopicCode, dto.getTopicCode())
                        .eq(TopicDocumentRelation::getDocumentId, dto.getDocumentId())
                        .eq(TopicDocumentRelation::getIsDeleted, 0));

        TopicDocumentRelation relation;
        if (existing != null) {
            relation = existing;
            relation.setRelationScore(dto.getRelationScore());
            relation.setRelationSource(dto.getRelationSource());
            relation.setReason(dto.getReason());
            relationMapper.updateById(relation);
            log.info("relation 已更新: topic={} doc={}", dto.getTopicCode(), dto.getDocumentId());
        } else {
            relation = TopicDocumentRelation.builder()
                    .id(uidGenerator.getUid())
                    .topicCode(dto.getTopicCode())
                    .documentId(dto.getDocumentId())
                    .relationScore(dto.getRelationScore())
                    .relationSource(dto.getRelationSource() != null ? dto.getRelationSource() : "auto")
                    .reason(dto.getReason())
                    .build();
            relationMapper.insert(relation);
            log.info("relation 已创建: topic={} doc={}", dto.getTopicCode(), dto.getDocumentId());
        }

        triggerRouteIndexRefresh();
        return toRelationVo(relation, doc);
    }

    @Override
    public void removeRelation(TopicDocumentRelationRemoveDto dto) {
        TopicDocumentRelation existing = relationMapper.selectOne(
                new LambdaQueryWrapper<TopicDocumentRelation>()
                        .eq(TopicDocumentRelation::getTopicCode, dto.getTopicCode())
                        .eq(TopicDocumentRelation::getDocumentId, dto.getDocumentId())
                        .eq(TopicDocumentRelation::getIsDeleted, 0));
        if (existing == null) {
            throw new DocumentException(DocumentManageCode.RELATION_NOT_FOUND,
                    dto.getTopicCode() + "/" + dto.getDocumentId());
        }
        existing.setIsDeleted(1);
        relationMapper.updateById(existing);
        log.info("relation 已删除: topic={} doc={}", dto.getTopicCode(), dto.getDocumentId());
        triggerRouteIndexRefresh();
    }

    @Override
    public List<TopicDocumentRelationItemVo> listRelations(String topicCode) {
        List<TopicDocumentRelation> relations = relationMapper.selectList(
                new LambdaQueryWrapper<TopicDocumentRelation>()
                        .eq(TopicDocumentRelation::getTopicCode, topicCode)
                        .eq(TopicDocumentRelation::getIsDeleted, 0));

        // 批量查文档信息
        List<Long> docIds = relations.stream().map(TopicDocumentRelation::getDocumentId).distinct().toList();
        Map<Long, Document> docMap = Collections.emptyMap();
        if (!docIds.isEmpty()) {
            docMap = documentMapper.selectBatchIds(docIds).stream()
                    .collect(Collectors.toMap(Document::getId, d -> d, (a, b) -> a));
        }

        Map<Long, Document> finalDocMap = docMap;
        return relations.stream()
                .map(r -> toRelationVo(r, finalDocMap.get(r.getDocumentId())))
                .toList();
    }

    // ==================== Profile ====================

    @Override
    public DocumentProfileVo getProfile(Long documentId) {
        Document doc = documentMapper.selectById(documentId);
        if (doc == null) {
            throw new DocumentException(DocumentManageCode.DOCUMENT_NOT_FOUND, String.valueOf(documentId));
        }

        DocumentProfile profile = documentProfileMapper.selectOne(
                new LambdaQueryWrapper<DocumentProfile>()
                        .eq(DocumentProfile::getDocumentId, documentId)
                        .eq(DocumentProfile::getIsDeleted, 0));

        return toProfileVo(doc, profile);
    }

    @Override
    public DocumentProfileVo regenerateProfile(Long documentId) {
        Document doc = documentMapper.selectById(documentId);
        if (doc == null) {
            throw new DocumentException(DocumentManageCode.DOCUMENT_NOT_FOUND, String.valueOf(documentId));
        }

        DocumentProfile existingProfile = documentProfileMapper.selectOne(
                new LambdaQueryWrapper<DocumentProfile>()
                        .eq(DocumentProfile::getDocumentId, documentId)
                        .eq(DocumentProfile::getIsDeleted, 0));

        if (existingProfile != null) {
            // 标记为待重新生成
            existingProfile.setProfileStatus(1); // PENDING
            existingProfile.setProfileVersion(
                    existingProfile.getProfileVersion() != null ? existingProfile.getProfileVersion() + 1 : 1);
            documentProfileMapper.updateById(existingProfile);
            log.info("profile 已标记重新生成: documentId={} version={}", documentId, existingProfile.getProfileVersion());
        } else {
            log.warn("document {} 无已有画像，无法 regenerate", documentId);
            throw new DocumentException(DocumentManageCode.PROFILE_GENERATE_FAILED,
                    "文档无已有画像，请通过文档解析管线生成");
        }

        triggerRouteIndexRefresh();
        return getProfile(documentId);
    }

    @Override
    public List<DocumentProfileVo> batchRegenerateProfiles(List<Long> documentIds) {
        if (documentIds == null || documentIds.isEmpty()) return List.of();
        List<DocumentProfileVo> results = new ArrayList<>();
        for (Long docId : documentIds) {
            try {
                results.add(regenerateProfile(docId));
            } catch (Exception e) {
                log.error("regenerate profile 失败: documentId={}", docId, e);
            }
        }
        return results;
    }

    // ==================== Route Trace ====================

    @Override
    public PageVo<KnowledgeRouteTraceItemVo> pageQueryRouteTrace(KnowledgeRouteTraceQueryDto dto) {
        LambdaQueryWrapper<KnowledgeRouteTrace> wrapper = new LambdaQueryWrapper<KnowledgeRouteTrace>()
                .eq(KnowledgeRouteTrace::getIsDeleted, 0)
                .orderByDesc(KnowledgeRouteTrace::getCreateTime);

        if (dto.getConversationId() != null && !dto.getConversationId().isBlank()) {
            wrapper.eq(KnowledgeRouteTrace::getConversationId, dto.getConversationId());
        }
        if (dto.getMode() != null && !dto.getMode().isBlank()) {
            wrapper.eq(KnowledgeRouteTrace::getMode, dto.getMode());
        }
        if (dto.getRouteStatus() != null) {
            wrapper.eq(KnowledgeRouteTrace::getRouteStatus, dto.getRouteStatus());
        }

        IPage<KnowledgeRouteTrace> page = traceMapper.selectPage(
                new Page<>(dto.getPageNo(), dto.getPageSize()), wrapper);

        List<KnowledgeRouteTraceItemVo> records = page.getRecords().stream()
                .map(this::toTraceVo)
                .toList();

        return PageVo.of(page.getTotal(), dto.getPageNo(), dto.getPageSize(), records);
    }

    // ==================== 转换方法 ====================

    private KnowledgeScopeItemVo toScopeVo(KnowledgeScopeNode node) {
        return KnowledgeScopeItemVo.builder()
                .id(node.getId())
                .scopeCode(node.getScopeCode())
                .scopeName(node.getScopeName())
                .parentScopeCode(node.getParentScopeCode())
                .description(node.getDescription())
                .aliases(node.getAliases())
                .examples(node.getExamples())
                .sortOrder(node.getSortOrder())
                .build();
    }

    private KnowledgeTopicItemVo toTopicVo(KnowledgeTopicNode node) {
        return KnowledgeTopicItemVo.builder()
                .id(node.getId())
                .topicCode(node.getTopicCode())
                .topicName(node.getTopicName())
                .scopeCode(node.getScopeCode())
                .description(node.getDescription())
                .aliases(node.getAliases())
                .examples(node.getExamples())
                .answerShape(node.getAnswerShape())
                .executionPreference(node.getExecutionPreference())
                .sortOrder(node.getSortOrder())
                .build();
    }

    private TopicDocumentRelationItemVo toRelationVo(TopicDocumentRelation relation, Document doc) {
        return TopicDocumentRelationItemVo.builder()
                .id(relation.getId())
                .topicCode(relation.getTopicCode())
                .documentId(relation.getDocumentId())
                .documentName(doc != null ? doc.getDocumentName() : null)
                .scopeName(doc != null ? doc.getKnowledgeScopeName() : null)
                .relationScore(relation.getRelationScore())
                .relationSource(relation.getRelationSource())
                .reason(relation.getReason())
                .build();
    }

    private DocumentProfileVo toProfileVo(Document doc, DocumentProfile profile) {
        DocumentProfileVo.DocumentProfileVoBuilder builder = DocumentProfileVo.builder()
                .documentId(doc.getId())
                .documentName(doc.getDocumentName());
        if (profile != null) {
            builder.id(profile.getId())
                    .profileVersion(profile.getProfileVersion())
                    .documentSummary(profile.getDocumentSummary())
                    .documentType(profile.getDocumentType())
                    .coreTopics(profile.getCoreTopics())
                    .exampleQuestions(profile.getExampleQuestions())
                    .graphFriendly(profile.getGraphFriendly())
                    .supportsGraphOutline(profile.getSupportsGraphOutline())
                    .supportsItemLookup(profile.getSupportsItemLookup())
                    .supportsGraphAssist(profile.getSupportsGraphAssist())
                    .profileSource(profile.getProfileSource())
                    .profileStatus(profile.getProfileStatus())
                    .errorMsg(profile.getErrorMsg())
                    .createTime(profile.getCreateTime())
                    .editTime(profile.getUpdateTime());
        }
        return builder.build();
    }

    private KnowledgeRouteTraceItemVo toTraceVo(KnowledgeRouteTrace trace) {
        return KnowledgeRouteTraceItemVo.builder()
                .id(trace.getId())
                .conversationId(trace.getConversationId())
                .turnId(trace.getTurnId())
                .question(trace.getQuestion())
                .rewriteQuestion(trace.getRewriteQuestion())
                .mode(trace.getMode())
                .topScopesJson(trace.getTopScopesJson())
                .topTopicsJson(trace.getTopTopicsJson())
                .topDocumentsJson(trace.getTopDocumentsJson())
                .selectedDocumentId(trace.getSelectedDocumentId())
                .hitSelectedDocument(trace.getHitSelectedDocument())
                .confidence(trace.getConfidence())
                .routeStatus(trace.getRouteStatus())
                .errorMsg(trace.getErrorMsg())
                .createTime(trace.getCreateTime())
                .build();
    }

    // ==================== 辅助 ====================

    private void triggerRouteIndexRefresh() {
        KnowledgeRouteIndexService indexService = routeIndexServiceProvider.getIfAvailable();
        if (indexService != null) {
            try {
                indexService.refreshAll();
            } catch (Exception e) {
                log.warn("触发路由索引刷新失败", e);
            }
        }
    }
}
