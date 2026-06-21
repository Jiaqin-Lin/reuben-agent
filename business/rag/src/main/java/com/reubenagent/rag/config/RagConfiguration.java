package com.reubenagent.rag.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * RAG 模块配置入口 —— 启用 {@link RagProperties} 属性绑定。
 *
 * @author reuben
 * @since 2026-06-21
 */
@Configuration
@EnableConfigurationProperties(RagProperties.class)
public class RagConfiguration {
}
