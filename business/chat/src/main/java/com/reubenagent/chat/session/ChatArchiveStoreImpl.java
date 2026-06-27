package com.reubenagent.chat.session;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.reubenagent.chat.entity.ChatConversation;
import com.reubenagent.chat.entity.ChatTurn;
import com.reubenagent.chat.enums.ChatTurnStatus;
import com.reubenagent.chat.mapper.IChatConversationMapper;
import com.reubenagent.chat.mapper.IChatTurnMapper;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collections;
import java.util.List;

/**
 * 对话归档仓储 MyBatis 实现。
 *
 * <p>修正 super-agent 问题：</p>
 * <ul>
 *   <li>会话 upsert 依赖 {@code conversation_id} 唯一索引 + 捕获 {@link DuplicateKeyException} 重试，
 *       避免 select-then-insert 竞态。</li>
 *   <li>列表分页用 {@link LambdaQueryWrapper}，不拼裸 SQL 片段。</li>
 *   <li>JSON 列读写由 {@link ChatJsonCodec} 容错降级。</li>
 * </ul>
 *
 * @author reuben
 * @since 2026-06-24
 */
@Slf4j
@Repository
@AllArgsConstructor
public class ChatArchiveStoreImpl implements ChatArchiveStore {

    private final IChatConversationMapper conversationMapper;
    private final IChatTurnMapper turnMapper;

    @Override
    public ConversationArchiveRecord saveConversation(ConversationArchiveRecord record) {
        ChatConversation existing = selectByConversationId(record.getConversationId());
        if (existing == null) {
            // 阶段 1：插入，唯一索引冲突则并发竞争，重试一次走 update 分支
            try {
                ChatConversation entity = toEntity(record);
                conversationMapper.insert(entity);
                return toRecord(entity);
            } catch (DuplicateKeyException e) {
                log.warn("会话插入冲突，转为更新 → conversationId={}", record.getConversationId());
                existing = selectByConversationId(record.getConversationId());
            }
        }
        // 阶段 2：更新（精准设置非 null 字段）
        ChatConversation patch = toEntity(record);
        patch.setId(existing.getId());
        conversationMapper.updateById(patch);
        return toRecord(selectByConversationId(record.getConversationId()));
    }

    @Override
    public ConversationArchiveRecord getConversation(String conversationId) {
        ChatConversation entity = selectByConversationId(conversationId);
        return entity == null ? null : toRecord(entity);
    }

    @Override
    public ConversationArchivePage listConversations(ConversationListQuery query) {
        LambdaQueryWrapper<ChatConversation> wrapper = new LambdaQueryWrapper<ChatConversation>()
                .orderByDesc(ChatConversation::getId);

        if (query.keyword() != null && !query.keyword().isBlank()) {
            wrapper.like(ChatConversation::getTitle, query.keyword().trim());
        }
        if (query.chatMode() != null) {
            wrapper.eq(ChatConversation::getChatMode, query.chatMode().getCode());
        }

        int pageNo = Math.max(query.pageNo(), 1);
        int pageSize = Math.max(query.pageSize(), 1);
        Page<ChatConversation> page = new Page<>(pageNo, pageSize);
        Page<ChatConversation> result = conversationMapper.selectPage(page, wrapper);

        List<ConversationArchiveRecord> records = result.getRecords().stream()
                .map(this::toRecord)
                .toList();
        return ConversationArchivePage.builder()
                .total(result.getTotal())
                .pageNo(pageNo)
                .pageSize(pageSize)
                .records(records)
                .build();
    }

    @Override
    @Transactional(rollbackFor = Exception.class, propagation = Propagation.REQUIRED)
    public ConversationRemovalResult deleteConversation(String conversationId) {
        ChatConversation conversation = selectByConversationId(conversationId);
        if (conversation == null) {
            return ConversationRemovalResult.builder()
                    .conversationId(conversationId)
                    .conversationRemoved(false)
                    .removedTurnCount(0)
                    .build();
        }
        conversationMapper.deleteById(conversation.getId());

        // 软删关联轮次（按 conversation_id 范围）
        LambdaUpdateWrapper<ChatTurn> turnRemove = new LambdaUpdateWrapper<ChatTurn>()
                .eq(ChatTurn::getConversationId, conversationId);
        int removedTurns = turnMapper.delete(turnRemove);

        log.info("软删会话 → conversationId={} turns={}", conversationId, removedTurns);
        return ConversationRemovalResult.builder()
                .conversationId(conversationId)
                .conversationRemoved(true)
                .removedTurnCount(removedTurns)
                .build();
    }

    @Override
    @Transactional(rollbackFor = Exception.class, propagation = Propagation.REQUIRED)
    public int deleteTurnsByConversation(String conversationId) {
        if (conversationId == null) {
            return 0;
        }
        LambdaUpdateWrapper<ChatTurn> turnRemove = new LambdaUpdateWrapper<ChatTurn>()
                .eq(ChatTurn::getConversationId, conversationId);
        int removedTurns = turnMapper.delete(turnRemove);

        // 会话置回 IDLE
        ChatConversation existing = selectByConversationId(conversationId);
        if (existing != null) {
            ChatConversation patch = ChatConversation.builder()
                    .id(existing.getId())
                    .sessionStatus(com.reubenagent.chat.enums.ChatSessionStatus.IDLE.getCode())
                    .build();
            conversationMapper.updateById(patch);
        }
        log.info("重置会话轮次 → conversationId={} turns={}", conversationId, removedTurns);
        return removedTurns;
    }

    @Override
    public Long startTurn(TurnArchiveRecord record) {
        ChatTurn entity = toTurnEntity(record);
        turnMapper.insert(entity);
        return entity.getId();
    }

    @Override
    public void completeTurn(String conversationId, Long turnId, TurnArchiveRecord patch) {
        ChatTurn update = toTurnEntity(patch);
        update.setId(turnId);
        turnMapper.updateById(update);
    }

    @Override
    public List<TurnArchiveRecord> listRecentTurns(String conversationId, int limit) {
        if (conversationId == null) {
            return Collections.emptyList();
        }
        LambdaQueryWrapper<ChatTurn> wrapper = new LambdaQueryWrapper<ChatTurn>()
                .eq(ChatTurn::getConversationId, conversationId)
                .in(ChatTurn::getTurnStatus,
                        ChatTurnStatus.COMPLETED.getCode(),
                        ChatTurnStatus.STOPPED.getCode())
                .orderByDesc(ChatTurn::getId);
        if (limit > 0) {
            wrapper.last("LIMIT " + limit);
        }
        return turnMapper.selectList(wrapper).stream()
                .map(this::toTurnRecord)
                .toList();
    }

    @Override
    public long countTurns(String conversationId) {
        if (conversationId == null) {
            return 0L;
        }
        LambdaQueryWrapper<ChatTurn> wrapper = new LambdaQueryWrapper<ChatTurn>()
                .eq(ChatTurn::getConversationId, conversationId);
        return turnMapper.selectCount(wrapper);
    }

    // ======================== 转换 ========================

    private ChatConversation selectByConversationId(String conversationId) {
        LambdaQueryWrapper<ChatConversation> wrapper = new LambdaQueryWrapper<ChatConversation>()
                .eq(ChatConversation::getConversationId, conversationId);
        return conversationMapper.selectOne(wrapper);
    }

    private ChatConversation toEntity(ConversationArchiveRecord record) {
        return ChatConversation.builder()
                .id(record.getId())
                .conversationId(record.getConversationId())
                .sessionStatus(record.getSessionStatus())
                .chatMode(record.getChatMode())
                .title(record.getTitle())
                .selectedDocumentId(record.getSelectedDocumentId())
                .selectedDocumentName(record.getSelectedDocumentName())
                .build();
    }

    private ConversationArchiveRecord toRecord(ChatConversation entity) {
        return ConversationArchiveRecord.builder()
                .id(entity.getId())
                .conversationId(entity.getConversationId())
                .sessionStatus(entity.getSessionStatus())
                .chatMode(entity.getChatMode())
                .title(entity.getTitle())
                .selectedDocumentId(entity.getSelectedDocumentId())
                .selectedDocumentName(entity.getSelectedDocumentName())
                .createTime(entity.getCreateTime())
                .updateTime(entity.getUpdateTime())
                .build();
    }

    private ChatTurn toTurnEntity(TurnArchiveRecord record) {
        return ChatTurn.builder()
                .id(record.getId())
                .conversationId(record.getConversationId())
                .userPrompt(record.getUserPrompt())
                .replyContent(record.getReplyContent())
                .reasoningNoteList(record.getReasoningNoteList())
                .sourceSnapshotList(record.getSourceSnapshotList())
                .followupSuggestionList(record.getFollowupSuggestionList())
                .toolTraceList(record.getToolTraceList())
                .debugTraceJson(record.getDebugTraceJson())
                .turnStatus(record.getTurnStatus())
                .executionMode(record.getExecutionMode())
                .finishNote(record.getFinishNote())
                .firstTokenLatencyMs(record.getFirstTokenLatencyMs())
                .totalLatencyMs(record.getTotalLatencyMs())
                .build();
    }

    private TurnArchiveRecord toTurnRecord(ChatTurn entity) {
        return TurnArchiveRecord.builder()
                .id(entity.getId())
                .conversationId(entity.getConversationId())
                .userPrompt(entity.getUserPrompt())
                .replyContent(entity.getReplyContent())
                .reasoningNoteList(entity.getReasoningNoteList())
                .sourceSnapshotList(entity.getSourceSnapshotList())
                .followupSuggestionList(entity.getFollowupSuggestionList())
                .toolTraceList(entity.getToolTraceList())
                .debugTraceJson(entity.getDebugTraceJson())
                .turnStatus(entity.getTurnStatus())
                .executionMode(entity.getExecutionMode())
                .finishNote(entity.getFinishNote())
                .firstTokenLatencyMs(entity.getFirstTokenLatencyMs())
                .totalLatencyMs(entity.getTotalLatencyMs())
                .createTime(entity.getCreateTime())
                .updateTime(entity.getUpdateTime())
                .build();
    }
}
