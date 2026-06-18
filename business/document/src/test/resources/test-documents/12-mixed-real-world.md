# ReubenAgent 系统设计文档

版权所有 © 2026 ReubenAgent Team | 版本 V2.1.3 | 修订 Rev. 5

---

## 第一章 项目概述

### 1.1 项目背景

随着企业数字化转型的深入，传统的文档管理方式已无法满足日益增长的知识管理需求。ReubenAgent 旨在构建一个智能化的企业级 AI Agent 平台，以文档管理为核心，逐步扩展 Agent、Memory、RAG 等能力。

### 1.2 核心目标

本项目的核心目标包括以下四个方面：

- [x] 实现文档的自动化结构提取与分类
- [x] 构建基于雪花算法的全局唯一 ID 体系
- [ ] 实现知识图谱的多维度语义索引
- [ ] 接入 LLM 进行智能问答与推理

### 1.3 技术选型

| 技术栈 | 选型 | 说明 |
|--------|------|------|
| 开发框架 | Spring Boot 3.5.6 | 成熟稳定的 Java 企业级框架 |
| ORM | MyBatis-Plus 3.5.7 | 轻量级持久层增强 |
| 对象存储 | MinIO 8.5.9 | S3 兼容的开源对象存储 |
| 搜索引擎 | Elasticsearch 8.15 | 分布式全文检索 |
| 图数据库 | Neo4j 5 | 知识图谱存储与查询 |
| 消息队列 | Kafka 3.8 | 异步事件驱动 |

---

## 第二章 系统架构

### 2.1 整体架构

系统采用微服务架构，按业务领域拆分为多个独立模块。

### 2.2 模块分层

各模块的分层关系如下：

> **上层**：launcher —— 唯一启动入口，统一扫描所有模块的 Spring Bean

> **中层**：business —— 业务模块聚合，包含 document、agent、auth、chat、memory、rag

> **底层**：common + framework —— 共享层和基础设施，提供通用工具和 ID 生成

### 2.3 核心设计原则

系统设计遵循以下核心原则：

1. 模块间单向依赖 —— 禁止 business 模块相互依赖
2. 统一异常处理 —— 通过 GlobalExceptionHandler 分类处理
3. 构造器注入 —— 禁止 @Autowired 字段注入
4. 雪花 ID —— 全局唯一主键，不依赖数据库自增

---

## 第三章 文档模块设计

### 3.1 文档处理管线

第1步 文件上传

用户通过 REST API 上传文档文件，系统将原始文件存储到 MinIO。

第2步 文本提取

使用 Apache Tika 从 PDF、DOCX、HTML、TXT 等格式中提取纯文本。

步骤三 结构信号提取

将纯文本逐行分类为 HEADING、LIST_ITEM、BODY、NOISE 等 10 种信号类型。

步骤四 结构节点归并

将扁平信号列表归并为树形结构节点，建立父子关系和兄弟链表。

第5步 索引存储

将结构化的文档节点存储到 MySQL，同时同步到 Elasticsearch 供全文检索。

### 3.2 信号分类优先级

分类器按照 16 级优先级对每行文本进行分类：

1. 空白行判定
2. 重复噪声/页眉页脚
3. 页码
4. Markdown 标题
5. 显式步骤标记
6. 中文章节
7. 附录标题
8. 数字多级编号
9. 表格行
10. 引用行
11. 复选框
12. 无序列表
13. 单级数字编号（模糊）
14. 中文序号（模糊）
15. 朴素标题启发式
16. 正文兜底

### 3.3 边界情况处理

重复标题去重

如果文档中出现了与 documentTitle 相同的标题文本，该行会被标记为 NOISE 而非 HEADING。

折行内的多步骤

如果一行中包含多个步骤标记（如 "第1步 xxx 第2步 yyy"），会被 STEP_BOUNDARY_PATTERN 自动切分为独立的逻辑行。

---

## 第四章 部署运维

### 4.1 Docker 部署

使用 docker-compose 一键启动所有中间件和业务服务：

docker-compose up -d

### 4.2 健康检查

部署完成后，通过以下端点确认服务状态：

- 系统健康：GET /actuator/health
- 文档模块：GET /api/document/health

---

## 附录A 术语表

| 术语 | 英文 | 说明 |
|------|------|------|
| 文档结构信号 | Document Structure Signal | 逐行分类标签 |
| 结构节点 | Structure Node | 树形结构中的节点 |
| 雪花 ID | Snowflake ID | 全局唯一数字标识符 |

---

## 附录 二 参考文献

1. Spring Boot Reference Documentation
2. MyBatis-Plus Official Guide
3. Apache Tika User Guide
4. Elasticsearch Definitive Guide

---

*文档结束*

第1页 / Page 1 / 1/1
