package com.reubenagent.chat.service.impl;

import com.reubenagent.chat.dto.ChatSessionCreateDto;
import com.reubenagent.chat.dto.ChatSessionListDto;
import com.reubenagent.chat.dto.ChatRenameDto;
import com.reubenagent.chat.enums.ChatErrorCode;
import com.reubenagent.chat.enums.ChatMode;
import com.reubenagent.chat.enums.ChatSessionStatus;
import com.reubenagent.chat.enums.ChatTurnStatus;
import com.reubenagent.chat.exception.ChatException;
import com.reubenagent.chat.session.ChatArchiveStore;
import com.reubenagent.chat.session.ChatCheckpointManager;
import com.reubenagent.chat.session.ConversationArchivePage;
import com.reubenagent.chat.session.ConversationArchiveRecord;
import com.reubenagent.chat.session.ChatArchiveStore.ConversationListQuery;
import com.reubenagent.chat.session.ConversationRemovalResult;
import com.reubenagent.chat.session.TurnArchiveRecord;
import com.reubenagent.chat.service.IChatMemoryService;
import com.reubenagent.chat.service.IChatSessionService;
import com.reubenagent.chat.vo.ConversationSessionListVo;
import com.reubenagent.chat.vo.ConversationView;
import com.reubenagent.chat.vo.ChatTurnVo;
import com.reubenagent.common.dto.PageVo;
import com.reubenagent.framework.uid.UidGenerator;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * 会话业务编排实现。
 *
 * @author reuben
 * @since 2026-06-24
 */
@Slf4j
@Service
@AllArgsConstructor
public class ChatSessionServiceImpl implements IChatSessionService {

    private static final int RECENT_TURN_PREVIEW = 20;

    private final ChatArchiveStore archiveStore;
    private final UidGenerator uidGenerator;
    private final ObjectProvider<ChatCheckpointManager> checkpointManagerProvider;
    private final ObjectProvider<IChatMemoryService> memoryServiceProvider;

    @Override
    public ConversationView createConversation(ChatSessionCreateDto dto) {
        ChatMode mode = ChatMode.getFromCode(dto.getChatMode());
        if (mode == null) {
            throw new ChatException(ChatErrorCode.PARAM_INVALID, "非法的对话模式: " + dto.getChatMode());
        }
        if (mode == ChatMode.DOCUMENT && dto.getSelectedDocumentId() == null) {
            throw new ChatException(ChatErrorCode.PARAM_INVALID, "DOCUMENT 模式必须指定文档");
        }

        String conversationId = generateConversationId();
        String title = (dto.getTitle() != null && !dto.getTitle().isBlank())
                ? dto.getTitle()
                : defaultTitle(mode, dto.getSelectedDocumentName());

        ConversationArchiveRecord record = ConversationArchiveRecord.builder()
                .conversationId(conversationId)
                .sessionStatus(ChatSessionStatus.IDLE.getCode())
                .chatMode(mode.getCode())
                .title(title)
                .selectedDocumentId(dto.getSelectedDocumentId())
                .selectedDocumentName(dto.getSelectedDocumentName())
                .build();
        archiveStore.saveConversation(record);

        log.info("创建会话 → conversationId={} mode={} documentId={}",
                conversationId, mode, dto.getSelectedDocumentId());
        return assembleView(record, Collections.emptyList(), 0L);
    }

    @Override
    public ConversationView getConversationDetail(String conversationId) {
        ConversationArchiveRecord record = requireConversation(conversationId);
        List<TurnArchiveRecord> recent = archiveStore.listRecentTurns(conversationId, RECENT_TURN_PREVIEW);
        long turnCount = archiveStore.countTurns(conversationId);
        return assembleView(record, recent, turnCount);
    }

    @Override
    public PageVo<ConversationSessionListVo> listConversations(ChatSessionListDto dto) {
        int pageNo = dto.getPageNo() == null ? 1 : dto.getPageNo();
        int pageSize = dto.getPageSize() == null ? 10 : dto.getPageSize();

        ChatMode mode = dto.getChatMode() == null ? null : ChatMode.getFromCode(dto.getChatMode());
        ChatTurnStatus turnStatus = dto.getTurnStatus() == null ? null : ChatTurnStatus.getFromCode(dto.getTurnStatus());

        ConversationListQuery query = new ConversationListQuery(
                pageNo, pageSize, dto.getKeyword(), mode, turnStatus);
        ConversationArchivePage page = archiveStore.listConversations(query);

        List<ConversationSessionListVo> items = page.getRecords().stream()
                .map(this::toListVo)
                .toList();
        return PageVo.of(page.getTotal(), page.getPageNo(), page.getPageSize(), items);
    }

    @Override
    public void deleteConversation(String conversationId) {
        ConversationRemovalResult result = archiveStore.deleteConversation(conversationId);
        // 阶段：级联清理 ReAct 检查点 + 长期摘要
        cascadeClearMemory(conversationId);
        log.info("删除会话 → conversationId={} removed={} turns={}",
                conversationId, result.isConversationRemoved(), result.getRemovedTurnCount());
    }

    /** 删除会话后级联清理 checkpoint + memory summary（可选 Bean，缺失跳过）。 */
    private void cascadeClearMemory(String conversationId) {
        ChatCheckpointManager checkpointManager = checkpointManagerProvider.getIfAvailable();
        if (checkpointManager != null) {
            try {
                checkpointManager.clearThread(conversationId);
            } catch (Exception e) {
                log.warn("清理会话检查点失败 → conversationId={} err={}", conversationId, e.getMessage());
            }
        }
        IChatMemoryService memoryService = memoryServiceProvider.getIfAvailable();
        if (memoryService != null) {
            try {
                memoryService.deleteConversationSummary(conversationId);
            } catch (Exception e) {
                log.warn("清理会话长期摘要失败 → conversationId={} err={}", conversationId, e.getMessage());
            }
        }
    }

    @Override
    public String renameConversation(ChatRenameDto dto) {
        requireConversation(dto.getConversationId());

        String title = (dto.getTitle() != null && !dto.getTitle().isBlank())
                ? dto.getTitle().trim()
                : generateTitle(dto.getConversationId());
        if (title == null || title.isBlank()) {
            title = "未命名会话";
        }

        ConversationArchiveRecord patch = ConversationArchiveRecord.builder()
                .conversationId(dto.getConversationId())
                .title(title)
                .build();
        archiveStore.saveConversation(patch);
        log.info("重命名会话 → conversationId={} title={}", dto.getConversationId(), title);
        return title;
    }

    @Override
    public String generateTitle(String firstQuestion) {
        // Phase 3 接入 ObservedChatModelService 后由 LLM 生成；当前规则兜底
        if (firstQuestion == null || firstQuestion.isBlank()) {
            return null;
        }
        String trimmed = firstQuestion.trim().replaceAll("\\s+", " ");
        return trimmed.length() <= 32 ? trimmed : trimmed.substring(0, 32) + "…";
    }

    // ======================== 内部 ========================

    private ConversationArchiveRecord requireConversation(String conversationId) {
        ConversationArchiveRecord record = archiveStore.getConversation(conversationId);
        if (record == null) {
            throw new ChatException(ChatErrorCode.CONVERSATION_NOT_FOUND, conversationId);
        }
        return record;
    }

    private ConversationView assembleView(ConversationArchiveRecord record,
                                          List<TurnArchiveRecord> recent, long turnCount) {
        List<ChatTurnVo> turnVos = recent.stream()
                .map(this::toTurnVo)
                .toList();
        ChatTurnVo latest = turnVos.isEmpty() ? null : turnVos.get(0);
        return ConversationView.builder()
                .conversationId(record.getConversationId())
                .chatMode(record.getChatMode())
                .sessionStatus(record.getSessionStatus())
                .title(record.getTitle())
                .selectedDocumentId(record.getSelectedDocumentId())
                .selectedDocumentName(record.getSelectedDocumentName())
                .turnCount(turnCount)
                .latestTurn(latest)
                .recentTurns(turnVos)
                .createTime(record.getCreateTime())
                .updateTime(record.getUpdateTime())
                .build();
    }

    private ConversationSessionListVo toListVo(ConversationArchiveRecord record) {
        long turnCount = archiveStore.countTurns(record.getConversationId());
        List<TurnArchiveRecord> recent = archiveStore.listRecentTurns(record.getConversationId(), 1);
        ChatTurnVo latest = recent.isEmpty() ? null : toTurnVo(recent.get(0));
        return ConversationSessionListVo.builder()
                .conversationId(record.getConversationId())
                .chatMode(record.getChatMode())
                .sessionStatus(record.getSessionStatus())
                .title(record.getTitle())
                .selectedDocumentId(record.getSelectedDocumentId())
                .selectedDocumentName(record.getSelectedDocumentName())
                .turnCount(turnCount)
                .latestTurn(latest)
                .createTime(record.getCreateTime())
                .updateTime(record.getUpdateTime())
                .build();
    }

    private ChatTurnVo toTurnVo(TurnArchiveRecord turn) {
        return ChatTurnVo.builder()
                .turnId(turn.getId())
                .conversationId(turn.getConversationId())
                .userPrompt(turn.getUserPrompt())
                .replyContent(turn.getReplyContent())
                .turnStatus(turn.getTurnStatus())
                .executionMode(turn.getExecutionMode())
                .firstTokenLatencyMs(turn.getFirstTokenLatencyMs())
                .totalLatencyMs(turn.getTotalLatencyMs())
                .createTime(turn.getCreateTime())
                .build();
    }

    private String generateConversationId() {
        // 雪花 ID 转为 16 进制字符串作为 conversationId，全局唯一
        return Long.toHexString(uidGenerator.getUid());
    }

    private String defaultTitle(ChatMode mode, String documentName) {
        String modeName = Objects.requireNonNullElse(mode.getMsg(), "对话");
        if (documentName != null && !documentName.isBlank()) {
            return documentName + " · " + modeName;
        }
        return modeName + " 会话";
    }
}
