# Docker 容器化部署指南

## 什么是 Docker

Docker 是一个开源的容器化平台，允许开发者将应用程序及其依赖打包到一个轻量级、可移植的容器中。容器使用操作系统级虚拟化，共享主机内核，启动速度快，资源开销小。

## Dockerfile 编写规范

Dockerfile 是构建 Docker 镜像的蓝图文件。一个典型的 Java 应用 Dockerfile 如下：

```dockerfile
FROM openjdk:17-jdk-slim
WORKDIR /app
COPY target/*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
```

多阶段构建可以有效减小镜像体积：第一阶段用 Maven 编译，第二阶段只复制 JAR 包到运行时镜像。

## Docker Compose 多服务编排

Docker Compose 通过 YAML 文件定义多容器应用。一个典型的微服务栈包括：

- **应用服务**：Spring Boot 应用
- **数据库**：MySQL、PostgreSQL
- **缓存**：Redis
- **消息队列**：Kafka

使用 `docker compose up -d` 一键启动全部服务。

## 容器网络与数据卷

Docker 提供 bridge、host、overlay 等多种网络模式。同一 Compose 文件中的服务自动加入默认网络，可通过服务名互相访问。

数据卷用于持久化容器数据。命名卷由 Docker 管理，绑定挂载直接映射主机目录。

## 生产环境最佳实践

- 不以 root 用户运行容器
- 使用健康检查确保服务就绪后再接收流量
- 限制容器内存和 CPU 使用
- 镜像打上语义化版本标签
- 敏感信息通过 Secrets 或环境变量注入
