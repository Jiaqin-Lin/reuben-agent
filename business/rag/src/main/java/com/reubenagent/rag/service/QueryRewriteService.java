package com.reubenagent.rag.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.reubenagent.rag.config.RagProperties;
import com.reubenagent.rag.model.RewriteResult;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.springframework.util.StreamUtils;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * 查询改写服务 —— 用 LLM 改写用户原始问题，提升召回命中率。
 *
 * <h3>设计要点</h3>
 * <ul>
 *   <li>规则前置：短 query、禁用、单问题不满足阈值均跳过 LLM</li>
 *   <li>LLM 改写：通过 prompt 模板 {@code rag-query-rewrite.st} 让 LLM 输出 JSON
 *       {@code {rewrite, should_split, sub_questions}}</li>
 *   <li>子问题拆解：多问号/分号/编号项做启发式检测 + LLM JSON 拆分，保守门控确保不误拆</li>
 *   <li>LLM 失败 → log.warn + 规则 fallback（不阻塞检索）</li>
 *   <li>模板首次加载后缓存，避免重复 I/O</li>
 * </ul>
 *
 * @author reuben
 * @since 2026-06-22
 */
@Slf4j
@Component
@AllArgsConstructor
public class QueryRewriteService {

    private static final String TEMPLATE_NAME = "rag-query-rewrite";
    private static final String TEMPLATE_PATH = "prompt/" + TEMPLATE_NAME + ".st";

    private static final Pattern NUMBERED_MULTI_QUESTION_PATTERN =
            Pattern.compile("(^|\\s)(\\d+[)\\.、]|[A-Za-z][)])");
    private static final Pattern MULTI_LINE_PATTERN = Pattern.compile("\\n+");

    private final ObjectProvider<ChatModel> chatModelProvider;
    private final RagProperties ragProperties;
    private final ObjectMapper objectMapper;

    /** 模板缓存 */
    private final ConcurrentHashMap<String, String> templateCache = new ConcurrentHashMap<>();

    /**
     * 改写查询文本，可能拆分为多个子问题。
     *
     * @param originalQuery 用户原始查询
     * @return 结构化改写结果（含子问题列表）
     */
    public RewriteResult rewrite(String originalQuery) {
        if (originalQuery == null || originalQuery.isBlank()) {
            return RewriteResult.builder()
                    .rewrittenQuery(originalQuery)
                    .subQuestions(List.of())
                    .usedRewrite(false)
                    .shouldSplit(false)
                    .build();
        }

        // 阶段 1：规则前置 — 禁用或短 query 跳过 LLM
        RagProperties.QueryRewrite config = ragProperties.getQueryRewrite();
        if (!config.isEnabled()) {
            log.debug("查询改写已禁用，使用原 query: '{}'", originalQuery);
            return singleQueryResult(originalQuery, false);
        }

        String trimmed = originalQuery.trim();
        if (trimmed.length() < config.getMinQueryLength()) {
            log.debug("查询过短 ({} 字 < {} 字)，跳过改写: '{}'", trimmed.length(), config.getMinQueryLength(), trimmed);
            return singleQueryResult(trimmed, false);
        }

        // 阶段 2：启发式检测 — 是否可能是多问题
        boolean explicitMulti = looksLikeExplicitMultiQuestion(trimmed);

        // 阶段 3：获取 ChatModel
        ChatModel chatModel = chatModelProvider.getIfAvailable();
        if (chatModel == null) {
            log.warn("ChatModel 不可用，跳过查询改写");
            return fallbackResult(trimmed, explicitMulti, config.getMaxSubQuestions());
        }

        // 阶段 4：LLM 改写 + 拆分
        try {
            String prompt = renderTemplate(Map.of("query", trimmed));
            log.debug("查询改写 prompt ({} 字符)", prompt.length());

            ChatResponse response = chatModel.call(new Prompt(new UserMessage(prompt)));
            String raw = response.getResult().getOutput().getText();

            RewriteResult parsed = normalizeRewriteResult(trimmed, raw, config.getMaxSubQuestions());
            if (parsed != null && !parsed.getRewrittenQuery().isBlank()) {
                log.info("查询改写完成 → question='{}' rewrite='{}' subs={} split={}",
                        trimmed, parsed.getRewrittenQuery(), parsed.getSubQuestions().size(),
                        parsed.isShouldSplit());
                return parsed;
            }

            log.warn("查询改写结果不可用，回退规则 → question='{}'", trimmed);
        } catch (Exception e) {
            log.warn("查询改写失败，降级 → question='{}' err={}", trimmed, e.getMessage());
        }

        return fallbackResult(trimmed, explicitMulti, config.getMaxSubQuestions());
    }

    // ======================== 启发式检测 ========================

    /**
     * 判断原始问题是否显式包含多个独立问题。
     *
     * <p>检测多问号、分号、多行、编号、显式"分别"等特征。对标
     * {@code ChatQueryRewriteService.looksLikeExplicitMultiQuestion}。</p>
     */
    public boolean looksLikeExplicitMultiQuestion(String question) {
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

    // ======================== 规则拆分 ========================

    /**
     * 按标点规则拆分多问题（不调 LLM）。
     */
    private List<String> ruleBasedSplit(String question, int maxSubQuestions) {
        List<String> raw = Arrays.stream(question.split("[?？；;\\n]+"))
                .map(String::trim)
                .filter(s -> !s.isBlank())
                .limit(maxSubQuestions)
                .toList();
        if (raw.isEmpty()) {
            return List.of(question);
        }
        return new ArrayList<>(new LinkedHashSet<>(raw));
    }

    private RewriteResult fallbackResult(String question, boolean explicitMulti, int maxSubQuestions) {
        if (!explicitMulti) {
            return singleQueryResult(question, false);
        }
        List<String> split = ruleBasedSplit(question, maxSubQuestions);
        return RewriteResult.builder()
                .rewrittenQuery(question)
                .subQuestions(split)
                .usedRewrite(false)
                .shouldSplit(split.size() > 1)
                .build();
    }

    private RewriteResult singleQueryResult(String query, boolean usedRewrite) {
        return RewriteResult.builder()
                .rewrittenQuery(query)
                .subQuestions(List.of(query))
                .usedRewrite(usedRewrite)
                .shouldSplit(false)
                .build();
    }

    // ======================== JSON 解析 ========================

    /**
     * 从 LLM 原始输出中解析改写结果。
     *
     * <p>保守门控：即使 LLM 输出 should_split=true，也必须满足启发式多问题检测才接受拆分。
     * 防止 LLM 对单问题过度拆分。</p>
     */
    private RewriteResult normalizeRewriteResult(String originalQuestion, String raw,
                                                  int maxSubQuestions) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String json = extractJsonFromLLMOutput(raw);
        if (json == null) {
            return null;
        }
        JsonNode root;
        try {
            root = objectMapper.readTree(json);
        } catch (Exception e) {
            log.warn("查询改写 JSON 解析失败 → err={}", e.getMessage());
            return null;
        }
        String rewrite = root.path("rewrite").asText(null);
        if (rewrite == null || rewrite.isBlank()) {
            return null;
        }
        rewrite = rewrite.trim();

        boolean shouldSplit = root.path("should_split").asBoolean(false);
        List<String> subQuestions = new ArrayList<>();
        JsonNode arr = root.path("sub_questions");
        if (arr.isArray()) {
            for (JsonNode s : arr) {
                if (s.isTextual()) {
                    String text = s.asText().trim();
                    if (!text.isBlank()) {
                        subQuestions.add(text);
                    }
                }
            }
        }

        // 保守门控：LLM 说拆分但启发式不通过 → 收敛为单问题
        boolean explicitMulti = looksLikeExplicitMultiQuestion(originalQuestion);
        if (!shouldSplit || !explicitMulti) {
            if (shouldSplit && !explicitMulti && subQuestions.size() > 1) {
                log.info("查询改写子问题收敛（保守结构检查未通过） → question='{}'", originalQuestion);
            }
            subQuestions = List.of(rewrite);
            shouldSplit = false;
        } else if (subQuestions.isEmpty()) {
            List<String> fallbackSplit = ruleBasedSplit(originalQuestion, maxSubQuestions);
            subQuestions = fallbackSplit.size() > 1 ? fallbackSplit : List.of(rewrite);
        }

        if (subQuestions.size() > maxSubQuestions) {
            subQuestions = subQuestions.subList(0, maxSubQuestions);
        }

        return RewriteResult.builder()
                .rewrittenQuery(rewrite)
                .subQuestions(subQuestions)
                .usedRewrite(true)
                .shouldSplit(shouldSplit && explicitMulti)
                .build();
    }

    /**
     * 从 LLM 原始输出中提取第一个平衡的 JSON 对象。
     * 处理 markdown fence、前后说明文字等情况。
     */
    static String extractJsonFromLLMOutput(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        // 尝试取 markdown code fence 内容
        String content = raw;
        int fenceStart = raw.indexOf("```");
        if (fenceStart >= 0) {
            int contentStart = raw.indexOf('\n', fenceStart);
            if (contentStart >= 0) {
                int fenceEnd = raw.indexOf("```", contentStart + 1);
                if (fenceEnd >= 0) {
                    content = raw.substring(contentStart + 1, fenceEnd);
                }
            }
        }
        // 平衡括号提取
        int start = content.indexOf('{');
        if (start < 0) {
            return null;
        }
        int depth = 0;
        boolean inString = false;
        boolean escaped = false;
        for (int i = start; i < content.length(); i++) {
            char c = content.charAt(i);
            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (c == '\\') {
                    escaped = true;
                } else if (c == '"') {
                    inString = false;
                }
            } else {
                if (c == '"') {
                    inString = true;
                } else if (c == '{') {
                    depth++;
                } else if (c == '}') {
                    depth--;
                    if (depth == 0) {
                        return content.substring(start, i + 1);
                    }
                }
            }
        }
        return null;
    }

    // ======================== 模板渲染 ========================

    /**
     * 加载模板并渲染。
     *
     * @param variables 变量名 → 值映射
     * @return 渲染后的文本
     */
    private String renderTemplate(Map<String, String> variables) {
        String template = loadTemplate();
        String result = template;
        if (variables != null) {
            for (Map.Entry<String, String> entry : variables.entrySet()) {
                String placeholder = "<" + entry.getKey() + ">";
                String value = entry.getValue() != null ? entry.getValue() : "";
                result = result.replace(placeholder, value);
            }
        }
        return result;
    }

    /** 从 classpath 加载模板内容（首次访问时缓存）。 */
    private String loadTemplate() {
        return templateCache.computeIfAbsent(TEMPLATE_NAME, key -> {
            try {
                ClassPathResource resource = new ClassPathResource(TEMPLATE_PATH);
                if (!resource.exists()) {
                    throw new IllegalStateException("Prompt 模板不存在: classpath:" + TEMPLATE_PATH);
                }
                String content = StreamUtils.copyToString(resource.getInputStream(), StandardCharsets.UTF_8);
                log.debug("已加载 Prompt 模板: {} ({} 字符)", TEMPLATE_PATH, content.length());
                return content;
            } catch (IllegalStateException e) {
                throw e;
            } catch (Exception e) {
                throw new IllegalStateException("无法读取 Prompt 模板: classpath:" + TEMPLATE_PATH, e);
            }
        });
    }
}
