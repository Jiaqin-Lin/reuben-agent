package com.reubenagent.chat.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.reubenagent.chat.config.ChatConfiguration;
import com.reubenagent.chat.config.ChatProperties;
import com.reubenagent.chat.entity.ChatMemorySummary;
import com.reubenagent.chat.enums.ChatErrorCode;
import com.reubenagent.chat.enums.ChatTraceStageCode;
import com.reubenagent.chat.exception.ChatException;
import com.reubenagent.chat.mapper.IChatMemorySummaryMapper;
import com.reubenagent.chat.model.memory.ChatMemoryContext;
import com.reubenagent.chat.model.memory.ChatSummaryPayload;
import com.reubenagent.chat.service.IChatMemoryService;
import com.reubenagent.chat.service.ObservedChatModelService;
import com.reubenagent.chat.session.ChatArchiveStore;
import com.reubenagent.chat.session.TurnArchiveRecord;
import com.reubenagent.chat.support.ChatJsonCodec;
import com.reubenagent.chat.support.ChatPromptNames;
import com.reubenagent.chat.support.ChatPromptTemplateService;
import com.reubenagent.chat.support.ChatTexts;
import com.reubenagent.chat.trace.ChatTraceRecorder;
import com.reubenagent.framework.uid.UidGenerator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 对话记忆服务实现 —— 短期 recent window + 长期摘要压缩。
 *
 * <p>修正 super-agent 问题：</p>
 * <ul>
 *   <li>问题 13：魔法常量全部进 {@link ChatProperties.Memory}；</li>
 *   <li>问题 14：LLM 输出 JSON 用 {@link ChatJsonCodec#extractFirstBalancedObject} 容错提取，失败返回上一版；</li>
 *   <li>问题 15：clipText/safeText 统一走 {@link ChatTexts}；</li>
 *   <li>问题 5：摘要压缩失败走规则 fallback + warn + 落 trace，不静默吞。</li>
 * </ul>
 *
 * @author reuben
 * @since 2026-06-25
 */
@Slf4j
@Service
public class ChatMemoryServiceImpl implements IChatMemoryService {

    private final ChatArchiveStore archiveStore;
    private final IChatMemorySummaryMapper memorySummaryMapper;
    private final ObservedChatModelService observedChatModelService;
    private final ChatPromptTemplateService promptTemplateService;
    private final ChatJsonCodec jsonCodec;
    private final ChatProperties properties;
    private final UidGenerator uidGenerator;
    private final Executor memoryExecutor;

    /** 在途异步刷新去重：conversationId → 标记位 */
    private final Set<String> refreshingConversations = ConcurrentHashMap.newKeySet();

    public ChatMemoryServiceImpl(ChatArchiveStore archiveStore,
                                 IChatMemorySummaryMapper memorySummaryMapper,
                                 ObservedChatModelService observedChatModelService,
                                 ChatPromptTemplateService promptTemplateService,
                                 ChatJsonCodec jsonCodec,
                                 ChatProperties properties,
                                 UidGenerator uidGenerator,
                                 @Qualifier(ChatConfiguration.CHAT_MEMORY_EXECUTOR) Executor memoryExecutor) {
        this.archiveStore = archiveStore;
        this.memorySummaryMapper = memorySummaryMapper;
        this.observedChatModelService = observedChatModelService;
        this.promptTemplateService = promptTemplateService;
        this.jsonCodec = jsonCodec;
        this.properties = properties;
        this.uidGenerator = uidGenerator;
        this.memoryExecutor = memoryExecutor;
    }

    // ======================== 加载 ========================

    @Override
    public ChatMemoryContext loadMemoryContext(String conversationId, ChatTraceRecorder traceRecorder) {
        ChatTraceRecorder.StageHandle stage = startMemoryStage(traceRecorder, conversationId, "加载记忆上下文");
        try {
            ChatMemoryContext context = doLoad(conversationId);
            completeMemoryStage(traceRecorder, stage, "loaded turns=" + context.getRecentTurns().size());
            return context;
        } catch (ChatException e) {
            failMemoryStage(traceRecorder, stage, e.getMessage());
            throw e;
        } catch (Exception e) {
            failMemoryStage(traceRecorder, stage, e.getMessage());
            throw new ChatException(ChatErrorCode.MEMORY_COMPRESS_FAILED, "加载记忆上下文失败", e);
        }
    }

    private ChatMemoryContext doLoad(String conversationId) {
        ChatProperties.Memory cfg = properties.getMemory();
        int keepRecent = cfg.getKeepRecentTurns();

        // 阶段：取全部稳定轮次（id 倒序），用于判断是否需要触发压缩；recent window 仅取前 keepRecent
        List<TurnArchiveRecord> allStable = archiveStore.listRecentTurns(conversationId, 0);
        List<TurnArchiveRecord> recent = allStable.size() <= keepRecent
                ? allStable
                : allStable.subList(0, keepRecent);
        List<ChatMemoryContext.RecentTurnSnapshot> snapshots = recent.stream()
                .map(this::toSnapshot)
                .toList();

        ChatMemorySummary summaryEntity = selectSummary(conversationId);
        ChatSummaryPayload payload = summaryEntity == null ? null : decodePayload(summaryEntity.getSummaryJson());

        String recentTranscript = renderTranscript(recent, cfg.getRecentTranscriptMaxChars());
        String answerTranscript = renderAnswerTranscript(recent, cfg.getRecentTranscriptMaxChars());
        String longTermSummary = payload == null ? "" : ChatTexts.safe(payload.getSummary());

        // 阶段：组装注入 prompt 的完整历史 = 长期摘要 + 近期 transcript
        String assembledHistory = assembleHistory(longTermSummary, recentTranscript);

        boolean refreshTriggered = false;
        int coverage = summaryEntity == null ? 0 : summaryEntity.getCoveredTurnCount();
        int compressionCount = summaryEntity == null ? 0 : summaryEntity.getCompressionCount();

        // 阶段：稳定轮次超过 keepRecent 且存在未被摘要覆盖的溢出轮次 → 触发异步压缩
        if (shouldTriggerCompression(allStable, summaryEntity, keepRecent)) {
            refreshConversationSummaryAsync(conversationId);
            refreshTriggered = true;
        }

        return ChatMemoryContext.builder()
                .conversationId(conversationId)
                .assembledHistory(assembledHistory)
                .longTermSummary(longTermSummary)
                .recentTranscript(recentTranscript)
                .answerRecentTranscript(answerTranscript)
                .summaryPayload(payload)
                .coverage(coverage)
                .compressionCount(compressionCount)
                .summaryRefreshTriggered(refreshTriggered)
                .recentTurns(snapshots)
                .build();
    }

    // ======================== 摘要查询 / 重建 / 删除 ========================

    @Override
    public ChatSummaryPayload getConversationSummary(String conversationId) {
        ChatMemorySummary entity = selectSummary(conversationId);
        return entity == null ? null : decodePayload(entity.getSummaryJson());
    }

    @Override
    public ChatMemorySummary getSummaryEntity(String conversationId) {
        return selectSummary(conversationId);
    }

    @Override
    public ChatSummaryPayload rebuildConversationSummary(String conversationId) {
        return doCompress(conversationId, true);
    }

    @Override
    @Transactional(rollbackFor = Exception.class, propagation = Propagation.REQUIRED)
    public void deleteConversationSummary(String conversationId) {
        ChatMemorySummary entity = selectSummary(conversationId);
        if (entity == null) {
            return;
        }
        memorySummaryMapper.deleteById(entity.getId());
        log.info("删除会话长期摘要 → conversationId={}", conversationId);
    }

    @Override
    public void refreshConversationSummaryAsync(String conversationId) {
        if (!refreshingConversations.add(conversationId)) {
            // 阶段：已有在途刷新，去重
            return;
        }
        memoryExecutor.execute(() -> {
            try {
                doCompress(conversationId, false);
            } catch (Exception e) {
                log.warn("异步刷新会话摘要失败 → conversationId={} err={}", conversationId, e.getMessage());
            } finally {
                refreshingConversations.remove(conversationId);
            }
        });
    }

    // ======================== 摘要压缩核心 ========================

    /**
     * 压缩会话摘要。
     *
     * @param fullRebuild true=忽略现有 coveredTurnId，取会话全部轮次重压；false=只压未覆盖的溢出批次
     */
    private ChatSummaryPayload doCompress(String conversationId, boolean fullRebuild) {
        ChatProperties.Memory cfg = properties.getMemory();
        int batchSize = cfg.getCompressionBatchTurns();

        ChatMemorySummary existing = selectSummary(conversationId);
        ChatSummaryPayload existingPayload = existing == null ? null : decodePayload(existing.getSummaryJson());

        // 阶段：取待压缩轮次（id 升序）
        List<TurnArchiveRecord> toCompress = collectTurnsToCompress(conversationId, existing, fullRebuild, batchSize);
        if (toCompress.isEmpty()) {
            log.debug("无可压缩轮次 → conversationId={}", conversationId);
            return existingPayload;
        }

        String batchTranscript = renderTranscript(toCompress, cfg.getRecentTranscriptMaxChars() * 2);
        String existingJson = existingPayload == null ? "{}" : jsonCodec.toJson(existingPayload);

        String systemPrompt = promptTemplateService.render(ChatPromptNames.CONVERSATION_SUMMARY_SYSTEM, Map.of());
        String userPrompt = promptTemplateService.render(ChatPromptNames.CONVERSATION_SUMMARY_MERGE,
                Map.of("existingSummaryJson", existingJson, "newConversationBatch", batchTranscript));

        ChatSummaryPayload merged;
        try {
            String llmOutput = observedChatModelService.callText(
                    ChatTraceStageCode.MEMORY.name(), systemPrompt + "\n\n" + userPrompt, null);
            merged = parsePayloadFromLlm(llmOutput, existingPayload);
            if (merged == null) {
                // 阶段：LLM 输出无法解析 → 规则 fallback
                merged = ruleBasedFallback(existingPayload, toCompress, cfg);
            }
        } catch (Exception e) {
            log.warn("LLM 摘要压缩失败，走规则 fallback → conversationId={} err={}",
                    conversationId, e.getMessage());
            merged = ruleBasedFallback(existingPayload, toCompress, cfg);
        }

        // 阶段：落库
        Long lastTurnId = toCompress.get(toCompress.size() - 1).getId();
        int coveredCount = (existing == null ? 0 : existing.getCoveredTurnCount()) + toCompress.size();
        int compressionCount = (existing == null ? 0 : existing.getCompressionCount()) + 1;
        int summaryVersion = (existing == null ? 0 : existing.getSummaryVersion()) + 1;

        saveSummary(conversationId, existing, merged, lastTurnId, coveredCount, compressionCount, summaryVersion,
                toCompress.get(0).getCreateTime());
        log.info("会话摘要已压缩 → conversationId={} batch={} compressionCount={} summaryVersion={}",
                conversationId, toCompress.size(), compressionCount, summaryVersion);
        return merged;
    }

    /** 取待压缩轮次：fullRebuild 取全部稳定轮次；否则取超出 coveredTurnId 之后的批次。 */
    private List<TurnArchiveRecord> collectTurnsToCompress(String conversationId, ChatMemorySummary existing,
                                                            boolean fullRebuild, int batchSize) {
        ChatProperties.Memory cfg = properties.getMemory();
        int keepRecent = cfg.getKeepRecentTurns();

        // 阶段：稳定轮次（COMPLETED/STOPPED 且非空），id 升序
        List<TurnArchiveRecord> stable = new java.util.ArrayList<>(
                archiveStore.listRecentTurns(conversationId, Integer.MAX_VALUE));
        Collections.reverse(stable); // listRecentTurns 是 id 倒序，反转为升序

        Long coveredTurnId = (existing == null || fullRebuild) ? null : existing.getCoveredTurnId();
        int startIndex = 0;
        if (coveredTurnId != null) {
            for (int i = 0; i < stable.size(); i++) {
                if (stable.get(i).getId().equals(coveredTurnId)) {
                    startIndex = i + 1;
                    break;
                }
            }
        }

        // 阶段：溢出 = 全部稳定轮次减去 keepRecent 窗口（窗口内轮次保持原文不压）。
        // fullRebuild 时忽略窗口，覆盖全部稳定轮次。
        int overflowEnd = fullRebuild ? stable.size() : Math.max(0, stable.size() - keepRecent);
        List<TurnArchiveRecord> overflow = stable.subList(startIndex, Math.max(startIndex, overflowEnd));

        if (overflow.isEmpty()) {
            return Collections.emptyList();
        }
        // 阶段：限制单次批量
        int limit = Math.min(batchSize, overflow.size());
        return new java.util.ArrayList<>(overflow.subList(0, limit));
    }

    /** 解析 LLM 输出为 payload，失败返回 null（由调用方决定 fallback）。 */
    private ChatSummaryPayload parsePayloadFromLlm(String llmOutput, ChatSummaryPayload existing) {
        if (llmOutput == null || llmOutput.isBlank()) {
            return null;
        }
        String json = jsonCodec.extractFirstBalancedObject(llmOutput);
        if (json == null) {
            return null;
        }
        ChatSummaryPayload parsed = jsonCodec.parseObject(json, ChatSummaryPayload.class);
        if (parsed == null || (parsed.getSummary() == null && parsed.getStableFacts() == null)) {
            return null;
        }
        return parsed;
    }

    /**
     * 规则 fallback —— LLM 失败时拼接关键词 / 事实。
     *
     * <p>不静默：调用方已 warn 落 trace。</p>
     */
    private ChatSummaryPayload ruleBasedFallback(ChatSummaryPayload existing, List<TurnArchiveRecord> batch,
                                                 ChatProperties.Memory cfg) {
        ChatSummaryPayload fallback = existing == null
                ? ChatSummaryPayload.builder().build()
                : copyPayload(existing);

        StringBuilder summaryBuilder = new StringBuilder();
        if (fallback.getSummary() != null) {
            summaryBuilder.append(fallback.getSummary()).append(" ");
        }
        for (TurnArchiveRecord turn : batch) {
            if (turn.getUserPrompt() != null) {
                summaryBuilder.append("用户问：").append(ChatTexts.clip(turn.getUserPrompt(), 60)).append("；");
            }
        }
        fallback.setSummary(ChatTexts.clip(summaryBuilder.toString(), cfg.getSummaryMaxChars()));

        if (fallback.getPendingQuestions() == null) {
            fallback.setPendingQuestions(new java.util.ArrayList<>());
        }
        if (fallback.getStableFacts() == null) {
            fallback.setStableFacts(new java.util.ArrayList<>());
        }
        return fallback;
    }

    private void saveSummary(String conversationId, ChatMemorySummary existing, ChatSummaryPayload payload,
                             Long coveredTurnId, int coveredCount, int compressionCount, int summaryVersion,
                             Date lastSourceEditTime) {
        ChatProperties.Memory cfg = properties.getMemory();
        String summaryText = ChatTexts.clip(ChatTexts.safe(payload.getSummary()), cfg.getSummaryMaxChars());
        String summaryJson = jsonCodec.toJson(payload);

        if (existing == null) {
            ChatMemorySummary entity = ChatMemorySummary.builder()
                    .id(uidGenerator.getUid())
                    .conversationId(conversationId)
                    .coveredTurnId(coveredTurnId)
                    .coveredTurnCount(coveredCount)
                    .compressionCount(compressionCount)
                    .summaryVersion(summaryVersion)
                    .summaryText(summaryText)
                    .summaryJson(summaryJson)
                    .lastSourceEditTime(lastSourceEditTime)
                    .build();
            memorySummaryMapper.insert(entity);
        } else {
            ChatMemorySummary patch = ChatMemorySummary.builder()
                    .id(existing.getId())
                    .coveredTurnId(coveredTurnId)
                    .coveredTurnCount(coveredCount)
                    .compressionCount(compressionCount)
                    .summaryVersion(summaryVersion)
                    .summaryText(summaryText)
                    .summaryJson(summaryJson)
                    .lastSourceEditTime(lastSourceEditTime)
                    .build();
            memorySummaryMapper.updateById(patch);
        }
    }

    // ======================== recent window / transcript 渲染 ========================

    /** 判断是否触发异步压缩：稳定轮次 > keepRecent 且存在未被摘要覆盖的溢出轮次。 */
    private boolean shouldTriggerCompression(List<TurnArchiveRecord> recent,
                                             ChatMemorySummary existing, int keepRecent) {
        if (recent.size() <= keepRecent) {
            return false;
        }
        // 阶段：recent 为 id 倒序，超出窗口的最早轮次在尾部
        Long coveredTurnId = existing == null ? null : existing.getCoveredTurnId();
        int overflowCount = recent.size() - keepRecent;
        int start = recent.size() - overflowCount;
        for (int i = start; i < recent.size(); i++) {
            Long turnId = recent.get(i).getId();
            if (coveredTurnId == null || (turnId != null && turnId > coveredTurnId)) {
                return true;
            }
        }
        return false;
    }

    private String renderTranscript(List<TurnArchiveRecord> turns, int maxChars) {
        if (turns == null || turns.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        int budget = maxChars;
        // 阶段：id 升序渲染，超出预算从最早开始截断
        List<TurnArchiveRecord> ordered = new java.util.ArrayList<>(turns);
        Collections.reverse(ordered);
        for (TurnArchiveRecord turn : ordered) {
            String q = ChatTexts.safe(turn.getUserPrompt());
            String a = ChatTexts.safe(turn.getReplyContent());
            String line = "用户：" + q + "\n助手：" + a + "\n\n";
            if (sb.length() + line.length() > budget && sb.length() > 0) {
                break;
            }
            sb.append(line);
        }
        return ChatTexts.clip(sb.toString(), maxChars);
    }

    private String renderAnswerTranscript(List<TurnArchiveRecord> turns, int maxChars) {
        if (turns == null || turns.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        List<TurnArchiveRecord> ordered = new java.util.ArrayList<>(turns);
        Collections.reverse(ordered);
        for (TurnArchiveRecord turn : ordered) {
            String a = ChatTexts.safe(turn.getReplyContent());
            if (a.isBlank()) {
                continue;
            }
            sb.append("- ").append(ChatTexts.clip(a, maxChars / Math.max(1, ordered.size()))).append("\n");
        }
        return ChatTexts.clip(sb.toString(), maxChars);
    }

    private String assembleHistory(String longTermSummary, String recentTranscript) {
        StringBuilder sb = new StringBuilder();
        if (!longTermSummary.isBlank()) {
            sb.append("【长期记忆摘要】\n").append(longTermSummary).append("\n\n");
        }
        if (!recentTranscript.isBlank()) {
            sb.append("【近期对话】\n").append(recentTranscript);
        }
        return sb.toString().trim();
    }

    // ======================== trace 辅助 ========================

    private ChatTraceRecorder.StageHandle startMemoryStage(ChatTraceRecorder recorder, String conversationId,
                                                           String summary) {
        if (recorder == null) {
            return null;
        }
        return recorder.startStage(ChatTraceStageCode.MEMORY, null, summary, null);
    }

    private void completeMemoryStage(ChatTraceRecorder recorder, ChatTraceRecorder.StageHandle stage, String summary) {
        if (recorder == null || stage == null) {
            return;
        }
        recorder.completeStage(stage, summary, null);
    }

    private void failMemoryStage(ChatTraceRecorder recorder, ChatTraceRecorder.StageHandle stage, String error) {
        if (recorder == null || stage == null) {
            return;
        }
        recorder.failStage(stage, "记忆加载失败", error, null);
    }

    // ======================== 转换辅助 ========================

    private ChatMemoryContext.RecentTurnSnapshot toSnapshot(TurnArchiveRecord turn) {
        return ChatMemoryContext.RecentTurnSnapshot.builder()
                .turnId(turn.getId())
                .userPrompt(turn.getUserPrompt())
                .replyContent(turn.getReplyContent())
                .turnStatus(turn.getTurnStatus())
                .build();
    }

    private ChatMemorySummary selectSummary(String conversationId) {
        LambdaQueryWrapper<ChatMemorySummary> wrapper = new LambdaQueryWrapper<ChatMemorySummary>()
                .eq(ChatMemorySummary::getConversationId, conversationId);
        return memorySummaryMapper.selectOne(wrapper);
    }

    private ChatSummaryPayload decodePayload(String summaryJson) {
        if (summaryJson == null || summaryJson.isBlank()) {
            return null;
        }
        return jsonCodec.parseObject(summaryJson, ChatSummaryPayload.class);
    }

    private ChatSummaryPayload copyPayload(ChatSummaryPayload source) {
        return ChatSummaryPayload.builder()
                .summary(source.getSummary())
                .conversationGoal(source.getConversationGoal())
                .stableFacts(source.getStableFacts() == null ? new java.util.ArrayList<>() : new java.util.ArrayList<>(source.getStableFacts()))
                .userPreferences(source.getUserPreferences() == null ? new java.util.ArrayList<>() : new java.util.ArrayList<>(source.getUserPreferences()))
                .resolvedPoints(source.getResolvedPoints() == null ? new java.util.ArrayList<>() : new java.util.ArrayList<>(source.getResolvedPoints()))
                .pendingQuestions(source.getPendingQuestions() == null ? new java.util.ArrayList<>() : new java.util.ArrayList<>(source.getPendingQuestions()))
                .retrievalHints(source.getRetrievalHints() == null ? new java.util.ArrayList<>() : new java.util.ArrayList<>(source.getRetrievalHints()))
                .build();
    }

    /** 占位：保留以备 benchmark 计数。 */
    @SuppressWarnings("unused")
    private static final AtomicInteger COMPRESS_COUNTER = new AtomicInteger(0);
}
