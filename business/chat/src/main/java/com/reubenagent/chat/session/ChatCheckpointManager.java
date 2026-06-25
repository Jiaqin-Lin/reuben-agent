package com.reubenagent.chat.session;

import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.checkpoint.Checkpoint;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.reubenagent.chat.entity.ChatCheckpoint;
import com.reubenagent.chat.entity.ChatThread;
import com.reubenagent.chat.mapper.IChatCheckpointMapper;
import com.reubenagent.chat.mapper.IChatThreadMapper;
import com.reubenagent.framework.uid.UidGenerator;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * ReAct 工作记忆检查点管理器 —— 自建轻量表，脱离 Alibaba {@code MysqlSaver}。
 *
 * <p>Phase 4.3 决策：本期自实现 ReAct loop 时用本表存消息列表；若 Phase 7 改用 Alibaba
 * {@code ReactAgent}，则改包装其 {@code MysqlSaver}，本类保留为 thread 清理入口。</p>
 *
 * <p>语义对齐 super-agent：{@code thread_id}/{@code thread_name} 均存 conversationId。</p>
 *
 * @author reuben
 * @since 2026-06-25
 */
@Slf4j
@Repository
@AllArgsConstructor
public class ChatCheckpointManager {

    private final IChatCheckpointMapper checkpointMapper;
    private final IChatThreadMapper threadMapper;
    private final UidGenerator uidGenerator;

    /**
     * 取线程最新检查点。
     *
     * @param runnableConfig 含 threadId 的运行配置
     */
    public Optional<ChatCheckpoint> get(RunnableConfig runnableConfig) {
        String threadId = resolveThreadId(runnableConfig);
        if (threadId == null) {
            return Optional.empty();
        }
        ensureThread(threadId);
        LambdaQueryWrapper<ChatCheckpoint> wrapper = new LambdaQueryWrapper<ChatCheckpoint>()
                .eq(ChatCheckpoint::getThreadId, threadId)
                .orderByDesc(ChatCheckpoint::getId)
                .last("LIMIT 1");
        return Optional.ofNullable(checkpointMapper.selectOne(wrapper));
    }

    /** 列出线程全部检查点（按 id 升序）。 */
    public List<ChatCheckpoint> list(String threadId) {
        if (threadId == null) {
            return Collections.emptyList();
        }
        LambdaQueryWrapper<ChatCheckpoint> wrapper = new LambdaQueryWrapper<ChatCheckpoint>()
                .eq(ChatCheckpoint::getThreadId, threadId)
                .orderByAsc(ChatCheckpoint::getId);
        return checkpointMapper.selectList(wrapper);
    }

    /**
     * 写入检查点。
     *
     * @param threadId          会话ID
     * @param checkpointId      检查点ID
     * @param parentCheckpointId 父检查点ID
     * @param messagesJson      消息列表JSON
     * @param stateJson         状态JSON
     */
    public void put(String threadId, String checkpointId, String parentCheckpointId,
                    String messagesJson, String stateJson) {
        if (threadId == null) {
            return;
        }
        ChatThread thread = ensureThread(threadId);
        ChatCheckpoint checkpoint = ChatCheckpoint.builder()
                .id(uidGenerator.getUid())
                .threadId(threadId)
                .checkpointId(checkpointId)
                .parentCheckpointId(parentCheckpointId)
                .messagesJson(messagesJson)
                .stateJson(stateJson)
                .build();
        checkpointMapper.insert(checkpoint);

        // 阶段：更新线程最新检查点指针
        ChatThread patch = ChatThread.builder()
                .id(thread.getId())
                .latestCheckpointId(checkpointId)
                .build();
        threadMapper.updateById(patch);
    }

    /**
     * 清空线程全部检查点（重置会话时调用）。
     *
     * @return 清理的检查点数量
     */
    @Transactional(rollbackFor = Exception.class, propagation = org.springframework.transaction.annotation.Propagation.REQUIRED)
    public int clearThread(String threadId) {
        if (threadId == null) {
            return 0;
        }
        LambdaQueryWrapper<ChatCheckpoint> wrapper = new LambdaQueryWrapper<ChatCheckpoint>()
                .eq(ChatCheckpoint::getThreadId, threadId);
        int removed = checkpointMapper.delete(wrapper);

        ChatThread thread = selectThread(threadId);
        if (thread != null) {
            ChatThread patch = ChatThread.builder()
                    .id(thread.getId())
                    .latestCheckpointId(null)
                    .build();
            threadMapper.updateById(patch);
        }
        log.info("清理 ReAct 检查点 → threadId={} removed={}", threadId, removed);
        return removed;
    }

    // ======================== 内部 ========================

    private String resolveThreadId(RunnableConfig runnableConfig) {
        if (runnableConfig == null) {
            return null;
        }
        return runnableConfig.threadId().orElse(null);
    }

    private ChatThread ensureThread(String threadId) {
        ChatThread thread = selectThread(threadId);
        if (thread != null) {
            return thread;
        }
        ChatThread newThread = ChatThread.builder()
                .id(uidGenerator.getUid())
                .threadId(threadId)
                .threadName(threadId)
                .build();
        try {
            threadMapper.insert(newThread);
            return newThread;
        } catch (org.springframework.dao.DuplicateKeyException e) {
            // 阶段：并发建线程，回查
            return selectThread(threadId);
        }
    }

    private ChatThread selectThread(String threadId) {
        LambdaQueryWrapper<ChatThread> wrapper = new LambdaQueryWrapper<ChatThread>()
                .eq(ChatThread::getThreadId, threadId);
        return threadMapper.selectOne(wrapper);
    }
}
