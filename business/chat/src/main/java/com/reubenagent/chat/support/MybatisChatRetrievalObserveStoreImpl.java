package com.reubenagent.chat.support;

import com.reubenagent.chat.entity.ChatChannelExecution;
import com.reubenagent.chat.mapper.IChatChannelExecutionMapper;
import com.reubenagent.chat.mapper.IChatRetrievalResultMapper;
import com.reubenagent.chat.model.orchestrate.ChatRetrievalResult;
import com.reubenagent.rag.model.RetrievalResult;
import com.reubenagent.framework.uid.UidGenerator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.Date;
import java.util.List;

/**
 * Phase 8 落 {@code reuben_agent_chat_retrieval_result} + {@code reuben_agent_chat_channel_execution}
 * 的实现。
 *
 * <p>{@code @Primary} 顶替 {@link NoopChatRetrievalObserveStore}（后者 {@code @ConditionalOnMissingBean}
 * 自动停用）。落库失败 warn 不中断主流程（adapter 侧已 try/catch，本类内部也降级）。</p>
 *
 * <p><b>待测</b>：批量 insert 性能（MyBatis-Plus 默认单条 insert，大文档场景需优化为 batch insert）。</p>
 *
 * @author reuben
 * @since 2026-06-25
 */
@Slf4j
@Primary
@Repository
public class MybatisChatRetrievalObserveStoreImpl implements MybatisChatRetrievalObserveStore,
        IChatRetrievalObserveStoreInternal {

    private final IChatRetrievalResultMapper retrievalResultMapper;
    private final IChatChannelExecutionMapper channelExecutionMapper;
    private final UidGenerator uidGenerator;

    public MybatisChatRetrievalObserveStoreImpl(IChatRetrievalResultMapper retrievalResultMapper,
                                                IChatChannelExecutionMapper channelExecutionMapper,
                                                UidGenerator uidGenerator) {
        this.retrievalResultMapper = retrievalResultMapper;
        this.channelExecutionMapper = channelExecutionMapper;
        this.uidGenerator = uidGenerator;
    }

    @Override
    public void recordRetrievalResults(String conversationId, Long turnId, String traceId,
                                       List<ChatRetrievalResult> aggregated) {
        if (aggregated == null || aggregated.isEmpty()) {
            return;
        }
        for (ChatRetrievalResult group : aggregated) {
            if (group == null || group.getResults() == null || group.getResults().isEmpty()) {
                continue;
            }
            int subIdx = group.getSubQuestionIndex() == null ? 0 : group.getSubQuestionIndex();
            int rank = 0;
            for (RetrievalResult r : group.getResults()) {
                if (r == null) {
                    continue;
                }
                rank++;
                try {
                    retrievalResultMapper.insert(buildEntity(conversationId, turnId, traceId, subIdx, r, rank));
                } catch (Exception e) {
                    log.warn("检索结果落库失败（单条跳过）→ conversationId={} chunkId={} err={}",
                            conversationId, r.getChunkId(), e.getMessage());
                }
            }
        }
    }

    @Override
    public void recordChannelExecution(String conversationId, Long turnId, String traceId,
                                       Integer subQuestionIndex, String channelType,
                                       long durationMs, int recalledCount, int acceptedCount,
                                       BigDecimal maxScore, BigDecimal minScore) {
        try {
            ChatChannelExecution entity = ChatChannelExecution.builder()
                    .id(uidGenerator.getUid())
                    .conversationId(conversationId)
                    .turnId(turnId)
                    .traceId(traceId)
                    .subQuestionIndex(subQuestionIndex)
                    .channelType(channelType)
                    .executionState("COMPLETED")
                    .startTime(new Date(System.currentTimeMillis() - durationMs))
                    .endTime(new Date())
                    .durationMs(durationMs)
                    .recalledCount(recalledCount)
                    .acceptedCount(acceptedCount)
                    .finalSelectedCount(acceptedCount)
                    .maxScore(maxScore)
                    .minScore(minScore)
                    .build();
            channelExecutionMapper.insert(entity);
        } catch (Exception e) {
            log.warn("通道执行落库失败（不中断）→ conversationId={} channel={} err={}",
                    conversationId, channelType, e.getMessage());
        }
    }

    private com.reubenagent.chat.entity.ChatRetrievalResult buildEntity(String conversationId, Long turnId,
                                                                        String traceId, int subIdx,
                                                                        RetrievalResult r, int rank) {
        BigDecimal finalScore = r.getScore() == null ? null : BigDecimal.valueOf(r.getScore());
        BigDecimal rerankScore = r.getRerankScore() == null ? null : BigDecimal.valueOf(r.getRerankScore());
        String preview = r.getChunkText();
        if (preview != null && preview.length() > 200) {
            preview = preview.substring(0, 200);
        }
        return com.reubenagent.chat.entity.ChatRetrievalResult.builder()
                .id(uidGenerator.getUid())
                .conversationId(conversationId)
                .turnId(turnId)
                .traceId(traceId)
                .subQuestionIndex(subIdx)
                .channelType(r.getSource() == null ? "hybrid" : r.getSource())
                .vectorRank(rank)
                .rerankScore(rerankScore)
                .finalScore(finalScore)
                .gatePassed(1)
                .isSelected(1)
                .documentId(r.getDocumentId())
                .chunkId(r.getChunkId())
                .parentBlockId(r.getParentBlockId())
                .sectionPath(r.getSectionPath())
                .chunkTextPreview(preview)
                .build();
    }
}
