# Kafka 消息队列在微服务中的应用

## Kafka 核心概念

Apache Kafka 是一个分布式流处理平台，核心组件包括：

- **Producer**：消息生产者，向 Topic 发送消息
- **Consumer**：消息消费者，从 Topic 订阅并处理消息
- **Broker**：Kafka 服务节点，负责消息存储和转发
- **Topic**：消息的逻辑分类，分为多个 Partition 并行处理
- **Consumer Group**：消费者组，组内消费者分摊 Partition 消费

## 异步任务处理

在微服务架构中，Kafka 常用于解耦同步流程和异步任务。例如文档上传后：

1. Controller 接收文件 → 写入数据库 → 发送 Kafka 消息
2. Consumer 异步消费 → 执行解析和索引构建
3. 用户轮询或 WebSocket 获取处理进度

这种模式避免长时间阻塞 HTTP 请求，提升系统吞吐量。

## 消息可靠性保障

Kafka 通过以下机制保证消息不丢失：

- **ACK 机制**：Producer 等待 Broker 确认后才认为发送成功
- **分区副本**：每个 Partition 有多个 Replica，Leader 故障时自动切换
- **消费者位移**：Consumer 手动提交 Offset，确保消息被成功处理

## 常见应用场景

除了异步任务处理，Kafka 还广泛应用于：

- 日志聚合与监控告警
- 实时数据流处理
- 事件溯源（Event Sourcing）
- CDC 数据变更捕获
- 微服务间事件驱动通信
