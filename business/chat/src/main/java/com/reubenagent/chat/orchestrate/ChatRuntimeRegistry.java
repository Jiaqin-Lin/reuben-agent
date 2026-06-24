package com.reubenagent.chat.orchestrate;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 内存态在途任务表 —— {@code conversationId → ChatTaskInfo}。
 *
 * <p>本期单实例够用（TODO 跨实例停止需 Redis 分布式 registry）。
 * 修正 super-agent 问题 11：{@code remove} 只提供 2-arg 版本（CAS 语义，
 * 防止新执行接管后被旧执行误删），不实现单 arg {@code remove}。</p>
 *
 * @author reuben
 * @since 2026-06-24
 */
@Slf4j
@Component
public class ChatRuntimeRegistry {

    private final ConcurrentMap<String, ChatTaskInfo> taskMap = new ConcurrentHashMap<>();

    /** 注册任务，会话已有在途任务则返回 false。 */
    public boolean register(ChatTaskInfo taskInfo) {
        return taskMap.putIfAbsent(taskInfo.getConversationId(), taskInfo) == null;
    }

    public Optional<ChatTaskInfo> get(String conversationId) {
        return Optional.ofNullable(taskMap.get(conversationId));
    }

    /**
     * CAS 移除：仅当当前映射等于 expectedTaskInfo 时移除。
     * 不提供单 arg remove（会误删被新执行接管的会话）。
     */
    public boolean remove(String conversationId, ChatTaskInfo expectedTaskInfo) {
        if (conversationId == null || expectedTaskInfo == null) {
            return false;
        }
        return taskMap.remove(conversationId, expectedTaskInfo);
    }
}
