package com.reubenagent.rag;

import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * RAG 模块集成测试共享配置。
 *
 * <p>{@link TestApp} 限制性包扫描，仅加载 {@code com.reubenagent.rag} 下的 Bean，
 * 避免拉取 document/auth/chat 等其他 business 模块。</p>
 *
 * @author reuben
 * @since 2026-06-21
 */
public final class RagTestConfig {

    private RagTestConfig() {
    }

    /**
     * 测试专用 Spring Boot 应用，仅扫描 {@code com.reubenagent.rag} 包。
     */
    @SpringBootApplication(scanBasePackages = "com.reubenagent.rag")
    public static class TestApp {
    }
}
