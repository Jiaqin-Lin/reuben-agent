package com.reubenagent.document.mq;

import com.alibaba.fastjson.JSON;
import com.reubenagent.document.model.mq.DocumentIndexBuildMessage;
import com.reubenagent.document.model.mq.DocumentParseRouteMessage;
import com.reubenagent.document.service.IDocumentAsyncProcessService;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * 文档模块 Kafka 消费者 —— 监听异步任务消息并调用对应处理器。
 *
 * <p>所有异常在方法内部捕获，仅记录日志，避免 Kafka 无限重试。
 * 当 Kafka 不可用时（如测试环境排除了 KafkaAutoConfiguration），此 Bean 不会被创建。</p>
 *
 * @author reuben
 * @since 2026-06-21
 */
@Slf4j
@AllArgsConstructor
@Component
public class DocumentKafkaConsumer {

    private final IDocumentAsyncProcessService asyncProcessService;

    /**
     * 消费解析路由任务 —— 触发文档解析 + 策略推荐。
     */
    @KafkaListener(
            topics = "${reuben.document.kafka.parse-topic}",
            groupId = "${reuben.document.kafka.group-id}-parse")
    public void consumeParseRoute(String payload) {
        try {
            DocumentParseRouteMessage message = JSON.parseObject(payload, DocumentParseRouteMessage.class);
            if (message == null || message.getDocumentId() == null || message.getTaskId() == null) {
                log.warn("解析路由消息格式无效: {}", payload);
                return;
            }
            log.info("收到解析路由消息: documentId={} taskId={}", message.getDocumentId(), message.getTaskId());
            asyncProcessService.handleParseStrategyRoute(message.getDocumentId(), message.getTaskId());
        } catch (Exception e) {
            log.error("消费解析路由消息失败: payload={}", payload, e);
        }
    }

    /**
     * 消费索引构建任务 —— 触发切块 + 向量化 + 索引入库。
     */
    @KafkaListener(
            topics = "${reuben.document.kafka.index-topic}",
            groupId = "${reuben.document.kafka.group-id}-index")
    public void consumeIndexBuild(String payload) {
        try {
            DocumentIndexBuildMessage message = JSON.parseObject(payload, DocumentIndexBuildMessage.class);
            if (message == null || message.getDocumentId() == null
                    || message.getTaskId() == null || message.getPlanId() == null) {
                log.warn("索引构建消息格式无效: {}", payload);
                return;
            }
            log.info("收到索引构建消息: documentId={} taskId={} planId={}",
                    message.getDocumentId(), message.getTaskId(), message.getPlanId());
            asyncProcessService.handleIndexBuild(message.getDocumentId(), message.getTaskId(), message.getPlanId());
        } catch (Exception e) {
            log.error("消费索引构建消息失败: payload={}", payload, e);
        }
    }
}
