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
import com.reubenagent.chat.trace.ChatStageBenchmarkService;
import com.reubenagent.chat.trace.ChatTraceStageStore;
import com.reubenagent.chat.vo.ChannelExecutionView;
import com.reubenagent.chat.vo.ChatResetVo;
import com.reubenagent.chat.vo.ChatTraceStageView;
import com.reubenagent.chat.vo.ChatTurnDetailView;
import com.reubenagent.chat.vo.ChatTurnVo;
import com.reubenagent.chat.vo.ConversationMemorySummaryVo;
import com.reubenagent.chat.vo.ConversationSessionListVo;
import com.reubenagent.chat.vo.ConversationView;
import com.reubenagent.chat.vo.RetrievalResultView;
import com.reubenagent.chat.vo.StageBenchmarkView;
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
    private final ChatTraceStageStore traceStageStore;
    private final ChatStageBenchmarkService benchmarkService;
    private final com.reubenagent.chat.mapper.IChatRetrievalResultMapper retrievalResultMapper;
    private final com.reubenagent.chat.mapper.IChatChannelExecutionMapper channelExecutionMapper;

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
                .id(uidGenerator.getUid())
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
        return assembleView(record, Collections.emptyList(), 0L, false);
    }

    @Override
    public ConversationView getConversationDetail(String conversationId) {
        ConversationArchiveRecord record = requireConversation(conversationId);
        List<TurnArchiveRecord> recent = archiveStore.listRecentTurns(conversationId, RECENT_TURN_PREVIEW);
        long turnCount = archiveStore.countTurns(conversationId);
        return assembleView(record, recent, turnCount, true);
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
        // 阶段：级联清理 ReAct 检查点 + 长期摘要 + 追踪 stage
        cascadeClearMemory(conversationId);
        safeDeleteTraceStages(conversationId);
        log.info("删除会话 → conversationId={} removed={} turns={}",
                conversationId, result.isConversationRemoved(), result.getRemovedTurnCount());
    }

    /** 删除会话的追踪 stage（失败 warn 不阻断删除主流程）。 */
    private void safeDeleteTraceStages(String conversationId) {
        try {
            traceStageStore.deleteByConversation(conversationId);
        } catch (Exception e) {
            log.warn("清理会话追踪 stage 失败 → conversationId={} err={}", conversationId, e.getMessage());
        }
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

    @Override
    public ChatResetVo resetConversation(String conversationId) {
        requireConversation(conversationId);
        int removedTurns = archiveStore.deleteTurnsByConversation(conversationId);
        int removedCheckpoints = clearCheckpoint(conversationId);
        boolean summaryCleared = deleteSummary(conversationId);
        safeDeleteTraceStages(conversationId);
        log.info("重置会话 → conversationId={} turns={} checkpoints={} summaryCleared={}",
                conversationId, removedTurns, removedCheckpoints, summaryCleared);
        return ChatResetVo.builder()
                .conversationId(conversationId)
                .removedTurnCount(removedTurns)
                .removedCheckpointCount(removedCheckpoints)
                .summaryCleared(summaryCleared)
                .message("会话已重置")
                .build();
    }

    @Override
    public void rebuildSummary(String conversationId) {
        requireConversation(conversationId);
        IChatMemoryService memoryService = memoryServiceProvider.getIfAvailable();
        if (memoryService == null) {
            log.warn("记忆服务不可用，无法重建摘要 → conversationId={}", conversationId);
            return;
        }
        memoryService.rebuildConversationSummary(conversationId);
        log.info("重建会话长期摘要 → conversationId={}", conversationId);
    }

    /** 清理会话检查点，返回清理数量（可选 Bean 缺失返回 0）。 */
    private int clearCheckpoint(String conversationId) {
        ChatCheckpointManager checkpointManager = checkpointManagerProvider.getIfAvailable();
        if (checkpointManager == null) {
            return 0;
        }
        try {
            return checkpointManager.clearThread(conversationId);
        } catch (Exception e) {
            log.warn("清理会话检查点失败 → conversationId={} err={}", conversationId, e.getMessage());
            return 0;
        }
    }

    /** 删除会话长期摘要，返回是否删除（可选 Bean 缺失 / 无摘要返回 false）。 */
    private boolean deleteSummary(String conversationId) {
        IChatMemoryService memoryService = memoryServiceProvider.getIfAvailable();
        if (memoryService == null) {
            return false;
        }
        try {
            memoryService.deleteConversationSummary(conversationId);
            return true;
        } catch (Exception e) {
            log.warn("清理会话长期摘要失败 → conversationId={} err={}", conversationId, e.getMessage());
            return false;
        }
    }

    // ======================== 观测查询（Phase 9.3）========================

    @Override
    public ChatTurnDetailView getTurnDetail(String conversationId, Long turnId) {
        requireConversation(conversationId);
        TurnArchiveRecord turn = requireTurn(conversationId, turnId);
        List<ChatTraceStageView> stages = traceStageStore.listStages(turnId).stream()
                .map(this::toStageView)
                .toList();
        return ChatTurnDetailView.builder()
                .turn(toTurnVo(turn))
                .stageTraces(stages)
                .build();
    }

    @Override
    public List<RetrievalResultView> getRetrievalResults(String conversationId, Long turnId) {
        requireConversation(conversationId);
        com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<com.reubenagent.chat.entity.ChatRetrievalResult> wrapper =
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<com.reubenagent.chat.entity.ChatRetrievalResult>()
                        .eq(com.reubenagent.chat.entity.ChatRetrievalResult::getTurnId, turnId)
                        .orderByAsc(com.reubenagent.chat.entity.ChatRetrievalResult::getSubQuestionIndex)
                        .orderByAsc(com.reubenagent.chat.entity.ChatRetrievalResult::getVectorRank);
        return retrievalResultMapper.selectList(wrapper).stream()
                .map(this::toRetrievalView)
                .toList();
    }

    @Override
    public List<ChannelExecutionView> getChannelExecutions(String conversationId, Long turnId) {
        requireConversation(conversationId);
        com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<com.reubenagent.chat.entity.ChatChannelExecution> wrapper =
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<com.reubenagent.chat.entity.ChatChannelExecution>()
                        .eq(com.reubenagent.chat.entity.ChatChannelExecution::getTurnId, turnId)
                        .orderByAsc(com.reubenagent.chat.entity.ChatChannelExecution::getSubQuestionIndex);
        return channelExecutionMapper.selectList(wrapper).stream()
                .map(this::toChannelView)
                .toList();
    }

    @Override
    public List<StageBenchmarkView> getStageBenchmarks(Integer executionMode) {
        com.reubenagent.chat.enums.ExecutionMode mode = executionMode == null
                ? null : com.reubenagent.chat.enums.ExecutionMode.getFromCode(executionMode);
        if (mode != null) {
            return benchmarkService.listAll().stream()
                    .filter(b -> Objects.equals(b.getExecutionMode(), mode.getCode()))
                    .map(this::toBenchmarkView)
                    .toList();
        }
        return benchmarkService.listAll().stream()
                .map(this::toBenchmarkView)
                .toList();
    }

    private TurnArchiveRecord requireTurn(String conversationId, Long turnId) {
        if (turnId == null) {
            throw new ChatException(ChatErrorCode.PARAM_INVALID, "turnId 不能为空");
        }
        List<TurnArchiveRecord> turns = archiveStore.listRecentTurns(conversationId, Integer.MAX_VALUE);
        return turns.stream()
                .filter(t -> turnId.equals(t.getId()))
                .findFirst()
                .orElseThrow(() -> new ChatException(ChatErrorCode.TURN_NOT_FOUND, String.valueOf(turnId)));
    }

    private ChatTraceStageView toStageView(com.reubenagent.chat.entity.ChatTraceStage s) {
        return ChatTraceStageView.builder()
                .id(s.getId())
                .conversationId(s.getConversationId())
                .turnId(s.getTurnId())
                .traceId(s.getTraceId())
                .stageCode(s.getStageCode())
                .stageName(s.getStageName())
                .stageOrder(s.getStageOrder())
                .executionMode(s.getExecutionMode())
                .stageState(s.getStageState())
                .startTime(s.getStartTime())
                .endTime(s.getEndTime())
                .durationMs(s.getDurationMs())
                .summaryText(s.getSummaryText())
                .errorMessage(s.getErrorMessage())
                .snapshot(parseSnapshot(s.getSnapshotJson()))
                .build();
    }

    /** 将 snapshotJson 反序列化为 Map 供观测页 Inspector 渲染；空串或解析失败返回 null。 */
    @SuppressWarnings("unchecked")
    private java.util.Map<String, Object> parseSnapshot(String snapshotJson) {
        if (snapshotJson == null || snapshotJson.isBlank()) {
            return null;
        }
        try {
            Object parsed = com.alibaba.fastjson.JSON.parse(snapshotJson);
            if (parsed instanceof java.util.Map) {
                return (java.util.Map<String, Object>) parsed;
            }
            return null;
        } catch (Exception e) {
            log.warn("阶段快照 JSON 解析失败 → jsonHead={} err={}",
                    snapshotJson.length() > 60 ? snapshotJson.substring(0, 60) : snapshotJson, e.getMessage());
            return null;
        }
    }

    private RetrievalResultView toRetrievalView(com.reubenagent.chat.entity.ChatRetrievalResult r) {
        return RetrievalResultView.builder()
                .id(r.getId())
                .conversationId(r.getConversationId())
                .turnId(r.getTurnId())
                .subQuestionIndex(r.getSubQuestionIndex())
                .channelType(r.getChannelType())
                .vectorRank(r.getVectorRank())
                .rerankScore(r.getRerankScore())
                .finalScore(r.getFinalScore())
                .gatePassed(r.getGatePassed())
                .isSelected(r.getIsSelected())
                .documentId(r.getDocumentId())
                .documentName(r.getDocumentName())
                .chunkId(r.getChunkId())
                .sectionPath(r.getSectionPath())
                .chunkTextPreview(r.getChunkTextPreview())
                .createTime(r.getCreateTime())
                .build();
    }

    private ChannelExecutionView toChannelView(com.reubenagent.chat.entity.ChatChannelExecution c) {
        return ChannelExecutionView.builder()
                .id(c.getId())
                .conversationId(c.getConversationId())
                .turnId(c.getTurnId())
                .subQuestionIndex(c.getSubQuestionIndex())
                .channelType(c.getChannelType())
                .executionState(c.getExecutionState())
                .startTime(c.getStartTime())
                .endTime(c.getEndTime())
                .durationMs(c.getDurationMs())
                .recalledCount(c.getRecalledCount())
                .acceptedCount(c.getAcceptedCount())
                .finalSelectedCount(c.getFinalSelectedCount())
                .maxScore(c.getMaxScore())
                .minScore(c.getMinScore())
                .build();
    }

    private StageBenchmarkView toBenchmarkView(com.reubenagent.chat.entity.ChatStageBenchmark b) {
        return StageBenchmarkView.builder()
                .stageCode(b.getStageCode())
                .executionMode(b.getExecutionMode())
                .p50(b.getP50())
                .p90(b.getP90())
                .p99(b.getP99())
                .avg(b.getAvg())
                .max(b.getMax())
                .min(b.getMin())
                .sampleCount(b.getSampleCount())
                .recentDurations(b.getRecentDurations())
                .build();
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
                                          List<TurnArchiveRecord> recent, long turnCount,
                                          boolean withMemoryContext) {
        List<ChatTurnVo> turnVos = recent.stream()
                .map(this::toTurnVo)
                .toList();
        ChatTurnVo latest = turnVos.isEmpty() ? null : turnVos.get(0);
        ConversationView.ConversationViewBuilder builder = ConversationView.builder()
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
                .updateTime(record.getUpdateTime());

        if (withMemoryContext) {
            String latestUser = null;
            String latestAssistant = null;
            for (int i = recent.size() - 1; i >= 0; i--) {
                TurnArchiveRecord t = recent.get(i);
                if (latestUser == null && t.getUserPrompt() != null && !t.getUserPrompt().isBlank()) {
                    latestUser = t.getUserPrompt();
                }
                if (latestAssistant == null && t.getReplyContent() != null && !t.getReplyContent().isBlank()) {
                    latestAssistant = t.getReplyContent();
                }
                if (latestUser != null && latestAssistant != null) {
                    break;
                }
            }
            int messageCount = (int) recent.stream()
                    .filter(t -> (t.getUserPrompt() != null && !t.getUserPrompt().isBlank())
                            || (t.getReplyContent() != null && !t.getReplyContent().isBlank()))
                    .count();
            builder.latestUserMessage(latestUser)
                    .latestAssistantMessage(latestAssistant)
                    .messageCount(messageCount * 2)
                    .checkpointCount(countCheckpoints(record.getConversationId()))
                    .memorySummary(loadMemorySummaryVo(record.getConversationId()));
        }
        return builder.build();
    }

    /** 取会话 ReAct 检查点数（可选 Bean 缺失返回 0）。 */
    private Integer countCheckpoints(String conversationId) {
        ChatCheckpointManager checkpointManager = checkpointManagerProvider.getIfAvailable();
        if (checkpointManager == null) {
            return 0;
        }
        try {
            return checkpointManager.list(conversationId).size();
        } catch (Exception e) {
            log.warn("统计会话检查点失败 → conversationId={} err={}", conversationId, e.getMessage());
            return 0;
        }
    }

    /** 取会话长期摘要快照（可选 Bean 缺失 / 无摘要返回 null）。 */
    private ConversationMemorySummaryVo loadMemorySummaryVo(String conversationId) {
        IChatMemoryService memoryService = memoryServiceProvider.getIfAvailable();
        if (memoryService == null) {
            return null;
        }
        try {
            com.reubenagent.chat.entity.ChatMemorySummary entity = memoryService.getSummaryEntity(conversationId);
            if (entity == null) {
                return null;
            }
            com.reubenagent.chat.model.memory.ChatSummaryPayload payload = memoryService.getConversationSummary(conversationId);
            boolean applied = entity.getSummaryText() != null && !entity.getSummaryText().isBlank();
            return ConversationMemorySummaryVo.builder()
                    .compressionApplied(applied)
                    .coveredExchangeCount(entity.getCoveredTurnCount())
                    .summaryVersion(entity.getSummaryVersion())
                    .compressionCount(entity.getCompressionCount())
                    .summaryText(payload == null ? entity.getSummaryText() : payload.getSummary())
                    .build();
        } catch (Exception e) {
            log.warn("加载会话长期摘要失败 → conversationId={} err={}", conversationId, e.getMessage());
            return null;
        }
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
                .sourceSnapshotList(turn.getSourceSnapshotList())
                .followupSuggestionList(turn.getFollowupSuggestionList())
                .toolTraceList(turn.getToolTraceList())
                .debugTraceJson(turn.getDebugTraceJson())
                .finishNote(turn.getFinishNote())
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
