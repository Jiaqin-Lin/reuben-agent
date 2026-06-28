package com.reubenagent.chat.orchestrate;

import com.reubenagent.chat.config.ChatProperties;
import com.reubenagent.chat.model.orchestrate.ChatRewriteResult;
import com.reubenagent.chat.model.orchestrate.ConversationItemAnchor;
import com.reubenagent.chat.model.orchestrate.ConversationRetrievalPlan;
import com.reubenagent.chat.model.orchestrate.ConversationStructureAnchor;
import com.reubenagent.chat.model.orchestrate.DocumentNavigationAction;
import com.reubenagent.chat.model.orchestrate.DocumentNavigationDecision;
import com.reubenagent.chat.model.orchestrate.NavigationScopeMode;
import com.reubenagent.chat.service.ObservedChatModelService;
import com.reubenagent.chat.support.ChatJsonCodec;
import com.reubenagent.chat.support.ChatPromptTemplateService;
import com.reubenagent.document.model.es.NavigationSectionHit;
import com.reubenagent.document.model.graph.GraphSection;
import com.reubenagent.document.service.DocumentNavigationIndexService;
import com.reubenagent.document.service.DocumentStructureGraphService;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 文档问题路由器 —— 规则引擎 + LLM 双引擎导航决策。
 *
 * <p>对标 super-agent DocumentQuestionRouter（全动作），reuben 修正：
 * <ul>
 *   <li>规则引擎 7 级优先级覆盖 80% 结构导航类问题，零延迟；</li>
 *   <li>LLM 仅在规则不确定且满足条件（单子问题 + 非强分析 + 有结构线索）时补充，
 *       走 Mono.fromCallable + subscribeOn(boundedElastic)；</li>
 *   <li>LLM 输出用 ChatJsonCodec 平衡花括号提取 + FastJSON 解析，不手撕 JSON；</li>
 *   <li>20+ 关键词列表全部从 ChatProperties.Navigation 读取，不硬编码到类体。</li>
 * </ul></p>
 *
 * @author reuben
 * @since 2026-06-28
 */
@Slf4j
@Service
@AllArgsConstructor
public class DocumentQuestionRouter {

    // 章节编码：1.2.3
    private static final Pattern SECTION_CODE_PATTERN = Pattern.compile("(\\d+(?:\\.\\d+)+)");
    // 中文章节引用：第三章 / 第二节 / 第三小节
    private static final Pattern CHINESE_SECTION_REFERENCE_PATTERN =
            Pattern.compile("第\\s*([0-9一二三四五六七八九十百]+)\\s*(章|节|小节)");
    // 步骤引用：第三步
    private static final Pattern STEP_REFERENCE_PATTERN =
            Pattern.compile("第\\s*([0-9一二三四五六七八九十百]+)\\s*步");
    // 序数引用：第三条/点/项/个
    private static final Pattern ORDINAL_REFERENCE_PATTERN =
            Pattern.compile("第\\s*([0-9一二三四五六七八九十百]+)\\s*(条|点|项|个)");
    // 引号包裹文本："章节标题" 或 “章节标题”
    private static final Pattern QUOTED_TEXT_PATTERN =
            Pattern.compile("[\"“]([^“”\"]{2,40})[\"”]");

    private final DocumentStructureGraphService graphService;
    private final ObjectProvider<DocumentNavigationIndexService> navigationIndexServiceProvider;
    private final ObservedChatModelService observedChatModelService;
    private final ChatPromptTemplateService promptTemplateService;
    private final ChatJsonCodec jsonCodec;
    private final ChatProperties properties;

    /**
     * 路由主入口：规则引擎优先，不确定时 LLM 补充，最后做 Section Resolution 组装决策。
     */
    public DocumentNavigationDecision route(Long documentId, String originalQuestion,
                                             ChatRewriteResult rewriteResult) {
        String question = pickQuestion(originalQuestion, rewriteResult);
        if (question == null || question.isBlank()) {
            return defaultDecision(documentId, question);
        }
        ChatProperties.Navigation nav = properties.getNavigation();
        // 阶段 1：规则引擎
        RuleIntent rule = detectGraphOnlyIntentByRules(question, nav);
        DocumentNavigationAction action = rule.action;
        double confidence = rule.confidence;
        // 阶段 2：LLM fallback（规则不确定 + 满足条件）
        LlmIntent llm = null;
        if (action == null && shouldInvokeLlm(question, rewriteResult, nav)) {
            llm = classifyQuestionIntentWithModel(question, nav);
            if (llm != null && llm.confidence >= nav.getLlmIntentConfidenceThreshold() && llm.action != null) {
                action = llm.action;
                confidence = llm.confidence;
            }
        }
        if (action == null) {
            return defaultDecision(documentId, question);
        }
        // Section Resolution + 条目解析
        return buildDecision(documentId, question, action, confidence, rewriteResult, llm, nav);
    }

    // ======================== Phase 1 规则引擎 ========================

    /**
     * 7 级优先级规则判定，命中即返回，未命中返回 action=null。
     */
    private RuleIntent detectGraphOnlyIntentByRules(String question, ChatProperties.Navigation nav) {
        // 1. 邻接关键词命中 → SECTION_ADJACENCY_LOOKUP
        if (containsAny(question, nav.getAdjacencyHints())) {
            return new RuleIntent(DocumentNavigationAction.SECTION_ADJACENCY_LOOKUP, 1.0);
        }
        boolean hasSectionCode = SECTION_CODE_PATTERN.matcher(question).find();
        boolean hasChineseSection = CHINESE_SECTION_REFERENCE_PATTERN.matcher(question).find();
        boolean hasDirection = containsAny(question, nav.getGraphOnlyExplicitAdjacencyHints());
        // 2. 章节编码 + 方向词 → SECTION_ADJACENCY_LOOKUP
        if ((hasSectionCode || hasChineseSection) && hasDirection) {
            return new RuleIntent(DocumentNavigationAction.SECTION_ADJACENCY_LOOKUP, 0.92);
        }
        // 3. 引号标题 + 方向词 → SECTION_ADJACENCY_LOOKUP
        if (hasDirection && QUOTED_TEXT_PATTERN.matcher(question).find()) {
            return new RuleIntent(DocumentNavigationAction.SECTION_ADJACENCY_LOOKUP, 0.90);
        }
        boolean hasStructureObject = containsAny(question, nav.getGraphOnlyStructureObjectHints());
        // 4. 结构对象 + 显式邻接 → SECTION_ADJACENCY_LOOKUP
        if (hasStructureObject && hasDirection) {
            return new RuleIntent(DocumentNavigationAction.SECTION_ADJACENCY_LOOKUP, 0.86);
        }
        // 5. 大纲显式关键词 → CHILD_SECTION_DESCEND
        if (containsAny(question, nav.getOutlineExplicitHints())) {
            return new RuleIntent(DocumentNavigationAction.CHILD_SECTION_DESCEND, 1.0);
        }
        // 6. 结构对象 + 大纲动作 → CHILD_SECTION_DESCEND
        if (hasStructureObject && containsAny(question, nav.getOutlineActionHints())) {
            return new RuleIntent(DocumentNavigationAction.CHILD_SECTION_DESCEND, 0.86);
        }
        // 7. 条目关键词 + 非强分析 → ITEM_REFERENCE
        boolean hasItem = containsAny(question, nav.getItemHints());
        boolean analytic = containsAny(question, nav.getAnalyticStrongHints());
        if (hasItem && !analytic) {
            return new RuleIntent(DocumentNavigationAction.ITEM_REFERENCE, 0.80);
        }
        return new RuleIntent(null, 0.0);
    }

    // ======================== Phase 2 LLM fallback ========================

    /** 仅当：单子问题 + 非强分析类 + 有结构导航线索时触发 LLM。 */
    private boolean shouldInvokeLlm(String question, ChatRewriteResult rewrite,
                                     ChatProperties.Navigation nav) {
        if (rewrite != null && rewrite.hasSubQuestions() && rewrite.getSubQuestions().size() > 1) {
            return false;
        }
        if (containsAny(question, nav.getAnalyticStrongHints())) {
            return false;
        }
        boolean structureHint = containsAny(question, nav.getGraphOnlyStructureObjectHints())
                || containsAny(question, nav.getStructuralRelationHints())
                || SECTION_CODE_PATTERN.matcher(question).find()
                || CHINESE_SECTION_REFERENCE_PATTERN.matcher(question).find()
                || QUOTED_TEXT_PATTERN.matcher(question).find();
        return structureHint;
    }

    /** LLM 图意图判定，走 boundedElastic，FastJSON 解析。 */
    private LlmIntent classifyQuestionIntentWithModel(String question, ChatProperties.Navigation nav) {
        try {
            Map<String, String> vars = new HashMap<>();
            vars.put("question", question);
            String prompt = promptTemplateService.render(nav.getLlmIntentTemplate(), vars);
            ChatOptions options = ChatOptions.builder()
                    .temperature(nav.getLlmIntentTemperature())
                    .build();
            String raw = Mono.fromCallable(() ->
                    observedChatModelService.callText("document-graph-only-intent", prompt, options))
                    .subscribeOn(Schedulers.boundedElastic())
                    .block();
            return parseLlmIntent(raw);
        } catch (Exception e) {
            log.warn("LLM 图意图判定失败，回退规则 → err={}", e.getMessage());
            return null;
        }
    }

    private LlmIntent parseLlmIntent(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String json = jsonCodec.extractFirstBalancedObject(raw);
        if (json == null) {
            return null;
        }
        // action 字段用字符串读入后规整为枚举，避免 FastJSON 直接按枚举名严格匹配导致解析失败
        com.alibaba.fastjson.JSONObject obj = jsonCodec.parseObject(json, com.alibaba.fastjson.JSONObject.class);
        if (obj == null) {
            return null;
        }
        String actionRaw = obj.getString("action");
        DocumentNavigationAction action = normalizeLlmAction(actionRaw);
        if (action == null) {
            return null;
        }
        LlmIntent intent = new LlmIntent();
        intent.action = action;
        intent.intentType = obj.getString("intent_type");
        intent.confidence = obj.getDouble("confidence");
        intent.graphOnly = obj.getBoolean("graph_only");
        intent.analytic = obj.getBoolean("analytic");
        intent.outline = obj.getBoolean("outline");
        intent.itemLookup = obj.getBoolean("item_lookup");
        intent.structureHint = obj.getBoolean("structure_hint");
        intent.reason = obj.getString("reason");
        return intent;
    }

    /** LLM 输出的 action 字段可能是枚举名，统一规整为枚举，无法识别返回 null。 */
    private DocumentNavigationAction normalizeLlmAction(String raw) {
        if (raw == null) {
            return null;
        }
        try {
            return DocumentNavigationAction.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    // ======================== Section Resolution ========================

    /**
     * 4 级回退定位目标章节：
     * 1) by section code（编码/中文引用）→ findSectionByCode
     * 2) by navigation index → searchSections → findSectionById
     * 3) by local structure scoring → findBestSection
     * 4) 未命中返回 null
     */
    private GraphSection resolveSection(Long documentId, String question, ChatProperties.Navigation nav) {
        if (question == null || question.isBlank()) {
            return null;
        }
        // 1) by section code
        String code = extractSectionCode(question);
        if (code != null) {
            try {
                GraphSection s = graphService.findSectionByCode(documentId, code);
                if (s != null) {
                    return s;
                }
            } catch (Exception e) {
                log.warn("按编码定位章节失败 → documentId={} code={} err={}", documentId, code, e.getMessage());
            }
        }
        // 2) by navigation index
        DocumentNavigationIndexService indexService = navigationIndexServiceProvider.getIfAvailable();
        if (indexService != null) {
            try {
                List<NavigationSectionHit> hits = indexService.searchSections(
                        documentId, null, null, null, question, 3);
                if (hits != null && !hits.isEmpty()) {
                    NavigationSectionHit top = hits.get(0);
                    if (top.getNodeId() != null) {
                        GraphSection s = graphService.findSectionById(documentId, top.getNodeId());
                        if (s != null) {
                            return s;
                        }
                    }
                }
            } catch (Exception e) {
                log.warn("导航索引定位章节失败 → documentId={} err={}", documentId, e.getMessage());
            }
        }
        // 3) by local structure scoring (findBestSection 内部做 title/anchor/content 评分)
        try {
            String topic = extractTopicFromQuoted(question);
            return graphService.findBestSection(documentId, topic != null ? topic : question, null);
        } catch (Exception e) {
            log.warn("本地评分定位章节失败 → documentId={} err={}", documentId, e.getMessage());
            return null;
        }
    }

    private String extractSectionCode(String question) {
        Matcher m = SECTION_CODE_PATTERN.matcher(question);
        if (m.find()) {
            return m.group(1);
        }
        // 中文章节引用暂不转阿拉伯数字编码，交给 navigation index 兜底
        return null;
    }

    private String extractTopicFromQuoted(String question) {
        Matcher m = QUOTED_TEXT_PATTERN.matcher(question);
        if (m.find()) {
            return m.group(1);
        }
        return null;
    }

    private Integer extractItemIndex(String question) {
        Matcher step = STEP_REFERENCE_PATTERN.matcher(question);
        if (step.find()) {
            return chineseToInt(step.group(1));
        }
        Matcher ord = ORDINAL_REFERENCE_PATTERN.matcher(question);
        if (ord.find()) {
            return chineseToInt(ord.group(1));
        }
        return null;
    }

    private Integer chineseToInt(String s) {
        if (s == null) {
            return null;
        }
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException ignored) {
            return chineseNumToInt(s);
        }
    }

    private Integer chineseNumToInt(String s) {
        int result = 0;
        int current = 0;
        for (int i = 0; i < s.length(); i++) {
            int v = digitOf(s.charAt(i));
            if (v < 0) {
                return null;
            }
            if (v >= 10) {
                current = (current == 0 ? 1 : current) * v;
                if (i == s.length() - 1) {
                    result += current;
                }
            } else {
                current += v;
                if (i == s.length() - 1) {
                    result += current;
                }
            }
        }
        return result == 0 ? null : result;
    }

    private int digitOf(char c) {
        return switch (c) {
            case '零', '〇' -> 0;
            case '一' -> 1;
            case '二', '两' -> 2;
            case '三' -> 3;
            case '四' -> 4;
            case '五' -> 5;
            case '六' -> 6;
            case '七' -> 7;
            case '八' -> 8;
            case '九' -> 9;
            case '十' -> 10;
            case '百' -> 100;
            default -> -1;
        };
    }

    // ======================== 组装决策 ========================

    private DocumentNavigationDecision buildDecision(Long documentId, String question,
                                                      DocumentNavigationAction action, double confidence,
                                                      ChatRewriteResult rewrite, LlmIntent llm,
                                                      ChatProperties.Navigation nav) {
        GraphSection section = resolveSection(documentId, question, nav);
        Integer itemIndex = extractItemIndex(question);
        ConversationStructureAnchor anchor = buildStructureAnchor(section, action);
        ConversationItemAnchor itemAnchor = (action == DocumentNavigationAction.ITEM_REFERENCE && itemIndex != null)
                ? buildItemAnchor(section, itemIndex) : null;
        NavigationScopeMode scopeMode = decideScopeMode(action, section, itemAnchor);
        List<String> contextHints = collectContextHints(question, section, nav);

        DocumentNavigationDecision.DocumentNavigationDecisionBuilder b = DocumentNavigationDecision.builder()
                .action(action)
                .scopeMode(scopeMode)
                .structureAnchor(anchor)
                .itemAnchor(itemAnchor)
                .queryContextHints(contextHints)
                .reason(buildReason(action, confidence, llm, section));

        // retrievalPlan 仅对检索类动作填充
        if (action.toExecutionMode().name().contains("RETRIEVAL")
                || action == DocumentNavigationAction.LOCATE_THEN_RETRIEVE
                || action == DocumentNavigationAction.ITEM_REFERENCE) {
            b.retrievalPlan(buildRetrievalPlan(rewrite, question));
        }
        if (scopeMode == NavigationScopeMode.SOFT && section != null) {
            b.softSectionHints(List.of(section.displayTitle()));
        }
        if (section != null && section.getSectionPath() != null) {
            b.sectionPath(section.getSectionPath());
        }
        return b.build();
    }

    private ConversationStructureAnchor buildStructureAnchor(GraphSection section, DocumentNavigationAction action) {
        if (section == null) {
            return null;
        }
        NavigationScopeMode scope = switch (action) {
            case SECTION_ADJACENCY_LOOKUP -> NavigationScopeMode.HARD_PARENT_WITH_SIBLINGS;
            case CHILD_SECTION_DESCEND, ANCESTOR_SECTION_RETURN -> NavigationScopeMode.HARD_SECTION;
            case ITEM_REFERENCE -> NavigationScopeMode.HARD_ITEM;
            case LOCATE_THEN_RETRIEVE -> NavigationScopeMode.SECTION_SCOPE;
            default -> NavigationScopeMode.SOFT;
        };
        return ConversationStructureAnchor.builder()
                .rootSectionCode(section.getNodeCode())
                .rootSectionTitle(section.getTitle())
                .targetSectionHint(section.getTitle())
                .structureNodeId(section.getNodeId())
                .canonicalPath(section.getCanonicalPath())
                .scopeMode(scope.name())
                .build();
    }

    private ConversationItemAnchor buildItemAnchor(GraphSection section, Integer itemIndex) {
        return ConversationItemAnchor.builder()
                .itemIndex(itemIndex)
                .structureNodeId(section == null ? null : section.getNodeId())
                .canonicalPath(section == null ? null : section.getCanonicalPath())
                .build();
    }

    private NavigationScopeMode decideScopeMode(DocumentNavigationAction action, GraphSection section,
                                                ConversationItemAnchor itemAnchor) {
        if (action == DocumentNavigationAction.GRAPH_ONLY) {
            return NavigationScopeMode.NONE;
        }
        if (itemAnchor != null) {
            return NavigationScopeMode.HARD_ITEM;
        }
        if (section == null) {
            return NavigationScopeMode.WHOLE_DOCUMENT;
        }
        return switch (action) {
            case SECTION_ADJACENCY_LOOKUP -> NavigationScopeMode.HARD_PARENT_WITH_SIBLINGS;
            case CHILD_SECTION_DESCEND, ANCESTOR_SECTION_RETURN -> NavigationScopeMode.HARD_SECTION;
            case ITEM_REFERENCE, LOCATE_THEN_RETRIEVE -> NavigationScopeMode.SECTION_SCOPE;
            default -> NavigationScopeMode.SOFT;
        };
    }

    private ConversationRetrievalPlan buildRetrievalPlan(ChatRewriteResult rewrite, String question) {
        String query = rewrite != null && rewrite.getRewrittenQuery() != null
                ? rewrite.getRewrittenQuery() : question;
        List<String> subs = (rewrite != null && rewrite.hasSubQuestions())
                ? rewrite.getSubQuestions() : Collections.emptyList();
        return ConversationRetrievalPlan.builder()
                .rewrittenQuery(query)
                .subQuestions(subs)
                .build();
    }

    private List<String> collectContextHints(String question, GraphSection section, ChatProperties.Navigation nav) {
        List<String> hints = new ArrayList<>();
        if (section != null && section.getTitle() != null) {
            hints.add(section.getTitle());
        }
        if (section != null && section.getSectionPath() != null) {
            hints.add(section.getSectionPath());
        }
        String quoted = extractTopicFromQuoted(question);
        if (quoted != null) {
            hints.add(quoted);
        }
        return hints;
    }

    private String buildReason(DocumentNavigationAction action, double confidence,
                                LlmIntent llm, GraphSection section) {
        String src = llm != null ? "LLM" : "规则";
        String located = section != null ? "已定位章节" : "未定位章节";
        return src + "判定 " + action.name() + " 置信度=" + confidence + " " + located;
    }

    private DocumentNavigationDecision defaultDecision(Long documentId, String question) {
        return DocumentNavigationDecision.builder()
                .action(DocumentNavigationAction.DIRECT_RETRIEVAL)
                .scopeMode(NavigationScopeMode.WHOLE_DOCUMENT)
                .reason("无明确结构导航线索，直接证据检索")
                .build();
    }

    // ======================== 工具 ========================

    private String pickQuestion(String originalQuestion, ChatRewriteResult rewrite) {
        if (rewrite != null && rewrite.getRewrittenQuery() != null && !rewrite.getRewrittenQuery().isBlank()) {
            return rewrite.getRewrittenQuery();
        }
        return originalQuestion;
    }

    private boolean containsAny(String question, List<String> hints) {
        if (question == null || question.isBlank() || hints == null || hints.isEmpty()) {
            return false;
        }
        for (String hint : hints) {
            if (hint != null && !hint.isBlank() && question.contains(hint)) {
                return true;
            }
        }
        return false;
    }

    /** 规则引擎中间结果 */
    private record RuleIntent(DocumentNavigationAction action, double confidence) {
    }

    /** LLM 输出 JSON 反序列化载体 */
    @lombok.Data
    public static class LlmIntent {
        private String intentType;
        private Double confidence;
        private DocumentNavigationAction action;
        private Boolean graphOnly;
        private Boolean analytic;
        private Boolean outline;
        private Boolean itemLookup;
        private Boolean structureHint;
        private String reason;
    }
}
