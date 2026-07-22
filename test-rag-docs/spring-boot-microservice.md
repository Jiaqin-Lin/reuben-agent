# Spring Boot 微服务开发实践

## Spring Boot 核心特性

Spring Boot 简化了基于 Spring 框架的 Java 应用开发，提供自动配置、起步依赖和内嵌服务器。开发者只需关注业务逻辑，无需繁琐的 XML 配置。

## RESTful API 设计

设计 REST API 时应遵循以下原则：

- 使用名词而非动词作为资源路径（`/api/users` 而非 `/api/getUsers`）
- HTTP 方法语义化：GET 查询、POST 创建、PUT 全量更新、PATCH 部分更新、DELETE 删除
- 统一响应格式，包含状态码、消息和数据体
- 分页查询使用 `page` 和 `size` 参数

## 分层架构

典型的 Spring Boot 项目采用 Controller → Service → Mapper 三层架构：

- **Controller 层**：接收 HTTP 请求，参数校验，调用 Service
- **Service 层**：业务逻辑编排，事务管理
- **Mapper 层**：数据访问，MyBatis-Plus 提供 CRUD 基础能力

## 数据库集成

Spring Boot 通过 MyBatis-Plus 实现数据库操作。实体类使用 `@TableName` 映射表名，继承 `BaseTableData` 自动获得创建时间和更新时间字段。

## 异常处理

全局异常处理器统一捕获业务异常，转换为标准化错误响应。自定义业务异常携带错误码和错误信息，方便前端统一处理。
