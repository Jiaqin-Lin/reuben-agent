package com.reubenagent.chat.support;

import com.reubenagent.chat.enums.ChatErrorCode;
import com.reubenagent.chat.exception.ChatException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.springframework.util.StreamUtils;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 对话模块 Prompt 模板渲染服务 —— 从 {@code classpath:prompt/} 加载 {@code .st} 并替换 {@code <var>} 占位符。
 *
 * <p>与 document 模块的 {@code PromptTemplateService} 风格一致：简单字符串替换，不引入 StringTemplate 依赖；
 * 模板内容首次加载后缓存至 {@link ConcurrentHashMap}。区别在于加载失败抛 {@link ChatException}，
 * 不抛 {@code IllegalStateException}（修正 super-agent 问题，统一走模块异常体系）。</p>
 *
 * @author reuben
 * @since 2026-06-24
 */
@Slf4j
@Component
public class ChatPromptTemplateService {

    private final ConcurrentHashMap<String, String> templateCache = new ConcurrentHashMap<>();

    /**
     * 加载并渲染模板。
     *
     * @param templateName 模板名（不含路径前缀和 .st 后缀）
     * @param variables    变量名 → 值映射，值为 null 时替换为空字符串
     * @return 渲染后的文本
     */
    public String render(String templateName, Map<String, String> variables) {
        String result = loadTemplate(templateName);
        if (variables != null) {
            for (Map.Entry<String, String> entry : variables.entrySet()) {
                String placeholder = "<" + entry.getKey() + ">";
                String value = entry.getValue() != null ? entry.getValue() : "";
                result = result.replace(placeholder, value);
            }
        }
        return result;
    }

    /** 从 classpath 加载模板内容（首次访问时缓存），失败抛 {@link ChatException}。 */
    String loadTemplate(String name) {
        return templateCache.computeIfAbsent(name, key -> {
            String path = "prompt/" + key + ".st";
            try {
                ClassPathResource resource = new ClassPathResource(path);
                if (!resource.exists()) {
                    throw new ChatException(ChatErrorCode.PROMPT_LOAD_FAILED, "classpath:" + path);
                }
                String content = StreamUtils.copyToString(resource.getInputStream(), StandardCharsets.UTF_8);
                log.debug("已加载对话 Prompt 模板: {} ({} 字符)", path, content.length());
                return content;
            } catch (ChatException e) {
                throw e;
            } catch (Exception e) {
                throw new ChatException(ChatErrorCode.PROMPT_LOAD_FAILED, "classpath:" + path, e);
            }
        });
    }
}
