package com.reubenagent.chat.trace.impl;

import com.reubenagent.chat.config.ChatProperties;
import com.reubenagent.chat.entity.ChatStageBenchmark;
import com.reubenagent.chat.enums.ChatTraceStageCode;
import com.reubenagent.chat.enums.ExecutionMode;
import com.reubenagent.chat.mapper.IChatStageBenchmarkMapper;
import com.reubenagent.chat.trace.ChatStageBenchmarkService;
import com.reubenagent.framework.uid.UidGenerator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Redis LIST + 原子 LTRIM 滑窗实现。
 *
 * <p>每次 {@link #recordDuration} 都 rightPush + 原子 trim 到 {@code benchmarkWindowSize}。
 * 每 N（默认 20）次 push 触发一次 flush：读全 list → 排序算 p50/p90/p99/avg/max/min →
 * mapper upsert（UNIQUE {@code (stage_code, execution_mode)}，先 delete 再 insert 保幂等）。</p>
 *
 * <p>Redis / DB 任意一步失败均 warn 不抛，benchmark 不影响主流程。</p>
 *
 * @author reuben
 * @since 2026-06-25
 */
@Slf4j
@Service
public class ChatStageBenchmarkServiceImpl implements ChatStageBenchmarkService {

    private static final String KEY_PREFIX = "chat:benchmark:";

    /** 原子 trim：超出窗口则只保留尾部 N 条。 */
    private static final DefaultRedisScript<Long> TRIM_SCRIPT = new DefaultRedisScript<>(
            "local n = redis.call('LLEN', KEYS[1]) "
                    + "if n > tonumber(ARGV[1]) then "
                    + "redis.call('LTRIM', KEYS[1], n - tonumber(ARGV[1]), -1) "
                    + "end "
                    + "return 0",
            Long.class);

    /** 每 N 次 push 触发一次 flush 落库 */
    private static final int FLUSH_EVERY_N = 20;

    private final StringRedisTemplate redisTemplate;
    private final IChatStageBenchmarkMapper mapper;
    private final ChatProperties properties;
    private final UidGenerator uidGenerator;

    /** 每 (stageCode,executionMode) 单独计数，到 FLUSH_EVERY_N 触发 flush */
    private final ConcurrentHashMap<String, AtomicInteger> pushCounters = new ConcurrentHashMap<>();

    public ChatStageBenchmarkServiceImpl(StringRedisTemplate redisTemplate,
                                         IChatStageBenchmarkMapper mapper,
                                         ChatProperties properties,
                                         UidGenerator uidGenerator) {
        this.redisTemplate = redisTemplate;
        this.mapper = mapper;
        this.properties = properties;
        this.uidGenerator = uidGenerator;
    }

    @Override
    public void recordDuration(ChatTraceStageCode stageCode, ExecutionMode executionMode, long durationMs) {
        if (stageCode == null || executionMode == null || durationMs < 0) {
            return;
        }
        String key = redisKey(stageCode, executionMode);
        try {
            int window = windowSize();
            redisTemplate.opsForList().rightPush(key, String.valueOf(durationMs));
            redisTemplate.execute(TRIM_SCRIPT, List.of(key), String.valueOf(window));

            int count = pushCounters
                    .computeIfAbsent(key, k -> new AtomicInteger(0))
                    .incrementAndGet();
            if (count >= FLUSH_EVERY_N) {
                pushCounters.get(key).set(0);
                flush(stageCode, executionMode, key);
            }
        } catch (Exception e) {
            log.debug("benchmark 记录失败（可忽略） → key={} err={}", key, e.getMessage());
        }
    }

    @Override
    public Optional<ChatStageBenchmark> get(ChatTraceStageCode stageCode, ExecutionMode executionMode) {
        if (stageCode == null || executionMode == null) {
            return Optional.empty();
        }
        try {
            return Optional.ofNullable(mapper.selectOne(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<ChatStageBenchmark>()
                    .eq(ChatStageBenchmark::getStageCode, stageCode.getCode())
                    .eq(ChatStageBenchmark::getExecutionMode, executionMode.getCode())));
        } catch (Exception e) {
            log.warn("benchmark 查询失败 → stage={} mode={} err={}", stageCode, executionMode, e.getMessage());
            return Optional.empty();
        }
    }

    @Override
    public List<ChatStageBenchmark> listAll() {
        try {
            return mapper.selectList(null);
        } catch (Exception e) {
            log.warn("benchmark listAll 失败 → err={}", e.getMessage());
            return Collections.emptyList();
        }
    }

    // ======================== 内部 ========================

    private void flush(ChatTraceStageCode stageCode, ExecutionMode executionMode, String key) {
        List<String> raw = redisTemplate.opsForList().range(key, 0, -1);
        if (raw == null || raw.isEmpty()) {
            return;
        }
        List<Long> durations = new ArrayList<>(raw.size());
        for (String s : raw) {
            try {
                durations.add(Long.parseLong(s));
            } catch (NumberFormatException ignored) {
                // 脏数据跳过
            }
        }
        if (durations.isEmpty()) {
            return;
        }
        durations.sort(Comparator.naturalOrder());
        long p50 = percentile(durations, 50);
        long p90 = percentile(durations, 90);
        long p99 = percentile(durations, 99);
        long max = durations.get(durations.size() - 1);
        long min = durations.get(0);
        long avg = durations.stream().mapToLong(Long::longValue).sum() / durations.size();

        try {
            // upsert：先 delete 后 insert 保证 UNIQUE (stage_code, execution_mode) 幂等
            mapper.delete(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<ChatStageBenchmark>()
                    .eq(ChatStageBenchmark::getStageCode, stageCode.getCode())
                    .eq(ChatStageBenchmark::getExecutionMode, executionMode.getCode()));
            ChatStageBenchmark entity = ChatStageBenchmark.builder()
                    .id(uidGenerator.getUid())
                    .stageCode(stageCode.getCode())
                    .executionMode(executionMode.getCode())
                    .p50(p50)
                    .p90(p90)
                    .p99(p99)
                    .avg(avg)
                    .max(max)
                    .min(min)
                    .sampleCount(durations.size())
                    .recentDurations(String.join(",", raw.subList(Math.max(0, raw.size() - 20), raw.size())))
                    .build();
            mapper.insert(entity);
        } catch (Exception e) {
            log.warn("benchmark flush 失败 → stage={} mode={} err={}", stageCode, executionMode, e.getMessage());
        }
    }

    /** 取百分位（线性插值，nearest-rank 简化版）。 */
    private long percentile(List<Long> sortedAsc, int p) {
        if (sortedAsc.isEmpty()) {
            return 0L;
        }
        int idx = (int) Math.ceil(p / 100.0 * sortedAsc.size()) - 1;
        idx = Math.max(0, Math.min(sortedAsc.size() - 1, idx));
        return sortedAsc.get(idx);
    }

    private int windowSize() {
        Integer configured = properties.getTrace().getBenchmarkWindowSize();
        return configured == null || configured <= 0 ? 100 : configured;
    }

    private String redisKey(ChatTraceStageCode stageCode, ExecutionMode executionMode) {
        return KEY_PREFIX + stageCode.name() + ":" + executionMode.name();
    }
}
