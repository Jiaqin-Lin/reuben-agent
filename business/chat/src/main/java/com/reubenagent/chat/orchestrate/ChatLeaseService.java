package com.reubenagent.chat.orchestrate;

import com.reubenagent.chat.config.ChatProperties;
import com.reubenagent.chat.enums.ChatErrorCode;
import com.reubenagent.chat.exception.ChatException;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;

/**
 * 会话执行租约 —— 基于 Redis 原子脚本，防止同一会话并发执行。
 *
 * <p>用 {@code SET NX PX} 抢占 + owner token 校验续期/释放，等价于 super-agent 的
 * {@code RedisLeaseManager}（依赖 Redisson），这里只用 {@link StringRedisTemplate} 的
 * Lua 脚本，避免引入 Redisson 强依赖。</p>
 *
 * <p>获取失败抛 {@link ChatException}({@link ChatErrorCode#SESSION_RUNNING})。</p>
 *
 * @author reuben
 * @since 2026-06-24
 */
@Slf4j
@Service
@AllArgsConstructor
public class ChatLeaseService {

    /** 抢占：键不存在才设置（NX + PX） */
    private static final DefaultRedisScript<Long> ACQUIRE_SCRIPT = new DefaultRedisScript<>(
            "if redis.call('exists', KEYS[1]) == 0 then "
                    + "redis.call('psetex', KEYS[1], ARGV[2], ARGV[1]); return 1; end; return 0;",
            Long.class);

    /** 续期：owner 匹配才延长 TTL */
    private static final DefaultRedisScript<Long> RENEW_SCRIPT = new DefaultRedisScript<>(
            "if redis.call('get', KEYS[1]) == ARGV[1] then "
                    + "redis.call('pexpire', KEYS[1], ARGV[2]); return 1; end; return 0;",
            Long.class);

    /** 释放：owner 匹配才删除 */
    private static final DefaultRedisScript<Long> RELEASE_SCRIPT = new DefaultRedisScript<>(
            "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]); end; return 0;",
            Long.class);

    private static final String LEASE_KEY_PREFIX = "chat:lease:";

    private final StringRedisTemplate redisTemplate;
    private final ChatProperties properties;

    /** 抢占租约，成功返回 owner token，失败抛 SESSION_RUNNING。 */
    public LeaseClaim tryAcquire(String conversationId) {
        String key = leaseKey(conversationId);
        String ownerToken = Long.toHexString(System.nanoTime()) + "-" + conversationId;
        Duration ttl = leaseTtl();
        Long ok = redisTemplate.execute(ACQUIRE_SCRIPT, List.of(key), ownerToken, String.valueOf(ttl.toMillis()));
        if (ok == null || ok == 0L) {
            throw new ChatException(ChatErrorCode.SESSION_RUNNING, conversationId);
        }
        return new LeaseClaim(key, ownerToken, ttl);
    }

    /** 续期，返回是否续期成功（失败需停止生成）。 */
    public boolean renew(LeaseClaim claim) {
        Long ok = redisTemplate.execute(RENEW_SCRIPT, List.of(claim.key()),
                claim.ownerToken(), String.valueOf(claim.ttl().toMillis()));
        return ok != null && ok == 1L;
    }

    /** 释放租约（owner 匹配才删）。失败 warn 不抛。 */
    public void release(LeaseClaim claim) {
        if (claim == null) {
            return;
        }
        try {
            redisTemplate.execute(RELEASE_SCRIPT, List.of(claim.key()), claim.ownerToken());
        } catch (Exception e) {
            log.warn("释放会话租约异常 → key={} err={}", claim.key(), e.getMessage());
        }
    }

    public Duration renewInterval() {
        // 续期间隔 = TTL / 3，保证在过期前续期
        return leaseTtl().dividedBy(3);
    }

    private Duration leaseTtl() {
        int ttlSeconds = properties.getLease().getTtlSeconds();
        if (ttlSeconds <= 0) {
            ttlSeconds = 60;
        }
        return Duration.ofSeconds(ttlSeconds);
    }

    private String leaseKey(String conversationId) {
        return LEASE_KEY_PREFIX + conversationId;
    }

    /** 租约凭证。 */
    public record LeaseClaim(String key, String ownerToken, Duration ttl) {
    }
}
