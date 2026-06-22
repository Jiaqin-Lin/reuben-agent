package com.reubenagent.rag.service;

import com.reubenagent.rag.config.RagProperties;
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
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 查询改写服务 —— 用 LLM 改写用户原始问题，提升召回命中率。
 *
 * <h3>设计要点</h3>
 * <ul>
 *   <li>规则前置：短 query（低于 {@code minQueryLength}）不调 LLM，直接返回原 query</li>
 *   <li>LLM 改写：通过 prompt 模板 {@code rag-query-rewrite.st} 让 LLM 输出检索友好 query</li>
 *   <li>LLM 失败 → log.warn + 返回原 query（降级，不阻塞检索）</li>
 *   <li>模板首次加载后缓存，避免重复 I/O</li>
 * </ul>
 *
 * <h3>与 super-agent 的差异</h3>
 * <ul>
 *   <li>不做子问题拆解、多轮历史上下文拼接——v1 只做单 query 改写</li>
 *   <li>skip 阈值简化为可配置长度，不做口语化检测</li>
 *   <li>不依赖 StringTemplate 框架——自实现简单模板渲染</li>
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

    private final ObjectProvider<ChatModel> chatModelProvider;
    private final RagProperties ragProperties;

    /** 模板缓存 */
    private final ConcurrentHashMap<String, String> templateCache = new ConcurrentHashMap<>();

    /**
     * 改写查询文本。
     *
     * @param originalQuery 用户原始查询
     * @return 改写后的查询（如果跳过改写或 LLM 不可用/失败，返回原始查询）
     */
    public String rewrite(String originalQuery) {
        if (originalQuery == null || originalQuery.isBlank()) {
            return originalQuery;
        }

        // 阶段 1：规则前置 — 短 query 跳过 LLM
        RagProperties.QueryRewrite config = ragProperties.getQueryRewrite();
        if (!config.isEnabled()) {
            log.debug("查询改写已禁用，使用原 query: '{}'", originalQuery);
            return originalQuery;
        }

        String trimmed = originalQuery.trim();
        if (trimmed.length() < config.getMinQueryLength()) {
            log.debug("查询过短 ({} 字 < {} 字)，跳过改写: '{}'", trimmed.length(), config.getMinQueryLength(), trimmed);
            return trimmed;
        }

        // 阶段 2：获取 ChatModel
        ChatModel chatModel = chatModelProvider.getIfAvailable();
        if (chatModel == null) {
            log.warn("ChatModel 不可用，跳过查询改写");
            return trimmed;
        }

        // 阶段 3：构建 Prompt 并调用 LLM
        try {
            String prompt = renderTemplate(Map.of("query", trimmed));
            log.debug("查询改写 prompt ({} 字符): {}", prompt.length(), prompt.substring(0, Math.min(200, prompt.length())));

            ChatResponse response = chatModel.call(new Prompt(new UserMessage(prompt)));
            String rewritten = response.getResult().getOutput().getText();
            rewritten = cleanResponse(rewritten);

            if (rewritten.isEmpty()) {
                log.debug("LLM 返回空文本，使用原 query: '{}'", trimmed);
                return trimmed;
            }

            if (rewritten.length() > 500) {
                log.debug("LLM 返回过长 ({} 字)，截断并使用原 query: '{}'", rewritten.length(), trimmed);
                return trimmed;
            }

            return rewritten;
        } catch (Exception e) {
            log.warn("查询改写失败，降级使用原 query: '{}' — {}", trimmed, e.getMessage());
            return trimmed;
        }
    }

    // ======================== 内部方法 ========================

    /** 清除 LLM 响应中的引号、多余空白和前缀标记。 */
    private String cleanResponse(String response) {
        if (response == null) return "";
        // 清除常见的 LLM 输出包裹字符
        String cleaned = response.trim();
        // 去除首尾引号（中英文）
        String[] quoteChars = {"\"", "'", "“", "”", "‘", "’", "「", "」"};
        for (String q : quoteChars) {
            if (cleaned.startsWith(q) && cleaned.endsWith(q) && cleaned.length() > q.length() * 2) {
                cleaned = cleaned.substring(q.length(), cleaned.length() - q.length());
                break;
            }
        }
        // 去除常见前缀标记
        cleaned = cleaned.replaceAll("^改写后查询[：:]\\s*", "");
        cleaned = cleaned.replaceAll("^查询[：:]\\s*", "");
        return cleaned.trim();
    }

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
