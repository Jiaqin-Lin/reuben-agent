package com.reubenagent.chat.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ThreadPoolExecutor;

/**
 * 对话模块配置 —— 装配 {@link ChatProperties} 与三个线程池。
 *
 * <p>线程池统一 {@code CallerRunsPolicy}（拒绝时回退到调用线程，不丢任务）+ daemon 线程。</p>
 *
 * @author reuben
 * @since 2026-06-23
 */
@Configuration
@EnableConfigurationProperties(ChatProperties.class)
public class ChatConfiguration {

    public static final String CHAT_RAG_EXECUTOR = "chatRagExecutor";
    public static final String CHAT_MEMORY_EXECUTOR = "chatMemoryExecutor";
    public static final String CHAT_POST_PROCESS_EXECUTOR = "chatPostProcessExecutor";

    /** 检索线程池 */
    @Bean(CHAT_RAG_EXECUTOR)
    public ThreadPoolTaskExecutor chatRagExecutor(ChatProperties properties) {
        return buildExecutor("chat-rag", properties.getExecutor().getRagPoolSize());
    }

    /** 记忆压缩线程池 */
    @Bean(CHAT_MEMORY_EXECUTOR)
    public ThreadPoolTaskExecutor chatMemoryExecutor(ChatProperties properties) {
        return buildExecutor("chat-memory", properties.getExecutor().getMemoryPoolSize());
    }

    /** 后处理线程池（追问生成 / 异步落库） */
    @Bean(CHAT_POST_PROCESS_EXECUTOR)
    public ThreadPoolTaskExecutor chatPostProcessExecutor(ChatProperties properties) {
        return buildExecutor("chat-post-process", properties.getExecutor().getPostProcessPoolSize());
    }

    private ThreadPoolTaskExecutor buildExecutor(String namePrefix, int poolSize) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(poolSize);
        executor.setMaxPoolSize(poolSize);
        executor.setQueueCapacity(256);
        executor.setThreadNamePrefix(namePrefix + "-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(10);
        executor.initialize();
        return executor;
    }
}
