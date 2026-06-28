package com.reubenagent.document.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.reubenagent.common.enums.DocumentManageCode;
import com.reubenagent.common.exception.DocumentException;
import com.reubenagent.document.config.DocumentProperties;
import com.reubenagent.document.config.DocumentProperties.KnowledgeRoute;
import com.reubenagent.document.entity.Document;
import com.reubenagent.document.entity.DocumentProfile;
import com.reubenagent.document.entity.KnowledgeScopeNode;
import com.reubenagent.document.entity.KnowledgeTopicNode;
import com.reubenagent.document.entity.TopicDocumentRelation;
import com.reubenagent.document.enums.KnowledgeRouteMode;
import com.reubenagent.document.enums.KnowledgeRouteStatus;
import com.reubenagent.document.mapper.IDocumentMapper;
import com.reubenagent.document.mapper.IDocumentProfileMapper;
import com.reubenagent.document.mapper.IKnowledgeScopeNodeMapper;
import com.reubenagent.document.mapper.IKnowledgeTopicNodeMapper;
import com.reubenagent.document.mapper.ITopicDocumentRelationMapper;
import com.reubenagent.document.model.es.RouteLexicalHit;
import com.reubenagent.document.model.route.DocumentRouteCandidate;
import com.reubenagent.document.model.route.KnowledgeRouteDecision;
import com.reubenagent.document.model.route.RouteQueryContext;
import com.reubenagent.document.model.route.RouteTraceContext;
import com.reubenagent.document.model.route.ScopeRouteCandidate;
import com.reubenagent.document.model.route.TopicRouteCandidate;
import com.reubenagent.document.service.KnowledgeRouteIndexService;
import com.reubenagent.document.service.KnowledgeRouteService;
import com.reubenagent.document.support.KnowledgeRouteTokenizer;
import com.reubenagent.framework.uid.UidGenerator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 知识路由引擎 —— 三级路由（scope → topic → document）。
 *
 * <p>评分公式：semanticMainScore = max(0, (cosine - floor) × weight)
 * + lexicalAssist = min(cap, esScore × weight)
 * + keywordEntityAssist = matchedTerms × entityHitScore。</p>
 *
 * <p>所有权重和阈值从 {@link DocumentProperties.KnowledgeRoute} 读取，可在 yml 调参。</p>
 *
 * @author reuben
 * @since 2026-06-28
 */
@Slf4j
@Service
public class KnowledgeRouteServiceImpl implements KnowledgeRouteService {

    private final IKnowledgeScopeNodeMapper scopeNodeMapper;
    private final IKnowledgeTopicNodeMapper topicNodeMapper;
    private final ITopicDocumentRelationMapper relationMapper;
    private final IDocumentMapper documentMapper;
    private final IDocumentProfileMapper documentProfileMapper;
    private final ObjectProvider<EmbeddingModel> embeddingModelProvider;
    private final ObjectProvider<KnowledgeRouteIndexService> routeIndexServiceProvider;
    private final KnowledgeRouteTraceService traceService;
    private final UidGenerator uidGenerator;
    private final KnowledgeRoute config;

    public KnowledgeRouteServiceImpl(IKnowledgeScopeNodeMapper scopeNodeMapper,
                                      IKnowledgeTopicNodeMapper topicNodeMapper,
                                      ITopicDocumentRelationMapper relationMapper,
                                      IDocumentMapper documentMapper,
                                      IDocumentProfileMapper documentProfileMapper,
                                      ObjectProvider<EmbeddingModel> embeddingModelProvider,
                                      ObjectProvider<KnowledgeRouteIndexService> routeIndexServiceProvider,
                                      KnowledgeRouteTraceService traceService,
                                      UidGenerator uidGenerator,
                                      DocumentProperties properties) {
        this.scopeNodeMapper = scopeNodeMapper;
        this.topicNodeMapper = topicNodeMapper;
        this.relationMapper = relationMapper;
        this.documentMapper = documentMapper;
        this.documentProfileMapper = documentProfileMapper;
        this.embeddingModelProvider = embeddingModelProvider;
        this.routeIndexServiceProvider = routeIndexServiceProvider;
        this.traceService = traceService;
        this.uidGenerator = uidGenerator;
        this.config = properties.getKnowledgeRoute();
    }

    // ==================== 对外接口 ====================

    @Override
    public KnowledgeRouteDecision route(String question, String rewriteQuestion) {
        if (!config.isEnabled()) {
            return KnowledgeRouteDecision.builder()
                    .scopes(List.of())
                    .topics(List.of())
                    .documents(List.of())
                    .confidence(BigDecimal.ZERO)
                    .routeStatus(KnowledgeRouteStatus.FAILED)
                    .reason("知识路由已关闭")
                    .build();
        }

        try {
            RouteQueryContext ctx = buildQueryContext(question, rewriteQuestion);
            List<ScopeRouteCandidate> scopes = rankScopes(ctx);
            List<TopicRouteCandidate> topics = rankTopics(ctx, scopes);
            List<DocumentRouteCandidate> documents = rankDocuments(ctx, scopes, topics);
            BigDecimal confidence = resolveConfidence(documents);
            KnowledgeRouteStatus status = determineStatus(confidence, documents);

            KnowledgeRouteDecision decision = KnowledgeRouteDecision.builder()
                    .scopes(scopes)
                    .topics(topics)
                    .documents(documents)
                    .confidence(confidence)
                    .routeStatus(status)
                    .reason(buildReason(scopes, topics, documents, confidence))
                    .build();

            log.info("知识路由完成: status={} confidence={} topDoc={}",
                    status, confidence,
                    decision.topDocument() != null ? decision.topDocument().getDocumentId() : "null");
            return decision;

        } catch (Exception e) {
            log.error("知识路由异常", e);
            return KnowledgeRouteDecision.builder()
                    .scopes(List.of())
                    .topics(List.of())
                    .documents(List.of())
                    .confidence(BigDecimal.ZERO)
                    .routeStatus(KnowledgeRouteStatus.FAILED)
                    .reason("路由异常: " + e.getMessage())
                    .build();
        }
    }

    @Override
    public void recordShadowRoute(String conversationId, Long turnId, Long selectedDocumentId,
                                  String question, String rewriteQuestion) {
        KnowledgeRouteDecision decision = route(question, rewriteQuestion);
        traceService.saveTrace(RouteTraceContext.builder()
                .conversationId(conversationId)
                .turnId(turnId)
                .question(question)
                .rewriteQuestion(rewriteQuestion)
                .mode(KnowledgeRouteMode.SHADOW.getMsg())
                .scopeCandidates(decision.getScopes())
                .topicCandidates(decision.getTopics())
                .documentCandidates(decision.getDocuments())
                .selectedDocumentId(selectedDocumentId)
                .confidence(decision.getConfidence())
                .routeStatus(decision.getRouteStatus().getCode())
                .errorMsg(decision.getRouteStatus() == KnowledgeRouteStatus.FAILED ? decision.getReason() : null)
                .build());
    }

    @Override
    public void recordAutoRoute(String conversationId, Long turnId, String question,
                                String rewriteQuestion, KnowledgeRouteDecision decision) {
        traceService.saveTrace(RouteTraceContext.builder()
                .conversationId(conversationId)
                .turnId(turnId)
                .question(question)
                .rewriteQuestion(rewriteQuestion)
                .mode(KnowledgeRouteMode.AUTO.getMsg())
                .scopeCandidates(decision.getScopes())
                .topicCandidates(decision.getTopics())
                .documentCandidates(decision.getDocuments())
                .selectedDocumentId(decision.topDocument() != null ? decision.topDocument().getDocumentId() : null)
                .confidence(decision.getConfidence())
                .routeStatus(decision.getRouteStatus().getCode())
                .errorMsg(decision.getRouteStatus() == KnowledgeRouteStatus.FAILED ? decision.getReason() : null)
                .build());
    }

    // ==================== 查询上下文 ====================

    private RouteQueryContext buildQueryContext(String question, String rewriteQuestion) {
        String routingText = question;
        if (rewriteQuestion != null && !rewriteQuestion.isBlank()) {
            routingText = question + " " + rewriteQuestion;
        }

        List<String> queryTerms = KnowledgeRouteTokenizer.tokenize(
                routingText, config.getTokenDelimiters(),
                config.getTokenMinLength(), config.getTokenMaxCount());

        float[] queryEmbedding = null;
        EmbeddingModel embeddingModel = embeddingModelProvider.getIfAvailable();
        if (embeddingModel != null) {
            try {
                List<float[]> embeddings = embeddingModel.embed(List.of(routingText));
                if (!embeddings.isEmpty()) {
                    queryEmbedding = embeddings.get(0);
                }
            } catch (Exception e) {
                log.warn("查询 embedding 失败，将跳过语义评分: {}", e.getMessage());
            }
        }

        return RouteQueryContext.builder()
                .originalQuestion(question)
                .rewriteQuestion(rewriteQuestion)
                .routingText(routingText)
                .queryTerms(queryTerms)
                .queryEmbedding(queryEmbedding)
                .build();
    }

    // ==================== 三级排序 ====================

    private List<ScopeRouteCandidate> rankScopes(RouteQueryContext ctx) {
        List<KnowledgeScopeNode> scopes = scopeNodeMapper.selectList(
                new LambdaQueryWrapper<KnowledgeScopeNode>().eq(KnowledgeScopeNode::getIsDeleted, 0));

        if (scopes.isEmpty()) {
            scopes = deriveScopesFromDocuments();
        }
        if (scopes.isEmpty()) {
            return List.of();
        }

        return rankByEntityType(ctx, scopes, "scope", config.getMaxScopeCandidates(),
                s -> buildScopeRouteText(s), s -> s.getScopeCode(), s -> s.getScopeName(),
                (s, score, reason) -> ScopeRouteCandidate.builder()
                        .scopeCode(s.getScopeCode())
                        .scopeName(s.getScopeName())
                        .score(score)
                        .reason(reason)
                        .build());
    }

    private List<TopicRouteCandidate> rankTopics(RouteQueryContext ctx, List<ScopeRouteCandidate> topScopes) {
        List<KnowledgeTopicNode> topics = topicNodeMapper.selectList(
                new LambdaQueryWrapper<KnowledgeTopicNode>().eq(KnowledgeTopicNode::getIsDeleted, 0));

        if (topics.isEmpty()) {
            topics = deriveTopicsFromProfiles();
        }
        if (topics.isEmpty()) {
            return List.of();
        }

        // scope 匹配加分
        Map<String, ScopeRouteCandidate> topScopeMap = topScopes.stream()
                .collect(Collectors.toMap(ScopeRouteCandidate::getScopeCode, s -> s, (a, b) -> a));

        String topScopeCode = topScopes.isEmpty() ? null : topScopes.get(0).getScopeCode();

        return rankByEntityType(ctx, topics, "topic", config.getMaxTopicCandidates(),
                t -> buildTopicRouteText(t), t -> t.getTopicCode(), t -> t.getTopicName(),
                (t, score, reason) -> {
                    // topic 级 scope 匹配加分
                    BigDecimal totalScore = score;
                    String finalReason = reason;
                    if (topScopeCode != null && topScopeCode.equals(t.getScopeCode())) {
                        totalScore = score.add(BigDecimal.valueOf(config.getScopeBoostTopic()));
                        finalReason = reason + " +scopeMatch";
                    }
                    return TopicRouteCandidate.builder()
                            .topicCode(t.getTopicCode())
                            .topicName(t.getTopicName())
                            .scopeCode(t.getScopeCode())
                            .score(totalScore)
                            .reason(finalReason)
                            .build();
                });
    }

    private List<DocumentRouteCandidate> rankDocuments(RouteQueryContext ctx,
                                                        List<ScopeRouteCandidate> topScopes,
                                                        List<TopicRouteCandidate> topTopics) {
        List<Document> docs = documentMapper.selectList(
                new LambdaQueryWrapper<Document>().eq(Document::getIsDeleted, 0));

        if (docs.isEmpty()) {
            return List.of();
        }

        String topScopeCode = topScopes.isEmpty() ? null : topScopes.get(0).getScopeCode();
        String topTopicCode = topTopics.isEmpty() ? null : topTopics.get(0).getTopicCode();

        // 构建 topic-doc relation 查找表
        Map<String, BigDecimal> relationScoreMap = new HashMap<>();
        if (topTopicCode != null) {
            List<TopicDocumentRelation> relations = relationMapper.selectList(
                    new LambdaQueryWrapper<TopicDocumentRelation>()
                            .eq(TopicDocumentRelation::getTopicCode, topTopicCode)
                            .eq(TopicDocumentRelation::getIsDeleted, 0));
            for (TopicDocumentRelation r : relations) {
                relationScoreMap.put(String.valueOf(r.getDocumentId()), r.getRelationScore());
            }
        }

        return rankByEntityType(ctx, docs, "document", config.getMaxDocumentCandidates(),
                d -> buildDocumentRouteText(d), d -> d.getDocumentName(), d -> d.getDocumentName(),
                (d, score, reason) -> {
                    BigDecimal totalScore = score;
                    String finalReason = reason;

                    // document 级 scope 匹配加分
                    if (topScopeCode != null && topScopeCode.equals(d.getKnowledgeScopeCode())) {
                        totalScore = totalScore.add(BigDecimal.valueOf(config.getScopeBoostDocument()));
                        finalReason = finalReason + " +scopeMatch";
                    }

                    // topic-doc relation 加分
                    BigDecimal relScore = relationScoreMap.get(String.valueOf(d.getId()));
                    if (relScore != null) {
                        totalScore = totalScore.add(
                                relScore.multiply(BigDecimal.valueOf(config.getRelationScoreWeight())));
                        finalReason = finalReason + " +relation";
                    }

                    List<String> tags = d.getDocumentTags() != null
                            ? Arrays.asList(d.getDocumentTags().split(","))
                            : Collections.emptyList();

                    return DocumentRouteCandidate.builder()
                            .documentId(d.getId())
                            .documentName(d.getDocumentName())
                            .knowledgeScopeCode(d.getKnowledgeScopeCode())
                            .knowledgeScopeName(d.getKnowledgeScopeName())
                            .businessCategory(d.getBusinessCategory())
                            .documentTags(tags)
                            .score(totalScore)
                            .reason(finalReason)
                            .build();
                });
    }

    // ==================== 通用排序引擎 ====================

    @FunctionalInterface
    interface RouteTextBuilder<T> {
        String build(T entity);
    }

    @FunctionalInterface
    interface EntityCodeExtractor<T> {
        String extractCode(T entity);
    }

    @FunctionalInterface
    interface EntityNameExtractor<T> {
        String extractName(T entity);
    }

    @FunctionalInterface
    interface CandidateBuilder<T, R> {
        R build(T entity, BigDecimal score, String reason);
    }

    private <T, R> List<R> rankByEntityType(RouteQueryContext ctx, List<T> entities,
                                              String entityType, int maxCandidates,
                                              RouteTextBuilder<T> routeTextBuilder,
                                              EntityCodeExtractor<T> codeExtractor,
                                              EntityNameExtractor<T> nameExtractor,
                                              CandidateBuilder<T, R> candidateBuilder) {
        // 1. 批量 embedding
        EmbeddingModel embeddingModel = embeddingModelProvider.getIfAvailable();
        List<float[]> embeddings = null;
        if (embeddingModel != null && ctx.getQueryEmbedding() != null) {
            try {
                List<String> texts = entities.stream().map(routeTextBuilder::build).toList();
                embeddings = embeddingModel.embed(texts);
            } catch (Exception e) {
                log.warn("批量 embedding 失败 (entityType={}): {}", entityType, e.getMessage());
            }
        }

        // 2. ES 词法搜索
        KnowledgeRouteIndexService indexService = routeIndexServiceProvider.getIfAvailable();
        Map<String, Double> lexicalScoreMap = new HashMap<>();
        if (indexService != null) {
            try {
                List<RouteLexicalHit> hits = indexService.search(ctx.getRoutingText(), entityType, maxCandidates * 2);
                for (RouteLexicalHit hit : hits) {
                    if (hit.getEntityCode() != null) {
                        lexicalScoreMap.put(hit.getEntityCode(), hit.getScore());
                    }
                    if (hit.getRouteId() != null && hit.getRouteId().startsWith(entityType + ":")) {
                        String code = hit.getRouteId().substring(hit.getRouteId().indexOf(':') + 1);
                        lexicalScoreMap.putIfAbsent(code, hit.getScore());
                    }
                }
            } catch (Exception e) {
                log.warn("ES 词法搜索失败 (entityType={}): {}", entityType, e.getMessage());
            }
        }

        // 3. 逐个打分
        List<R> candidates = new ArrayList<>();
        for (int i = 0; i < entities.size(); i++) {
            T entity = entities.get(i);
            String code = codeExtractor.extractCode(entity);
            String name = nameExtractor.extractName(entity);

            // 语义分
            double semanticScore = 0.0;
            if (embeddings != null && i < embeddings.size() && ctx.getQueryEmbedding() != null) {
                float[] entityEmb = embeddings.get(i);
                if (entityEmb != null) {
                    semanticScore = cosineSimilarity(ctx.getQueryEmbedding(), entityEmb);
                }
            }
            double semanticMainScore = Math.max(0, (semanticScore - config.getSemanticFloor()) * config.getSemanticWeight());

            // 词法分
            Double esScore = lexicalScoreMap.get(code);
            double lexicalAssist = esScore != null
                    ? Math.min(config.getLexicalCap(), esScore * config.getLexicalWeight())
                    : 0.0;

            // 实体词命中分
            long matchedTerms = ctx.getQueryTerms().stream()
                    .filter(term -> name != null && name.contains(term) || code != null && code.contains(term))
                    .count();
            double keywordAssist = matchedTerms * config.getEntityHitScore();

            double rawScore = semanticMainScore + lexicalAssist + keywordAssist;
            BigDecimal totalScore = BigDecimal.valueOf(rawScore).setScale(4, RoundingMode.HALF_UP);

            String reason = String.format("sem=%.2f lex=%.2f kw=%.1f raw=%.2f",
                    semanticMainScore, lexicalAssist, keywordAssist, rawScore);

            candidates.add(candidateBuilder.build(entity, totalScore, reason));
        }

        // 4. 按分数降序 + 截断
        candidates.sort((a, b) -> {
            BigDecimal sa = getScore(a);
            BigDecimal sb = getScore(b);
            return sb.compareTo(sa);
        });

        if (candidates.size() > maxCandidates) {
            return candidates.subList(0, maxCandidates);
        }
        return candidates;
    }

    /** 从候选对象中反射获取 score（避免强制类型转换） */
    @SuppressWarnings("unchecked")
    private BigDecimal getScore(Object candidate) {
        if (candidate instanceof ScopeRouteCandidate s) return s.getScore();
        if (candidate instanceof TopicRouteCandidate t) return t.getScore();
        if (candidate instanceof DocumentRouteCandidate d) return d.getScore();
        return BigDecimal.ZERO;
    }

    // ==================== 置信度与状态 ====================

    private BigDecimal resolveConfidence(List<DocumentRouteCandidate> documents) {
        if (documents == null || documents.isEmpty()) return BigDecimal.ZERO;
        if (documents.size() == 1) return BigDecimal.ONE;

        BigDecimal topScore = documents.get(0).getScore();
        BigDecimal secondScore = documents.get(1).getScore();
        BigDecimal base = topScore.add(secondScore)
                .add(BigDecimal.valueOf(config.getConfidenceNormalizerOffset()))
                .max(BigDecimal.valueOf(config.getConfidenceNormalizerBase()));

        return topScore.divide(base, 4, RoundingMode.HALF_UP);
    }

    private KnowledgeRouteStatus determineStatus(BigDecimal confidence, List<DocumentRouteCandidate> documents) {
        if (documents == null || documents.isEmpty()) return KnowledgeRouteStatus.FAILED;
        if (confidence.compareTo(BigDecimal.valueOf(config.getLowConfidenceThreshold())) < 0) {
            return KnowledgeRouteStatus.LOW_CONFIDENCE;
        }
        return KnowledgeRouteStatus.SUCCESS;
    }

    private String buildReason(List<ScopeRouteCandidate> scopes, List<TopicRouteCandidate> topics,
                               List<DocumentRouteCandidate> documents, BigDecimal confidence) {
        StringBuilder sb = new StringBuilder();
        if (!scopes.isEmpty()) {
            sb.append("topScope=").append(scopes.get(0).getScopeName())
                    .append("(").append(scopes.get(0).getScore()).append(")");
        }
        if (!topics.isEmpty()) {
            if (!sb.isEmpty()) sb.append(" ");
            sb.append("topTopic=").append(topics.get(0).getTopicName())
                    .append("(").append(topics.get(0).getScore()).append(")");
        }
        if (!documents.isEmpty()) {
            if (!sb.isEmpty()) sb.append(" ");
            sb.append("topDoc=").append(documents.get(0).getDocumentId())
                    .append("(").append(documents.get(0).getScore()).append(")");
        }
        return sb.toString();
    }

    // ==================== Fallback ====================

    /** 从 Document 表聚合 scope 信息（scope 表无数据时降级）。 */
    private List<KnowledgeScopeNode> deriveScopesFromDocuments() {
        List<Document> docs = documentMapper.selectList(
                new LambdaQueryWrapper<Document>()
                        .eq(Document::getIsDeleted, 0)
                        .isNotNull(Document::getKnowledgeScopeCode));
        Map<String, String> scopeMap = new LinkedHashMap<>();
        for (Document doc : docs) {
            if (doc.getKnowledgeScopeCode() != null && !doc.getKnowledgeScopeCode().isBlank()) {
                scopeMap.putIfAbsent(doc.getKnowledgeScopeCode(), doc.getKnowledgeScopeName());
            }
        }
        log.info("从 Document 表推导 scope: count={}", scopeMap.size());
        return scopeMap.entrySet().stream()
                .map(e -> KnowledgeScopeNode.builder()
                        .scopeCode(e.getKey())
                        .scopeName(e.getValue() != null ? e.getValue() : e.getKey())
                        .build())
                .toList();
    }

    /** 从 DocumentProfile.coreTopics 提取 topic（topic 表无数据时降级）。 */
    private List<KnowledgeTopicNode> deriveTopicsFromProfiles() {
        List<DocumentProfile> profiles = documentProfileMapper.selectList(
                new LambdaQueryWrapper<DocumentProfile>()
                        .eq(DocumentProfile::getIsDeleted, 0)
                        .isNotNull(DocumentProfile::getCoreTopics));
        Map<String, String> topicMap = new LinkedHashMap<>();
        for (DocumentProfile profile : profiles) {
            if (profile.getCoreTopics() != null && !profile.getCoreTopics().isBlank()) {
                try {
                    List<String> topics = com.alibaba.fastjson.JSON.parseArray(profile.getCoreTopics(), String.class);
                    if (topics != null) {
                        for (String topic : topics) {
                            if (topic != null && !topic.isBlank()) {
                                String code = topic.trim().toLowerCase().replaceAll("[\\s、，,]+", "-");
                                topicMap.putIfAbsent(code, topic.trim());
                            }
                        }
                    }
                } catch (Exception ignored) {
                    // 非 JSON 格式则整段作为单个 topic
                    topicMap.putIfAbsent(profile.getCoreTopics().trim(), profile.getCoreTopics().trim());
                }
            }
        }
        log.info("从 DocumentProfile 推导 topic: count={}", topicMap.size());
        return topicMap.entrySet().stream()
                .map(e -> KnowledgeTopicNode.builder()
                        .topicCode(e.getKey())
                        .topicName(e.getValue())
                        .build())
                .toList();
    }

    // ==================== 文本拼接 ====================

    private String buildScopeRouteText(KnowledgeScopeNode s) {
        return joinNonEmpty(s.getScopeName(), s.getDescription(), s.getAliases(), s.getExamples());
    }

    private String buildTopicRouteText(KnowledgeTopicNode t) {
        return joinNonEmpty(t.getTopicName(), t.getDescription(), t.getAliases(), t.getExamples());
    }

    private String buildDocumentRouteText(Document d) {
        DocumentProfile profile = getProfile(d.getId());
        String summary = profile != null ? profile.getDocumentSummary() : null;
        String coreTopics = profile != null ? profile.getCoreTopics() : null;
        return joinNonEmpty(d.getDocumentName(), summary, coreTopics);
    }

    private String joinNonEmpty(String... parts) {
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (p != null && !p.isBlank()) {
                if (!sb.isEmpty()) sb.append(" ");
                sb.append(p.trim());
            }
        }
        return sb.toString();
    }

    private DocumentProfile getProfile(Long documentId) {
        if (documentId == null) return null;
        return documentProfileMapper.selectOne(
                new LambdaQueryWrapper<DocumentProfile>()
                        .eq(DocumentProfile::getDocumentId, documentId)
                        .eq(DocumentProfile::getIsDeleted, 0));
    }

    // ==================== 数学工具 ====================

    static double cosineSimilarity(float[] a, float[] b) {
        if (a == null || b == null || a.length != b.length) return 0.0;
        double dot = 0.0, magA = 0.0, magB = 0.0;
        for (int i = 0; i < a.length; i++) {
            dot += (double) a[i] * b[i];
            magA += (double) a[i] * a[i];
            magB += (double) b[i] * b[i];
        }
        double denom = Math.sqrt(magA) * Math.sqrt(magB);
        return denom == 0 ? 0.0 : dot / denom;
    }
}
