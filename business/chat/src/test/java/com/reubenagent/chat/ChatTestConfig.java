package com.reubenagent.chat;

import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import org.apache.ibatis.reflection.MetaObject;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

import java.util.Date;

/**
 * 对话模块集成测试共享配置 —— 供所有 *Test 类通过 {@code @Import} 引用。
 *
 * <p>提供：</p>
 * <ul>
 *   <li>{@link TestApp} — 限制性包扫描的 {@code @SpringBootApplication}（仅 chat）</li>
 *   <li>{@link TestMetaConfig} — MyBatis-Plus 自动填充模拟</li>
 * </ul>
 *
 * <p>对标 document 模块的 {@code DocumentTestConfig}。</p>
 */
public final class ChatTestConfig {

    private ChatTestConfig() {
    }

    /**
     * 测试专用 Spring Boot 应用，仅扫描 {@code com.reubenagent.chat} 包。
     */
    @SpringBootApplication(scanBasePackages = "com.reubenagent.chat")
    public static class TestApp {
    }

    /**
     * MyBatis-Plus 自动填充模拟 —— 代替运行时的 {@code MetaObjectHandler}。
     */
    @TestConfiguration
    public static class TestMetaConfig {
        @Bean
        MetaObjectHandler metaObjectHandler() {
            return new MetaObjectHandler() {
                @Override
                public void insertFill(MetaObject metaObject) {
                    Date now = new Date();
                    this.strictInsertFill(metaObject, "createTime", Date.class, now);
                    this.strictInsertFill(metaObject, "updateTime", Date.class, now);
                    this.strictInsertFill(metaObject, "isDeleted", Integer.class, 0);
                }

                @Override
                public void updateFill(MetaObject metaObject) {
                    this.strictUpdateFill(metaObject, "updateTime", Date.class, new Date());
                }
            };
        }
    }
}
