package com.reubenagent;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * reuben-agent 启动入口。
 *
 * <p>{@code scanBasePackages = "com.reubenagent"} 覆盖：
 * common / framework / document / rag / chat / auth</p>
 *
 * <h3>启动方式</h3>
 * <pre>
 * # 先 install 依赖模块
 * mvn install -pl launcher -am -DskipTests
 *
 * # 启动
 * mvn spring-boot:run -pl launcher
 * </pre>
 */
@SpringBootApplication(scanBasePackages = "com.reubenagent")
public class LauncherApplication {

    public static void main(String[] args) {
        SpringApplication.run(LauncherApplication.class, args);
    }
}
