package com.reubenagent.document.service.impl;

import com.alibaba.fastjson.JSON;
import com.reubenagent.document.entity.KnowledgeRouteTrace;
import com.reubenagent.document.mapper.IKnowledgeRouteTraceMapper;
import com.reubenagent.document.model.route.RouteTraceContext;
import com.reubenagent.framework.uid.UidGenerator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 路由追踪服务 —— 异步写入 {@code reuben_agent_knowledge_route_trace} 表。
 *
 * <p>使用守护线程池异步执行，失败不中断路由主流程，仅打 warn 日志。</p>
 *
 * @author reuben
 * @since 2026-06-28
 */
@Slf4j
@Service
public class KnowledgeRouteTraceService {

    private static final ExecutorService TRACE_EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "knowledge-route-trace");
        t.setDaemon(true);
        return t;
    });

    private final IKnowledgeRouteTraceMapper traceMapper;
    private final UidGenerator uidGenerator;

    public KnowledgeRouteTraceService(IKnowledgeRouteTraceMapper traceMapper, UidGenerator uidGenerator) {
        this.traceMapper = traceMapper;
        this.uidGenerator = uidGenerator;
    }

    /**
     * 异步保存路由追踪记录（fire-and-forget）。
     */
    public void saveTrace(RouteTraceContext ctx) {
        CompletableFuture.runAsync(() -> doSave(ctx), TRACE_EXECUTOR)
                .exceptionally(e -> {
                    log.warn("路由追踪保存失败: conversationId={} turnId={}",
                            ctx.getConversationId(), ctx.getTurnId(), e);
                    return null;
                });
    }

    private void doSave(RouteTraceContext ctx) {
        try {
            KnowledgeRouteTrace trace = KnowledgeRouteTrace.builder()
                    .id(uidGenerator.getUid())
                    .conversationId(ctx.getConversationId())
                    .turnId(ctx.getTurnId())
                    .question(ctx.getQuestion())
                    .rewriteQuestion(ctx.getRewriteQuestion())
                    .mode(ctx.getMode())
                    .topScopesJson(ctx.getScopeCandidates() != null
                            ? JSON.toJSONString(ctx.getScopeCandidates()) : null)
                    .topTopicsJson(ctx.getTopicCandidates() != null
                            ? JSON.toJSONString(ctx.getTopicCandidates()) : null)
                    .topDocumentsJson(ctx.getDocumentCandidates() != null
                            ? JSON.toJSONString(ctx.getDocumentCandidates()) : null)
                    .selectedDocumentId(ctx.getSelectedDocumentId())
                    .hitSelectedDocument(computeHit(ctx))
                    .confidence(ctx.getConfidence())
                    .routeStatus(ctx.getRouteStatus())
                    .errorMsg(ctx.getErrorMsg())
                    .build();

            traceMapper.insert(trace);
            log.debug("路由追踪已保存: conversationId={} turnId={} status={}",
                    ctx.getConversationId(), ctx.getTurnId(), ctx.getRouteStatus());

        } catch (Exception e) {
            log.warn("路由追踪保存失败: conversationId={} turnId={}", ctx.getConversationId(), ctx.getTurnId(), e);
        }
    }

    /** 检查 selectedDocumentId 是否在 top 3 document 候选列表中。 */
    private int computeHit(RouteTraceContext ctx) {
        if (ctx.getSelectedDocumentId() == null || ctx.getDocumentCandidates() == null) return 0;
        return ctx.getDocumentCandidates().stream()
                .limit(3)
                .anyMatch(d -> ctx.getSelectedDocumentId().equals(d.getDocumentId())) ? 1 : 0;
    }
}
