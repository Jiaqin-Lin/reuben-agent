package com.reubenagent.document.config;

import lombok.AllArgsConstructor;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.config.TopicBuilder;

/**
 * Kafka 配置 —— 启用 {@code @KafkaListener} 并创建文档模块所需的 Topic。
 *
 * <p>开发环境 {@code reuben.document.kafka.auto-create-topics=true} 时自动创建，
 * 生产环境由运维预先创建 Topic。Kafka 完全不可用时通过排除
 * {@code KafkaAutoConfiguration} 禁用整套设施。</p>
 *
 * @author reuben
 * @since 2026-06-21
 */
@EnableKafka
@Configuration
@AllArgsConstructor
public class DocumentKafkaConfiguration {

    private final DocumentProperties documentProperties;

    /** 文档解析路由 Topic */
    @Bean
    @ConditionalOnProperty(name = "reuben.document.kafka.auto-create-topics", havingValue = "true", matchIfMissing = true)
    public NewTopic documentParseRouteTopic() {
        return TopicBuilder.name(documentProperties.getKafka().getParseTopic())
                .partitions(1)
                .replicas(1)
                .build();
    }

    /** 索引构建 Topic */
    @Bean
    @ConditionalOnProperty(name = "reuben.document.kafka.auto-create-topics", havingValue = "true", matchIfMissing = true)
    public NewTopic documentIndexBuildTopic() {
        return TopicBuilder.name(documentProperties.getKafka().getIndexTopic())
                .partitions(1)
                .replicas(1)
                .build();
    }
}
