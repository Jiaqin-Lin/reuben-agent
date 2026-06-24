package com.reubenagent.chat.orchestrate;

import com.reubenagent.chat.enums.ExecutionMode;
import com.reubenagent.chat.enums.ChatErrorCode;
import com.reubenagent.chat.exception.ChatException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * 执行器注册表 —— 按 {@link ExecutionMode} 分发。
 *
 * <p>启动时收集所有 {@link ConversationExecutor} Bean，按 {@code mode()} 装入 {@link EnumMap}。
 * 缺失模式抛 {@link ChatException}({@link ChatErrorCode#EXECUTOR_NOT_FOUND})（修正 super-agent
 * 抛 {@code IllegalStateException}）。</p>
 *
 * @author reuben
 * @since 2026-06-24
 */
@Slf4j
@Component
public class ConversationExecutorRegistry {

    private final Map<ExecutionMode, ConversationExecutor> executors = new EnumMap<>(ExecutionMode.class);

    @Autowired
    public ConversationExecutorRegistry(List<ConversationExecutor> executorList) {
        for (ConversationExecutor executor : executorList) {
            ConversationExecutor prev = executors.put(executor.mode(), executor);
            if (prev != null) {
                log.warn("执行器模式冲突 → mode={} old={} new={}",
                        executor.mode(), prev.getClass().getSimpleName(), executor.getClass().getSimpleName());
            }
        }
        log.info("对话执行器注册完成 → {}", executors.keySet());
    }

    public ConversationExecutor get(ExecutionMode mode) {
        ConversationExecutor executor = executors.get(mode);
        if (executor == null) {
            throw new ChatException(ChatErrorCode.EXECUTOR_NOT_FOUND, mode == null ? "null" : mode.name());
        }
        return executor;
    }
}
