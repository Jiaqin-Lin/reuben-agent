# Docker 容器化 Spring Boot 项目实战

## 项目准备

将一个 Spring Boot 微服务项目容器化，需要完成以下步骤：

1. 编写 Dockerfile，定义镜像构建过程
2. 配置 Docker Compose，编排应用与中间件
3. 处理环境差异（开发/测试/生产配置分离）

## Dockerfile 最佳实践

针对 Spring Boot 应用的多阶段构建：

```dockerfile
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /src
COPY pom.xml .
RUN mvn dependency:go-offline
COPY src/ src/
RUN mvn package -DskipTests

FROM openjdk:17-jdk-slim
WORKDIR /app
COPY --from=build /src/target/*.jar app.jar
EXPOSE 8080
HEALTHCHECK CMD curl -f http://localhost:8080/actuator/health || exit 1
ENTRYPOINT ["java", "-jar", "app.jar"]
```

## Docker Compose 配置

```yaml
services:
  app:
    build: .
    ports: ["8080:8080"]
    environment:
      SPRING_DATASOURCE_URL: jdbc:mysql://mysql:3306/mydb
    depends_on:
      mysql:
        condition: service_healthy
  mysql:
    image: mysql:8.0
    environment:
      MYSQL_ROOT_PASSWORD: root123
    healthcheck:
      test: ["CMD", "mysqladmin", "ping", "-h", "localhost"]
```

## 日志与监控

容器化后日志输出到标准输出，由 Docker 日志驱动或日志收集器统一采集。建议使用 Loki + Grafana 搭建日志监控体系。

健康检查端点 `/actuator/health` 可被 Docker 和 K8s 用于探活和就绪检测。

## CI/CD 集成

在 CI 流水线中自动构建镜像并推送到镜像仓库（如 Harbor、Docker Hub），CD 阶段拉取最新镜像部署到测试或生产环境。
