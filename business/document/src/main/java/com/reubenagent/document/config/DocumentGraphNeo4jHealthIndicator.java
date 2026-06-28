package com.reubenagent.document.config;

import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.neo4j.driver.Driver;
import org.neo4j.driver.Session;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Component;

/**
 * Neo4j 健康检查 —— 仅在 Driver Bean 存在时启用。
 *
 * <p>项目未引入 spring-boot-starter-actuator，故不实现 {@code HealthIndicator}，
 * 暴露 {@link #isAvailable()} 供需要前置探活的调用方使用。</p>
 *
 * @author reuben
 * @since 2026-06-28
 */
@Slf4j
@Component("documentGraphNeo4jHealthIndicator")
@AllArgsConstructor
@ConditionalOnBean(name = "documentManageNeo4jDriver")
public class DocumentGraphNeo4jHealthIndicator {

    private final Driver documentManageNeo4jDriver;

    /** 执行 RETURN 1 探活，成功返回 true */
    public boolean isAvailable() {
        try (Session session = documentManageNeo4jDriver.session()) {
            session.run("RETURN 1 AS ok").consume();
            return true;
        } catch (Exception e) {
            log.warn("Neo4j 健康检查失败: {}", e.getMessage());
            return false;
        }
    }
}
