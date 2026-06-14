package com.reubenagent.framework.uid.config;

import com.reubenagent.framework.uid.worker.WorkerIdAssigner;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.Optional;

/**
 * 基于 Redis 的 WorkerID 分配器。
 *
 * <p>使用 Redis {@code INCR} 命令原子自增 {@code uid_work_id} 键，
 * 保证每个服务实例获取唯一的 WorkerID。</p>
 *
 * <p>适用场景：K8s 动态伸缩、多实例部署、无法提前固定 WorkerID 的环境。</p>
 *
 * @author reuben
 * @since 2026-06-14
 */
@Slf4j
public class RedisDisposableWorkerIdAssigner implements WorkerIdAssigner {

    private static final String UID_WORK_ID_KEY = "uid_work_id";

    private final StringRedisTemplate stringRedisTemplate;

    public RedisDisposableWorkerIdAssigner(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    @Override
    public long assignWorkerId() {
        Long increment = stringRedisTemplate.opsForValue().increment(UID_WORK_ID_KEY);
        long workerId = Optional.ofNullable(increment)
                .orElseThrow(() -> new IllegalStateException(
                        "无法从 Redis 获取 WorkerID：Redis INCR " + UID_WORK_ID_KEY + " 返回 null"));
        log.info("分配 WorkerID: {}", workerId);
        return workerId;
    }
}
