package com.reubenagent.document.support;

import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.springframework.util.StreamUtils;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Prompt 模板渲染服务 —— 从 classpath 加载模板文件并替换变量占位符。
 *
 * <p>模板文件位于 {@code classpath:prompt/} 目录，使用 {@code <variableName>} 格式的占位符。
 * 模板内容在首次加载后缓存至 {@link ConcurrentHashMap}，避免重复 I/O。</p>
 *
 * <p>与 super-agent 的区别：不使用 StringTemplate/StTemplateRenderer，改用简单字符串替换以消除额外依赖。
 * 模板文件格式与 super-agent 兼容（.st 后缀，尖括号定界符）。</p>
 *
 * @author reuben
 * @since 2026-06-18
 */
@Slf4j
@Component
public class PromptTemplateService {

    /** 模板缓存（模板名 → 模板原始内容），线程安全 */
    private final ConcurrentHashMap<String, String> templateCache = new ConcurrentHashMap<>();

    // ======================== 模板名称常量 ========================

    /** 文档结构歧义消解主模板 */
    public static final String DOCUMENT_STRUCTURE_AMBIGUITY = "document-structure-ambiguity";
    /** 候选行子模板 */
    public static final String DOCUMENT_STRUCTURE_AMBIGUITY_CANDIDATE = "document-structure-ambiguity-candidate";

    // ======================== 公共 API ========================

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

    // ======================== 内部方法 ========================

    /**
     * 从 classpath 加载模板内容（首次访问时缓存）。
     *
     * @param name 模板名（不含 .st 后缀）
     * @return 模板原始内容
     * @throws IllegalStateException 如果模板文件不存在或读取失败
     */
    String loadTemplate(String name) {
        return templateCache.computeIfAbsent(name, key -> {
            String path = "prompt/" + key + ".st";
            try {
                ClassPathResource resource = new ClassPathResource(path);
                if (!resource.exists()) {
                    throw new IllegalStateException("Prompt 模板不存在: classpath:" + path);
                }
                String content = StreamUtils.copyToString(resource.getInputStream(), StandardCharsets.UTF_8);
                log.debug("已加载 Prompt 模板: {} ({} 字符)", path, content.length());
                return content;
            } catch (IllegalStateException e) {
                throw e;
            } catch (Exception e) {
                throw new IllegalStateException("无法读取 Prompt 模板: classpath:" + path, e);
            }
        });
    }
}
