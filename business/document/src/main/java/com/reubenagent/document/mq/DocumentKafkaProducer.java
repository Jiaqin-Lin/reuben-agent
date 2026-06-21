package com.reubenagent.document.mq;

import com.alibaba.fastjson.JSON;
import com.reubenagent.common.enums.DocumentManageCode;
import com.reubenagent.common.exception.DocumentException;
import com.reubenagent.document.config.DocumentProperties;
import com.reubenagent.document.model.mq.DocumentIndexBuildMessage;
import com.reubenagent.document.model.mq.DocumentParseRouteMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/**
 * 文档模块 Kafka 生产者 —— 投递异步任务消息。
 *
 * <p>消息序列化为 JSON 字符串发送，异常时抛出 {@link DocumentException}（code=20010）。
 * 当 Kafka 不可用时（如测试环境排除了 KafkaAutoConfiguration），此 Bean 不会被创建，
 * 调用方通过 {@code ObjectProvider} 获取可选的引用。</p>
 *
 * @author reuben
 * @since 2026-06-21
 */
@Slf4j
@Component
@ConditionalOnBean(KafkaTemplate.class)
public class DocumentKafkaProducer {

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final DocumentProperties documentProperties;

    public DocumentKafkaProducer(KafkaTemplate<String, String> kafkaTemplate,
                                  DocumentProperties documentProperties) {
        this.kafkaTemplate = kafkaTemplate;
        this.documentProperties = documentProperties;
    }

    /**
     * 发送解析与策略路由任务消息。
     *
     * @param message 解析路由消息
     */
    public void sendParseRoute(DocumentParseRouteMessage message) {
        String topic = documentProperties.getKafka().getParseTopic();
        String key = String.valueOf(message.getDocumentId());
        send(topic, key, JSON.toJSONString(message));
        log.info("Kafka 解析路由消息已发送: topic={} documentId={} taskId={}",
                topic, message.getDocumentId(), message.getTaskId());
    }

    /**
     * 发送索引构建任务消息。
     *
     * @param message 索引构建消息
     */
    public void sendIndexBuild(DocumentIndexBuildMessage message) {
        String topic = documentProperties.getKafka().getIndexTopic();
        String key = String.valueOf(message.getDocumentId());
        send(topic, key, JSON.toJSONString(message));
        log.info("Kafka 索引构建消息已发送: topic={} documentId={} taskId={} planId={}",
                topic, message.getDocumentId(), message.getTaskId(), message.getPlanId());
    }

    private void send(String topic, String key, String payload) {
        try {
            kafkaTemplate.send(topic, key, payload).get();
        } catch (Exception e) {
            log.error("Kafka 消息发送失败: topic={} key={}", topic, key, e);
            throw new DocumentException(DocumentManageCode.KAFKA_SEND_FAILED,
                    "topic=" + topic + " key=" + key, e);
        }
    }
}
