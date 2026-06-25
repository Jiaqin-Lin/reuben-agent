package com.reubenagent.chat.service.impl;

import com.reubenagent.chat.ChatTestConfig;
import com.reubenagent.chat.ChatTestSchema;
import com.reubenagent.chat.config.ChatProperties;
import com.reubenagent.chat.controller.ChatController;
import com.reubenagent.chat.entity.ChatMemorySummary;
import com.reubenagent.chat.enums.ChatTurnStatus;
import com.reubenagent.chat.mapper.IChatMemorySummaryMapper;
import com.reubenagent.chat.model.memory.ChatMemoryContext;
import com.reubenagent.chat.model.memory.ChatSummaryPayload;
import com.reubenagent.chat.orchestrate.ChatLeaseService;
import com.reubenagent.chat.orchestrate.ChatStreamOrchestrator;
import com.reubenagent.chat.service.IChatMemoryService;
import com.reubenagent.chat.service.ObservedChatModelService;
import com.reubenagent.chat.session.ChatArchiveStore;
import com.reubenagent.chat.session.ConversationArchiveRecord;
import com.reubenagent.chat.session.TurnArchiveRecord;
import com.reubenagent.chat.support.ChatJsonCodec;
import com.reubenagent.chat.support.ChatPromptTemplateService;
import com.reubenagent.framework.uid.UidGenerator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * {@link ChatMemoryServiceImpl} MySQL 集成测试。
 *
 * <h3>前置</h3>
 * <pre>
 * docker compose up -d mysql
 * </pre>
 *
 * <h3>中间件</h3>
 * <ul>
 *   <li>MySQL 8.0 — Docker（端口 3307）</li>
 *   <li>UidGenerator — Mock</li>
 *   <li>ObservedChatModelService — Mock（伪造 LLM 摘要输出）</li>
 *   <li>ChatPromptTemplateService — Mock（直接回显变量，不读 classpath）</li>
 * </ul>
 */
@SpringBootTest(classes = ChatTestConfig.TestApp.class)
@ActiveProfiles("test")
@Import(ChatTestConfig.TestMetaConfig.class)
class ChatMemoryServiceImplTest {

    @MockBean
    private UidGenerator uidGenerator;

    @MockBean
    private ObservedChatModelService observedChatModelService;

    @MockBean
    private ChatPromptTemplateService promptTemplateService;

    // 阶段：Redis/编排相关 Bean 在记忆测试中不需要，Mock 掉避免缺 StringRedisTemplate 启动失败
    @MockBean
    private ChatLeaseService chatLeaseService;

    @MockBean
    private ChatStreamOrchestrator chatStreamOrchestrator;

    @MockBean
    private ChatController chatController;

    @Autowired
    private IChatMemoryService memoryService;

    @Autowired
    private ChatArchiveStore archiveStore;

    @Autowired
    private IChatMemorySummaryMapper memorySummaryMapper;

    @Autowired
    private ChatProperties properties;

    @Autowired
    private ChatJsonCodec jsonCodec;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final AtomicLong uidSeq = new AtomicLong(1000);

    @BeforeEach
    void setUp() {
        ChatTestSchema.dropTables(jdbcTemplate);
        ChatTestSchema.createAllTables(jdbcTemplate);

        uidSeq.set(1000);
        when(uidGenerator.getUid()).thenAnswer(inv -> uidSeq.getAndAdd(100));

        // 阶段：prompt 渲染回显变量占位，便于断言
        when(promptTemplateService.render(anyString(), any()))
                .thenAnswer(inv -> {
                    String name = inv.getArgument(0);
                    return "[prompt:" + name + "]";
                });
    }

    @Test
    @DisplayName("空会话加载记忆 → recentTranscript 为空，无摘要，不触发压缩")
    void shouldReturnEmptyContextForNewConversation() {
        String conversationId = "conv-empty";
        ensureConversation(conversationId);

        ChatMemoryContext context = memoryService.loadMemoryContext(conversationId, null);

        assertThat(context.getRecentTranscript()).isEmpty();
        assertThat(context.getLongTermSummary()).isEmpty();
        assertThat(context.getSummaryPayload()).isNull();
        assertThat(context.getRecentTurns()).isEmpty();
        assertThat(context.isSummaryRefreshTriggered()).isFalse();
    }

    @Test
    @DisplayName("近 2 轮对话 → recent window 渲染 transcript，未超 keepRecent 不触发压缩")
    void shouldRenderRecentWindowWithoutCompression() {
        String conversationId = "conv-recent";
        ensureConversation(conversationId);
        completeTurn(conversationId, "什么是 RAG？", "RAG 是检索增强生成。");
        completeTurn(conversationId, "和微调有什么区别？", "RAG 不改模型权重。");

        ChatMemoryContext context = memoryService.loadMemoryContext(conversationId, null);

        assertThat(context.getRecentTurns()).hasSize(2);
        assertThat(context.getRecentTranscript()).contains("什么是 RAG？");
        assertThat(context.getRecentTranscript()).contains("检索增强生成");
        assertThat(context.getAssembledHistory()).contains("【近期对话】");
        assertThat(context.isSummaryRefreshTriggered()).isFalse();
        assertThat(context.getAnswerRecentTranscript()).contains("检索增强生成");
    }

    @Test
    @DisplayName("稳定轮次超过 keepRecent → 触发异步压缩，摘要落库")
    void shouldTriggerCompressionWhenOverflow() throws Exception {
        String conversationId = "conv-overflow";
        ensureConversation(conversationId);
        // 阶段：keepRecentTurns=4，造 6 轮稳定对话 → 溢出 2 轮
        for (int i = 1; i <= 6; i++) {
            completeTurn(conversationId, "问题" + i, "答案" + i);
        }

        // 阶段：LLM 输出合法 JSON 摘要
        ChatSummaryPayload llmOutput = ChatSummaryPayload.builder()
                .summary("用户在持续追问 RAG 相关问题")
                .conversationGoal("理解 RAG 架构")
                .stableFacts(List.of("RAG = 检索增强生成"))
                .pendingQuestions(List.of("RAG 与微调对比"))
                .retrievalHints(List.of("RAG", "微调"))
                .build();
        when(observedChatModelService.callText(anyString(), anyString(), any()))
                .thenReturn(jsonCodec.toJson(llmOutput));

        ChatMemoryContext context = memoryService.loadMemoryContext(conversationId, null);

        // 阶段：loadMemoryContext 触发异步刷新，等待线程池执行
        assertThat(context.isSummaryRefreshTriggered()).isTrue();
        awaitCompressionPersisted(conversationId);

        ChatMemorySummary entity = selectSummary(conversationId);
        assertThat(entity).isNotNull();
        assertThat(entity.getCoveredTurnCount()).isEqualTo(2);
        assertThat(entity.getCompressionCount()).isEqualTo(1);
        assertThat(entity.getSummaryVersion()).isEqualTo(1);
        assertThat(entity.getSummaryText()).contains("持续追问");

        ChatSummaryPayload stored = memoryService.getConversationSummary(conversationId);
        assertThat(stored).isNotNull();
        assertThat(stored.getConversationGoal()).isEqualTo("理解 RAG 架构");
        assertThat(stored.getStableFacts()).contains("RAG = 检索增强生成");
    }

    @Test
    @DisplayName("LLM 输出无法解析 → 规则 fallback 落库，不抛异常")
    void shouldFallbackWhenLlmOutputInvalid() throws Exception {
        String conversationId = "conv-fallback";
        ensureConversation(conversationId);
        for (int i = 1; i <= 6; i++) {
            completeTurn(conversationId, "问题" + i, "答案" + i);
        }
        when(observedChatModelService.callText(anyString(), anyString(), any()))
                .thenReturn("这不是 JSON，LLM 出错了");

        memoryService.loadMemoryContext(conversationId, null);
        awaitCompressionPersisted(conversationId);

        ChatMemorySummary entity = selectSummary(conversationId);
        assertThat(entity).isNotNull();
        assertThat(entity.getSummaryText()).contains("用户问");
        assertThat(entity.getCompressionCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("rebuildConversationSummary 全量重建 → 覆盖全部稳定轮次")
    void shouldRebuildFullSummary() {
        String conversationId = "conv-rebuild";
        ensureConversation(conversationId);
        for (int i = 1; i <= 3; i++) {
            completeTurn(conversationId, "重建问题" + i, "重建答案" + i);
        }
        ChatSummaryPayload llmOutput = ChatSummaryPayload.builder()
                .summary("全量重建摘要")
                .conversationGoal("重建目标")
                .build();
        when(observedChatModelService.callText(anyString(), anyString(), any()))
                .thenReturn(jsonCodec.toJson(llmOutput));

        ChatSummaryPayload result = memoryService.rebuildConversationSummary(conversationId);

        assertThat(result).isNotNull();
        assertThat(result.getSummary()).isEqualTo("全量重建摘要");
        ChatMemorySummary entity = selectSummary(conversationId);
        assertThat(entity.getCoveredTurnCount()).isEqualTo(3);
    }

    @Test
    @DisplayName("deleteConversationSummary → 摘要行被删除")
    void shouldDeleteSummary() throws Exception {
        String conversationId = "conv-delete";
        ensureConversation(conversationId);
        for (int i = 1; i <= 6; i++) {
            completeTurn(conversationId, "问题" + i, "答案" + i);
        }
        when(observedChatModelService.callText(anyString(), anyString(), any()))
                .thenReturn(jsonCodec.toJson(ChatSummaryPayload.builder().summary("s").build()));
        memoryService.loadMemoryContext(conversationId, null);
        awaitCompressionPersisted(conversationId);
        assertThat(selectSummary(conversationId)).isNotNull();

        memoryService.deleteConversationSummary(conversationId);

        assertThat(selectSummary(conversationId)).isNull();
    }

    @Test
    @DisplayName("已有摘要时 loadMemoryContext 注入长期摘要到 assembledHistory")
    void shouldInjectLongTermSummaryIntoHistory() throws Exception {
        String conversationId = "conv-inject";
        ensureConversation(conversationId);
        for (int i = 1; i <= 6; i++) {
            completeTurn(conversationId, "问题" + i, "答案" + i);
        }
        when(observedChatModelService.callText(anyString(), anyString(), any()))
                .thenReturn(jsonCodec.toJson(ChatSummaryPayload.builder()
                        .summary("这是长期摘要内容").build()));
        memoryService.loadMemoryContext(conversationId, null);
        awaitCompressionPersisted(conversationId);

        // 阶段：再加一轮，再次加载应带上长期摘要
        completeTurn(conversationId, "新问题", "新答案");
        ChatMemoryContext context = memoryService.loadMemoryContext(conversationId, null);

        assertThat(context.getLongTermSummary()).isEqualTo("这是长期摘要内容");
        assertThat(context.getAssembledHistory()).contains("【长期记忆摘要】");
        assertThat(context.getAssembledHistory()).contains("这是长期摘要内容");
    }

    // ======================== 辅助 ========================

    private void ensureConversation(String conversationId) {
        archiveStore.saveConversation(ConversationArchiveRecord.builder()
                .id(uidGenerator.getUid())
                .conversationId(conversationId)
                .sessionStatus(1)
                .chatMode(2)
                .title("test")
                .build());
    }

    private void completeTurn(String conversationId, String question, String answer) {
        Long turnId = uidGenerator.getUid();
        archiveStore.startTurn(TurnArchiveRecord.builder()
                .id(turnId)
                .conversationId(conversationId)
                .userPrompt(question)
                .turnStatus(ChatTurnStatus.RUNNING.getCode())
                .build());
        archiveStore.completeTurn(conversationId, turnId, TurnArchiveRecord.builder()
                .replyContent(answer)
                .turnStatus(ChatTurnStatus.COMPLETED.getCode())
                .build());
    }

    private ChatMemorySummary selectSummary(String conversationId) {
        return memorySummaryMapper.selectOne(new com.baomidou.mybatisplus.core.conditions.query
                .LambdaQueryWrapper<ChatMemorySummary>()
                .eq(ChatMemorySummary::getConversationId, conversationId));
    }

    /** 等待异步压缩落库（最多 5 秒）。 */
    private void awaitCompressionPersisted(String conversationId) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline) {
            if (selectSummary(conversationId) != null) {
                return;
            }
            Thread.sleep(100);
        }
    }
}
