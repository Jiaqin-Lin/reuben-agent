package com.reubenagent.document.config;

import com.reubenagent.document.model.mq.DocumentIndexBuildMessage;
import com.reubenagent.document.model.mq.DocumentParseRouteMessage;
import lombok.AllArgsConstructor;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaTemplate;

/**
 * Kafka 配置 —— 创建文档模块所需的 Topic。
 *
 * <p>开发环境 {@code reuben.document.kafka.auto-create-topics=true} 时自动创建，
 * 生产环境由运维预先创建 Topic。当 Kafka 不可用时，整个配置类不会被加载。</p>
 *
 * @author reuben
 * @since 2026-06-21
 */
@EnableKafka
@Configuration
@AllArgsConstructor
@ConditionalOnBean(KafkaTemplate.class)
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
