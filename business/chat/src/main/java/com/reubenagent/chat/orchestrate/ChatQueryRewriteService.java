package com.reubenagent.chat.orchestrate;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.reubenagent.chat.config.ChatProperties;
import com.reubenagent.chat.model.memory.ChatMemoryContext;
import com.reubenagent.chat.model.orchestrate.ChatRewriteResult;
import com.reubenagent.chat.service.ObservedChatModelService;
import com.reubenagent.chat.support.ChatJsonCodec;
import com.reubenagent.chat.support.ChatPromptNames;
import com.reubenagent.chat.support.ChatPromptTemplateService;
import com.reubenagent.chat.support.ChatTexts;
import com.reubenagent.chat.trace.ChatTraceRecorder;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 查询改写服务 —— 把用户原始问题改写为独立可答的形式，必要时拆分为多个子问题。
 *
 * <p>对标 super-agent {@code ChatQueryRewriteService}，修正：
 * <ul>
 *   <li>改写阈值（needsRewriteNoHistoryChars / needsRewriteWithHistoryChars / maxSubQuestions）进 {@link ChatProperties.Rewrite}；</li>
 *   <li>多问号 / 分号 / 编号 / 多行 / "分别" 判定用预编译 {@link Pattern}；</li>
 *   <li>LLM 输出 JSON 提取用 {@link ChatJsonCodec#extractFirstBalancedObject}（替代 super-agent 贪婪正则）；</li>
 *   <li>失败 warn + 走规则 fallback + 落 trace（REWRITE stage），不抛异常中断主流程。</li>
 * </ul></p>
 *
 * @author reuben
 * @since 2026-06-25
 */
@Slf4j
@Service
@AllArgsConstructor
public class ChatQueryRewriteService {

    private static final Pattern NUMBERED_MULTI_QUESTION_PATTERN =
            Pattern.compile("(^|\\s)(\\d+[)\\.、]|[A-Za-z][)])");
    private static final Pattern MULTI_LINE_PATTERN = Pattern.compile("\\n+");

    private final ObservedChatModelService observedChatModelService;
    private final ChatProperties properties;
    private final ChatPromptTemplateService promptTemplateService;
    private final ChatJsonCodec jsonCodec;

    /**
     * 改写主入口。
     *
     * @param question       原始用户问题
     * @param memoryContext  记忆上下文（取 longTermSummary + assembledHistory 作为 history）
     * @param traceRecorder  追踪记录器（可空，落 REWRITE stage）
     */
    public ChatRewriteResult rewrite(String question, ChatMemoryContext memoryContext,
                                     ChatTraceRecorder traceRecorder) {
        String normalized = ChatTexts.collapseWhitespace(question);
        if (normalized.isBlank()) {
            return ChatRewriteResult.builder()
                    .rewrittenQuery("")
                    .subQuestions(List.of())
                    .usedRewrite(false)
                    .shouldSplit(false)
                    .build();
        }
        String historySummary = extractHistorySummary(memoryContext);
        ChatProperties.Rewrite cfg = properties.getRewrite();

        ChatTraceRecorder.StageHandle stage = traceRecorder == null ? null
                : traceRecorder.startStage(com.reubenagent.chat.enums.ChatTraceStageCode.REWRITE,
                "rewrite", "查询改写开始", Collections.singletonMap("questionLen", normalized.length()));

        if (!Boolean.TRUE.equals(cfg.getEnabled()) || !needsRewrite(normalized, historySummary, cfg)) {
            ChatRewriteResult fallback = ruleFallback(normalized, cfg);
            log.info("查询改写跳过 → question='{}' rewrite='{}' subs={}",
                    normalized, fallback.getRewrittenQuery(), fallback.getSubQuestions());
            if (traceRecorder != null) {
                Map<String, Object> skipSnapshot = new HashMap<>();
                skipSnapshot.put("usedRewrite", false);
                skipSnapshot.put("subCount", fallback.getSubQuestions().size());
                traceRecorder.completeStage(stage, "改写跳过（规则）", skipSnapshot);
            }
            return fallback;
        }

        try {
            Map<String, String> promptVars = new HashMap<>();
            promptVars.put("history", historySummary.isBlank() ? "无历史上下文" : historySummary);
            promptVars.put("question", normalized);
            String prompt = promptTemplateService.render(ChatPromptNames.CHAT_QUERY_REWRITE, promptVars);
            String raw = observedChatModelService.callText("rewrite", prompt, buildRewriteOptions(cfg),
                    traceRecorder == null ? null : traceRecorder.traceSink());
            ChatRewriteResult parsed = normalizeRewriteResult(normalized, raw, cfg);
            if (parsed != null && !parsed.getRewrittenQuery().isBlank()) {
                log.info("查询改写完成 → question='{}' rewrite='{}' subs={}",
                        normalized, parsed.getRewrittenQuery(), parsed.getSubQuestions());
                if (traceRecorder != null) {
                    Map<String, Object> okSnapshot = new HashMap<>();
                    okSnapshot.put("usedRewrite", true);
                    okSnapshot.put("subCount", parsed.getSubQuestions().size());
                    traceRecorder.completeStage(stage, "改写完成", okSnapshot);
                }
                return parsed;
            }
            log.warn("查询改写结果不可用，回退规则 → question='{}' rawHead={}",
                    normalized, ChatTexts.clip(raw, 120));
        } catch (Exception e) {
            log.warn("查询改写失败，回退规则 → question='{}' err={}", normalized, e.getMessage());
        }

        ChatRewriteResult fallback = ruleFallback(normalized, cfg);
        if (traceRecorder != null) {
            Map<String, Object> fbSnapshot = new HashMap<>();
            fbSnapshot.put("usedRewrite", false);
            fbSnapshot.put("subCount", fallback.getSubQuestions().size());
            traceRecorder.completeStage(stage, "改写回退（规则）", fbSnapshot);
        }
        return fallback;
    }

    private String extractHistorySummary(ChatMemoryContext ctx) {
        if (ctx == null) {
            return "";
        }
        String summary = ctx.getLongTermSummary();
        if (summary != null && !summary.isBlank()) {
            return summary;
        }
        return ctx.getAssembledHistory() == null ? "" : ctx.getAssembledHistory();
    }

    private boolean needsRewrite(String question, String historySummary, ChatProperties.Rewrite cfg) {
        int threshold = (historySummary == null || historySummary.isBlank())
                ? cfg.getNeedsRewriteNoHistoryChars()
                : cfg.getNeedsRewriteWithHistoryChars();
        return question.length() < threshold || looksLikeExplicitMultiQuestion(question);
    }

    boolean looksLikeExplicitMultiQuestion(String question) {
        if (question == null || question.isBlank()) {
            return false;
        }
        String normalized = question.trim();
        long qmark = normalized.chars().filter(ch -> ch == '?' || ch == '？').count();
        if (qmark >= 2) {
            return true;
        }
        if (normalized.contains("；") || normalized.contains(";")) {
            return true;
        }
        if (MULTI_LINE_PATTERN.matcher(normalized).find()) {
            long nonBlankLines = Arrays.stream(normalized.split("\\n+"))
                    .map(String::trim)
                    .filter(s -> !s.isBlank())
                    .count();
            if (nonBlankLines >= 2) {
                return true;
            }
        }
        if (NUMBERED_MULTI_QUESTION_PATTERN.matcher(normalized).find()) {
            return true;
        }
        return normalized.contains("分别");
    }

    private ChatOptions buildRewriteOptions(ChatProperties.Rewrite cfg) {
        OpenAiChatOptions.Builder builder = OpenAiChatOptions.builder();
        if (cfg.getTemperature() != null) {
            builder.temperature(cfg.getTemperature());
        }
        if (cfg.getTopP() != null) {
            builder.topP(cfg.getTopP());
        }
        if (Boolean.TRUE.equals(cfg.getThinkingEnabled())) {
            builder.extraBody(Map.of("thinking", true));
        }
        return builder.build();
    }

    private ChatRewriteResult ruleFallback(String question, ChatProperties.Rewrite cfg) {
        if (!looksLikeExplicitMultiQuestion(question)) {
            return ChatRewriteResult.builder()
                    .rewrittenQuery(question)
                    .subQuestions(List.of(question))
                    .usedRewrite(false)
                    .shouldSplit(false)
                    .build();
        }
        List<String> split = ruleBasedSplit(question, cfg);
        return ChatRewriteResult.builder()
                .rewrittenQuery(question)
                .subQuestions(split)
                .usedRewrite(false)
                .shouldSplit(split.size() > 1)
                .build();
    }

    private List<String> ruleBasedSplit(String question, ChatProperties.Rewrite cfg) {
        List<String> raw = Arrays.stream(question.split("[?？；;\\n]+"))
                .map(String::trim)
                .filter(s -> !s.isBlank())
                .limit(cfg.getMaxSubQuestions())
                .toList();
        if (raw.isEmpty()) {
            return List.of(question);
        }
        return new ArrayList<>(new LinkedHashSet<>(raw));
    }

    private ChatRewriteResult normalizeRewriteResult(String originalQuestion, String raw, ChatProperties.Rewrite cfg) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String json = jsonCodec.extractFirstBalancedObject(raw);
        if (json == null) {
            return null;
        }
        JSONObject root;
        try {
            root = JSON.parseObject(json);
        } catch (Exception e) {
            log.warn("查询改写 JSON 解析失败 → head={} err={}", ChatTexts.clip(json, 120), e.getMessage());
            return null;
        }
        String rewrite = ChatTexts.collapseWhitespace(root.getString("rewrite"));
        if (rewrite == null || rewrite.isBlank()) {
            return null;
        }
        Boolean shouldSplit = root.getBoolean("should_split");
        boolean split = Boolean.TRUE.equals(shouldSplit);
        List<String> subQuestions = new ArrayList<>();
        JSONArray arr = root.getJSONArray("sub_questions");
        if (arr != null) {
            for (int i = 0; i < arr.size(); i++) {
                String s = arr.getString(i);
                if (s != null && !s.isBlank()) {
                    subQuestions.add(s.trim());
                }
            }
        }
        boolean explicitMulti = looksLikeExplicitMultiQuestion(originalQuestion);
        if (!split || !explicitMulti) {
            if (split && !explicitMulti && subQuestions.size() > 1) {
                log.info("查询改写子问题收敛（保守结构检查未通过） → question='{}'", originalQuestion);
            }
            subQuestions = List.of(rewrite);
        } else if (subQuestions.isEmpty()) {
            List<String> fallbackSplit = ruleBasedSplit(originalQuestion, cfg);
            subQuestions = fallbackSplit.size() > 1 ? fallbackSplit : List.of(rewrite);
        }
        if (subQuestions.size() > cfg.getMaxSubQuestions()) {
            subQuestions = subQuestions.subList(0, cfg.getMaxSubQuestions());
        }
        return ChatRewriteResult.builder()
                .rewrittenQuery(rewrite)
                .subQuestions(subQuestions)
                .usedRewrite(true)
                .shouldSplit(split && explicitMulti)
                .build();
    }
}
