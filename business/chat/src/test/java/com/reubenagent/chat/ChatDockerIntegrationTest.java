package com.reubenagent.chat;

import com.alibaba.cloud.ai.graph.KeyStrategy;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.streaming.OutputType;
import com.alibaba.cloud.ai.graph.streaming.StreamingOutput;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.reubenagent.chat.config.ChatProperties;
import com.reubenagent.chat.dto.ChatRenameDto;
import com.reubenagent.chat.dto.ChatSessionCreateDto;
import com.reubenagent.chat.dto.ChatSessionListDto;
import com.reubenagent.chat.dto.ChatStreamDto;
import com.reubenagent.chat.entity.ChatRetrievalResult;
import com.reubenagent.chat.entity.ChatTraceStage;
import com.reubenagent.chat.entity.ChatTurn;
import com.reubenagent.chat.enums.ChatMode;
import com.reubenagent.chat.enums.ChatTurnStatus;
import com.reubenagent.chat.enums.ExecutionMode;
import com.reubenagent.chat.mapper.IChatChannelExecutionMapper;
import com.reubenagent.chat.mapper.IChatRetrievalResultMapper;
import com.reubenagent.chat.mapper.IChatTraceStageMapper;
import com.reubenagent.chat.mapper.IChatTurnMapper;
import com.reubenagent.chat.model.SearchReference;
import com.reubenagent.chat.orchestrate.ChatStreamOrchestrator;
import com.reubenagent.chat.service.ChatDocumentOptionService;
import com.reubenagent.chat.service.IChatSessionService;
import com.reubenagent.chat.service.ObservedChatModelService;
import com.reubenagent.chat.session.ChatArchiveStore;
import com.reubenagent.chat.support.ChatPromptTemplateService;
import com.reubenagent.chat.vo.ChatResetVo;
import com.reubenagent.chat.vo.ChatTurnDetailView;
import com.reubenagent.chat.vo.ChannelExecutionView;
import com.reubenagent.chat.vo.ConversationSessionListVo;
import com.reubenagent.chat.vo.ConversationView;
import com.reubenagent.chat.vo.RetrievalResultView;
import com.reubenagent.chat.vo.StageBenchmarkView;
import com.reubenagent.common.dto.PageVo;
import com.reubenagent.document.service.IDocumentOptionQueryService;
import com.reubenagent.document.service.IDocumentStructureNodeService;
import com.reubenagent.framework.uid.UidGenerator;
import com.reubenagent.rag.dto.RagRetrieveRequest;
import com.reubenagent.rag.model.RetrievalResult;
import com.reubenagent.rag.service.IRagRetrievalService;
import com.reubenagent.rag.vo.RagRetrieveResponse;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;

/**
 * Chat 模块 Docker 集成测试 —— 验证流式问答 + 会话控制 + 观测查询在真实 MySQL + Redis 上端到端正确。
 *
 * <h3>前置</h3>
 * <pre>
 *   docker compose up -d mysql redis
 * </pre>
 *
 * <h3>策略</h3>
 * <p>真实 DeepSeek / Tavily / RAG 索引的联网端到端已在 Phase 5/6/7/8 手工实测，本测试不重复烧 key。
 * 通过 {@code @MockBean} 替身外部依赖（模型 / Tavily / RAG 检索 / 结构节点 / 文档选项），
 * 保留真实 MySQL + Redis + 全链路 trace/retrieval/channel 落库，验证 chat 自身编排与持久化链路。</p>
 *
 * <h3>运行</h3>
 * <pre>
 *   mvn test -pl business/chat -am \
 *       -Dtest=ChatDockerIntegrationTest \
 *       -Dspring.profiles.active=docker \
 *       -Dsurefire.failIfNoSpecifiedTests=false
 * </pre>
 *
 * @author reuben
 * @since 2026-06-27
 */
@Slf4j
@SpringBootTest(classes = ChatTestConfig.TestApp.class, webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Import(ChatTestConfig.TestMetaConfig.class)
@ActiveProfiles("docker")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ChatDockerIntegrationTest {

    @MockBean
    private UidGenerator uidGenerator;

    @MockBean
    private ObservedChatModelService observedChatModelService;

    @MockBean
    private ChatPromptTemplateService promptTemplateService;

    @MockBean
    private IRagRetrievalService ragRetrievalService;

    @MockBean
    private ReactAgent reactAgent;

    @MockBean
    private IDocumentStructureNodeService structureNodeService;

    @MockBean
    private IDocumentOptionQueryService documentOptionQueryService;

    @Autowired
    private ChatStreamOrchestrator streamOrchestrator;

    @Autowired
    private com.reubenagent.chat.orchestrate.ChatRuntimeRegistry runtimeRegistry;

    @Autowired
    private IChatSessionService sessionService;

    @Autowired
    private ChatArchiveStore archiveStore;

    @Autowired
    private IChatTurnMapper turnMapper;

    @Autowired
    private IChatTraceStageMapper traceStageMapper;

    @Autowired
    private IChatRetrievalResultMapper retrievalResultMapper;

    @Autowired
    private IChatChannelExecutionMapper channelExecutionMapper;

    @Autowired
    private ChatProperties properties;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private org.springframework.data.redis.core.StringRedisTemplate redisTemplate;

    private final AtomicLong uidSeq = new AtomicLong(2000);
    private final ObjectMapper objectMapper = new ObjectMapper();

    // 跨用例共享状态
    private String openChatConversationId;
    private Long openChatTurnId;
    private String documentConversationId;
    private Long documentTurnId;

    @BeforeAll
    void setUp() throws Exception {
        ChatTestSchema.dropTables(jdbcTemplate);
        ChatTestSchema.createAllTables(jdbcTemplate);
        // 阶段：清掉可能残留的 Redis 租约 key（上轮测试若未干净释放会卡 SESSION_RUNNING）
        try {
            java.util.Set<java.lang.String> keys = redisTemplate.keys("chat:lease:*");
            if (keys != null && !keys.isEmpty()) {
                redisTemplate.delete(keys);
            }
        } catch (Exception e) {
            log.warn("清理 Redis 租约 key 失败 → err={}", e.getMessage());
        }

        uidSeq.set(2000);
        stubSharedMocks();
    }

    @org.junit.jupiter.api.BeforeEach
    void resetMocks() {
        // 阶段：Spring @MockBean 默认在每个测试方法后 reset，需每方法重新 stub 共享 mock
        stubSharedMocks();
    }

    /** 公共 mock stub —— @MockBean 默认每方法 reset，需在 @BeforeEach 重新打桩。 */
    private void stubSharedMocks() {
        try {
            stubSharedMocksWithCheckedException();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @SuppressWarnings("unchecked")
    private void stubSharedMocksWithCheckedException() throws Exception {
        when(uidGenerator.getUid()).thenAnswer(inv -> uidSeq.getAndAdd(100));

        // 阶段：prompt 渲染回显占位，便于断言
        when(promptTemplateService.render(anyString(), any()))
                .thenAnswer(inv -> "[prompt:" + inv.getArgument(0) + "]");

        // 阶段：模型流式输出固定文本（覆盖 RAG 回答生成 + 推荐追问前置）
        when(observedChatModelService.streamText(anyString(), anyString(), any(ChatOptions.class), any()))
                .thenReturn(Flux.just("这是", "测试", "回答。"));
        when(observedChatModelService.streamText(anyString(), anyString(), any(ChatOptions.class)))
                .thenReturn(Flux.just("这是", "测试", "回答。"));

        // 阶段：阻塞模型调用 —— 按调用场景返回不同内容：
        //   - RECOMMENDATION → JSON 数组（推荐追问）
        //   - rewrite → JSON 对象 {"rewrite":..., "sub_questions":[...]}，保证 DOCUMENT 改写不回退规则
        //   - 其余（摘要合并等）→ 简单文本
        when(observedChatModelService.callText(eq("RECOMMENDATION"), anyString(), isNull(), any()))
                .thenReturn("[\"追问一\",\"追问二\"]");
        when(observedChatModelService.callText(eq("RECOMMENDATION"), anyString(), isNull()))
                .thenReturn("[\"追问一\",\"追问二\"]");
        when(observedChatModelService.callText(eq("RECOMMENDATION"), anyString(), any(), any()))
                .thenReturn("[\"追问一\",\"追问二\"]");
        when(observedChatModelService.callText(eq("RECOMMENDATION"), anyString(), any()))
                .thenReturn("[\"追问一\",\"追问二\"]");
        when(observedChatModelService.callText(eq("rewrite"), anyString(), any(), any()))
                .thenReturn("{\"rewrite\":\"RAG 是什么\",\"should_split\":false,\"sub_questions\":[\"RAG 是什么\"]}");
        when(observedChatModelService.callText(eq("rewrite"), anyString(), any()))
                .thenReturn("{\"rewrite\":\"RAG 是什么\",\"should_split\":false,\"sub_questions\":[\"RAG 是什么\"]}");
        // 兜底：其余 callText（摘要合并等）返回简单文本
        when(observedChatModelService.callText(anyString(), anyString(), any(ChatOptions.class), any()))
                .thenReturn("{\"summary\":\"测试摘要\"}");
        when(observedChatModelService.callText(anyString(), anyString(), any(ChatOptions.class)))
                .thenReturn("{\"summary\":\"测试摘要\"}");

        // 阶段：RAG 检索返回 2 条证据（DOCUMENT 模式）
        when(ragRetrievalService.retrieve(any(RagRetrieveRequest.class)))
                .thenReturn(RagRetrieveResponse.builder()
                        .results(List.of(
                                RetrievalResult.builder()
                                        .chunkId(1001L)
                                        .chunkText("RAG 是检索增强生成的缩写。")
                                        .score(0.85)
                                        .sectionPath("第一章 > 1.1 概述")
                                        .documentId(5001L)
                                        .parentBlockId(2001L)
                                        .source("hybrid")
                                        .rerankScore(0.85)
                                        .build(),
                                RetrievalResult.builder()
                                        .chunkId(1002L)
                                        .chunkText("RAG 结合检索与生成。")
                                        .score(0.78)
                                        .sectionPath("第一章 > 1.2 原理")
                                        .documentId(5001L)
                                        .parentBlockId(2002L)
                                        .source("hybrid")
                                        .rerankScore(0.78)
                                        .build()))
                        .totalCostMs(42L)
                        .rewrittenQuery("RAG 是什么")
                        .build());

        when(reactAgent.stream(anyString(), any(RunnableConfig.class)))
                .thenReturn(streamingOutput());

        // 阶段：文档选项查询返回空（ChatDocumentOptionService 委托，避免缺 Bean）
        when(documentOptionQueryService.listOptions()).thenReturn(List.of());
    }

    @Test
    @Order(0)
    @DisplayName("环境就绪：表已建 + 配置已注入")
    void shouldPrepareEnvironment() {
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM reuben_agent_chat_conversation", Long.class)).isZero();
        assertThat(properties.getMemory().getKeepRecentTurns()).isEqualTo(4);
        assertThat(properties.getLease().getTtlSeconds()).isEqualTo(60);
    }

    @Test
    @Order(1)
    @DisplayName("OPEN_CHAT 流式 → ReAct 回答落库 + trace 全链路 + 会话回 IDLE + 推荐追问")
    void shouldStreamOpenChatAndPersist() {
        // 阶段：清掉可能残留的租约 key，避免上轮测试未释放导致 SESSION_RUNNING
        try {
            jdbcTemplate.update("DELETE FROM reuben_agent_chat_turn WHERE execution_mode = ?",
                    ExecutionMode.REACT_AGENT.getCode());
            jdbcTemplate.update("DELETE FROM reuben_agent_chat_conversation WHERE chat_mode = ?",
                    ChatMode.OPEN_CHAT.getCode());
        } catch (Exception ignored) {
            // 首次运行表空，忽略
        }

        ChatStreamDto dto = ChatStreamDto.builder()
                .conversationId("it-open-chat")
                .question("你好，自我介绍一下")
                .chatMode(ChatMode.OPEN_CHAT.getCode())
                .build();

        List<String> events = streamOrchestrator.openStream(dto)
                .collectList()
                .block(java.time.Duration.ofSeconds(30));

        assertThat(events).isNotNull().isNotEmpty();
        assertThat(events.stream().anyMatch(e -> e.contains("\"type\":\"text\""))).isTrue();
        assertThat(events.stream().anyMatch(e -> e.contains("\"type\":\"done\""))).isTrue();

        // 阶段：sink complete 先于 completeTurn 落库，需等收尾提交后再查
        awaitFinalization("it-open-chat");

        // 阶段：找本次 turn（OPEN_CHAT 走 REACT_AGENT=4）
        ChatTurn turn = selectLatestTurnByExecutionMode(ExecutionMode.REACT_AGENT.getCode());
        assertThat(turn).isNotNull();
        assertThat(turn.getTurnStatus()).isEqualTo(ChatTurnStatus.COMPLETED.getCode());
        assertThat(turn.getReplyContent()).contains("测试助手");
        openChatConversationId = turn.getConversationId();
        openChatTurnId = turn.getId();

        // 阶段：trace stage 落库（MEMORY/INTENT/ROUTE/REACT_AGENT/FINALIZE 等）
        List<ChatTraceStage> stages = traceStageMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<ChatTraceStage>()
                        .eq(ChatTraceStage::getTurnId, openChatTurnId));
        assertThat(stages).isNotEmpty();

        // 阶段：会话回 IDLE
        Integer status = jdbcTemplate.queryForObject(
                "SELECT session_status FROM reuben_agent_chat_conversation WHERE conversation_id = ?",
                Integer.class, openChatConversationId);
        assertThat(status).isEqualTo(1);

        // 阶段：推荐追问（callText mock 返回 JSON 数组）→ turn.followup_suggestion_list 非空
        assertThat(turn.getFollowupSuggestionList())
                .as("推荐追问应落库，actual=" + turn.getFollowupSuggestionList())
                .isNotBlank();
    }

    @Test
    @Order(2)
    @DisplayName("DOCUMENT 流式 → RAG 检索 + 引用落库 + retrieval_result/channel_execution 观测")
    void shouldStreamDocumentRagAndObserve() {
        // 阶段：清掉可能残留的租约 key，避免 SESSION_RUNNING
        try {
            jdbcTemplate.update("DELETE FROM reuben_agent_chat_turn WHERE execution_mode = ?",
                    ExecutionMode.RETRIEVAL.getCode());
            jdbcTemplate.update("DELETE FROM reuben_agent_chat_conversation WHERE chat_mode = ?",
                    ChatMode.DOCUMENT.getCode());
        } catch (Exception ignored) {
            // 表空忽略
        }

        ChatStreamDto dto = ChatStreamDto.builder()
                .conversationId("it-document")
                .question("RAG 是什么")
                .chatMode(ChatMode.DOCUMENT.getCode())
                .selectedDocumentId(5001L)
                .selectedDocumentName("RAG 白皮书")
                .build();

        List<String> events = streamOrchestrator.openStream(dto)
                .collectList()
                .block(java.time.Duration.ofSeconds(30));

        assertThat(events).isNotNull();
        assertThat(events.stream().anyMatch(e -> e.contains("\"type\":\"reference\""))).isTrue();

        // 阶段：sink complete 先于 completeTurn 落库，需等收尾提交后再查
        awaitFinalization("it-document");

        ChatTurn turn = selectLatestTurnByExecutionMode(ExecutionMode.RETRIEVAL.getCode());
        assertThat(turn).isNotNull();
        assertThat(turn.getReplyContent()).contains("测试");
        assertThat(turn.getSourceSnapshotList()).isNotBlank();
        documentConversationId = turn.getConversationId();
        documentTurnId = turn.getId();

        // 阶段：retrieval_result 落库 2 条
        Long retrievalCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM reuben_agent_chat_retrieval_result WHERE turn_id = ?",
                Long.class, documentTurnId);
        assertThat(retrievalCount).isEqualTo(2L);

        // 阶段：channel_execution 落库（hybrid-only）
        Long channelCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM reuben_agent_chat_channel_execution WHERE turn_id = ?",
                Long.class, documentTurnId);
        assertThat(channelCount).isGreaterThanOrEqualTo(1L);
    }

    @Test
    @Order(3)
    @DisplayName("推荐追问 → turn.followup_suggestion_list 非空")
    void shouldPersistRecommendations() {
        assertThat(openChatTurnId).as("依赖 Order(1) 的 OPEN_CHAT turn").isNotNull();
        ChatTurn turn = turnMapper.selectById(openChatTurnId);
        assertThat(turn.getFollowupSuggestionList()).isNotBlank();
        assertThat(turn.getFollowupSuggestionList()).contains("追问");
    }

    @Test
    @Order(4)
    @DisplayName("会话控制：create → list → rename → reset → delete")
    void shouldManageSessionLifecycle() {
        // create
        ConversationView created = sessionService.createConversation(ChatSessionCreateDto.builder()
                .chatMode(ChatMode.OPEN_CHAT.getCode())
                .title("集成测试会话")
                .build());
        String conversationId = created.getConversationId();
        assertThat(created.getTitle()).isEqualTo("集成测试会话");

        // list
        ChatSessionListDto listDto = ChatSessionListDto.builder()
                .pageNo(1).pageSize(10).keyword("集成测试").build();
        PageVo<ConversationSessionListVo> page = sessionService.listConversations(listDto);
        assertThat(page.getRecords()).extracting(ConversationSessionListVo::getConversationId)
                .contains(conversationId);

        // rename（title 空 → 规则兜底，generateTitle 取首问）
        String renamed = sessionService.renameConversation(ChatRenameDto.builder()
                .conversationId(conversationId).title("").build());
        assertThat(renamed).isNotBlank();

        // reset
        ChatResetVo reset = sessionService.resetConversation(conversationId);
        assertThat(reset.getRemovedTurnCount()).isGreaterThanOrEqualTo(0);
        assertThat(reset.getMessage()).contains("重置");

        // delete 软删
        sessionService.deleteConversation(conversationId);
        Long alive = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM reuben_agent_chat_conversation WHERE conversation_id = ? AND is_deleted = 0",
                Long.class, conversationId);
        assertThat(alive).isZero();
    }

    @Test
    @Order(5)
    @DisplayName("观测查询：turnDetail / retrievalResults / channelExecutions / stageBenchmarks")
    void shouldQueryObservations() {
        assertThat(documentTurnId).as("依赖 Order(2) 的 DOCUMENT turn").isNotNull();

        ChatTurnDetailView detail = sessionService.getTurnDetail(documentConversationId, documentTurnId);
        assertThat(detail.getTurn()).isNotNull();
        assertThat(detail.getTurn().getTurnId()).isEqualTo(documentTurnId);
        assertThat(detail.getStageTraces()).isNotEmpty();

        List<RetrievalResultView> retrievals = sessionService.getRetrievalResults(documentConversationId, documentTurnId);
        assertThat(retrievals).hasSize(2);
        assertThat(retrievals.get(0).getDocumentId()).isEqualTo(5001L);

        List<ChannelExecutionView> channels = sessionService.getChannelExecutions(documentConversationId, documentTurnId);
        assertThat(channels).isNotEmpty();
        assertThat(channels.get(0).getChannelType()).isEqualTo("hybrid");

        List<StageBenchmarkView> benchmarks = sessionService.getStageBenchmarks(null);
        assertThat(benchmarks).isNotNull();
    }

    // ======================== 辅助 ========================

    /** 构造一段 ReactAgent 流式输出（AGENT_MODEL_STREAMING + 纯文本 AssistantMessage）。 */
    @SuppressWarnings("unchecked")
    private Flux<com.alibaba.cloud.ai.graph.NodeOutput> streamingOutput() {
        try {
            OverAllState state = new OverAllState();
            StreamingOutput<Object> streaming = new StreamingOutput<>(
                    new AssistantMessage("你好，我是测试助手。"), null, "agent", "agent", state,
                    OutputType.AGENT_MODEL_STREAMING);
            return Flux.just((com.alibaba.cloud.ai.graph.NodeOutput) streaming);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private ChatTurn selectLatestTurnByExecutionMode(Integer executionMode) {
        return turnMapper.selectOne(new com.baomidou.mybatisplus.core.conditions.query
                .LambdaQueryWrapper<ChatTurn>()
                .eq(ChatTurn::getExecutionMode, executionMode)
                .orderByDesc(ChatTurn::getId)
                .last("LIMIT 1"));
    }

    /**
     * 等待流式收尾落库完成。
     *
     * <p>openStream 的 sink 在 finalize 阶段 1 先 emitComplete（collectList().block() 据此返回），
     * 阶段 2 才落 turn 行；二者在弹性线程上异步推进，block 返回时 completeTurn 可能尚未提交。
     * 这里轮询 runtimeRegistry 中该会话任务是否已移除（cleanup 在 completeTurn 之后执行），
     * 保证后续断言读到的是已落库的 turn。</p>
     */
    private void awaitFinalization(String conversationId) {
        long deadline = System.currentTimeMillis() + 10_000L;
        while (System.currentTimeMillis() < deadline) {
            if (runtimeRegistry.get(conversationId).isEmpty()) {
                return;
            }
            try {
                Thread.sleep(20L);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        log.warn("等待收尾超时 → conversationId={}", conversationId);
    }
}
