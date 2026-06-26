package com.reubenagent.chat.trace;

import com.reubenagent.chat.entity.ChatTraceStage;
import com.reubenagent.chat.enums.ChatErrorCode;
import com.reubenagent.chat.enums.ChatTraceStageCode;
import com.reubenagent.chat.enums.ChatTraceStageState;
import com.reubenagent.chat.enums.ExecutionMode;
import com.reubenagent.chat.exception.ChatException;
import com.reubenagent.chat.mapper.IChatTraceStageMapper;
import com.reubenagent.chat.support.ChatJsonCodec;
import com.reubenagent.framework.uid.UidGenerator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Repository;

import java.util.Date;

/**
 * Phase 8 落 {@code reuben_agent_chat_trace_stage} 的实现。
 *
 * <p>{@code @Primary} 顶替 {@link NoopChatTraceStageStore}（后者 {@code @ConditionalOnMissingBean}
 * 自动停用）。落库失败 <b>warn + 降级返回 {@code -1L}</b>，不抛、不中断主流程（与 recorder 降级一致）。</p>
 *
 * @author reuben
 * @since 2026-06-25
 */
@Slf4j
@Primary
@Repository
public class MybatisChatTraceStageStoreImpl implements MybatisChatTraceStageStore {

    private final IChatTraceStageMapper mapper;
    private final ChatJsonCodec jsonCodec;
    private final UidGenerator uidGenerator;

    public MybatisChatTraceStageStoreImpl(IChatTraceStageMapper mapper,
                                          ChatJsonCodec jsonCodec,
                                          UidGenerator uidGenerator) {
        this.mapper = mapper;
        this.jsonCodec = jsonCodec;
        this.uidGenerator = uidGenerator;
    }

    @Override
    public long startStage(String conversationId, Long turnId, String traceId,
                           ChatTraceStageCode stageCode, int stageLevel, Long parentStageId,
                           String executionMode, String summaryText, Object snapshot) {
        if (stageCode == null) {
            return -1L;
        }
        try {
            ChatTraceStage entity = ChatTraceStage.builder()
                    .id(uidGenerator.getUid())
                    .conversationId(conversationId)
                    .turnId(turnId)
                    .traceId(traceId)
                    .stageCode(stageCode.getCode())
                    .stageName(stageCode.getMsg())
                    .stageOrder(stageCode.getCode())
                    .stageLevel(stageLevel)
                    .parentStageId(parentStageId)
                    .executionMode(parseExecutionModeCode(executionMode))
                    .stageState(ChatTraceStageState.RUNNING.getCode())
                    .startTime(new Date())
                    .summaryText(summaryText)
                    .snapshotJson(jsonCodec.toJson(snapshot))
                    .build();
            mapper.insert(entity);
            return entity.getId();
        } catch (Exception e) {
            log.warn("追踪 stage 起始落库失败，降级 no-op → stage={} err={}",
                    stageCode, e.getMessage());
            return -1L;
        }
    }

    @Override
    public void finishStage(long stageId, ChatTraceStageState state, String summaryText,
                            String errorMessage, Object snapshot, long durationMs) {
        if (stageId < 0 || state == null) {
            return;
        }
        try {
            ChatTraceStage update = ChatTraceStage.builder()
                    .id(stageId)
                    .stageState(state.getCode())
                    .endTime(new Date())
                    .durationMs(durationMs)
                    .summaryText(summaryText)
                    .errorMessage(errorMessage)
                    .snapshotJson(jsonCodec.toJson(snapshot))
                    .build();
            mapper.updateById(update);
        } catch (Exception e) {
            log.warn("追踪 stage 收尾落库失败 → stageId={} state={} err={}",
                    stageId, state, e.getMessage());
        }
    }

    private Integer parseExecutionModeCode(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        try {
            return ExecutionMode.valueOf(name).getCode();
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
