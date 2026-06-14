package com.reubenagent.framework.uid.config;

import com.reubenagent.framework.uid.UidGenerator;
import com.reubenagent.framework.uid.impl.DefaultUidGenerator;
import com.reubenagent.framework.uid.worker.WorkerIdAssigner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * UID 生成器自动配置。
 *
 * <p>当 classpath 中存在 {@link StringRedisTemplate} 时自动装配：</p>
 * <ol>
 *   <li>{@link RedisDisposableWorkerIdAssigner} — 基于 Redis INCR 的 WorkerID 分配器</li>
 *   <li>{@link DefaultUidGenerator} — 默认 UID 生成器</li>
 * </ol>
 *
 * <p>业务代码直接注入 {@link UidGenerator} 接口使用即可。</p>
 *
 * @author reuben
 * @since 2026-06-14
 */
@Configuration
public class UidGeneratorAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public WorkerIdAssigner workerIdAssigner(StringRedisTemplate stringRedisTemplate) {
        return new RedisDisposableWorkerIdAssigner(stringRedisTemplate);
    }

    @Bean
    @ConditionalOnMissingBean
    public UidGenerator uidGenerator(WorkerIdAssigner workerIdAssigner) {
        DefaultUidGenerator generator = new DefaultUidGenerator();
        generator.setWorkerIdAssigner(workerIdAssigner);
        return generator;
    }
}
