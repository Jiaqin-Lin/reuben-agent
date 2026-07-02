package com.reubenagent.chat.service.impl;

import com.reubenagent.chat.config.ChatProperties;
import com.reubenagent.chat.enums.ChatTraceStageCode;
import com.reubenagent.chat.model.SearchReference;
import com.reubenagent.chat.service.IChatRecommendationService;
import com.reubenagent.chat.service.ObservedChatModelService;
import com.reubenagent.chat.support.ChatJsonCodec;
import com.reubenagent.chat.support.ChatPromptNames;
import com.reubenagent.chat.support.ChatPromptTemplateService;
import com.reubenagent.chat.support.ChatTexts;
import com.reubenagent.chat.trace.ChatTraceRecorder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * 推荐追问服务实现。
 *
 * <p>流程：构造 prompt（recent context + 引用摘要 + 当前问答）→ {@code observedChatModelService.callText}
 * （同步阻塞）→ JSON 数组解析（首个平衡数组）→ 去重截断。
 * 同步调用保证 model usage trace 在 return 前已记录，不会因异步超时丢失。</p>
 *
 * <p>失败语义：warn + 落 RECOMMENDATION trace error，返回空集合让前端显示占位。</p>
 *
 * @author reuben
 * @since 2026-06-27
 */
@Slf4j
@Service
public class ChatRecommendationServiceImpl implements IChatRecommendationService {

    private final ChatProperties properties;
    private final ObservedChatModelService observedChatModelService;
    private final ChatPromptTemplateService promptTemplateService;
    private final ChatJsonCodec jsonCodec;

    public ChatRecommendationServiceImpl(ChatProperties properties,
                                         ObservedChatModelService observedChatModelService,
                                         ChatPromptTemplateService promptTemplateService,
                                         ChatJsonCodec jsonCodec) {
        this.properties = properties;
        this.observedChatModelService = observedChatModelService;
        this.promptTemplateService = promptTemplateService;
        this.jsonCodec = jsonCodec;
    }

    @Override
    public List<String> recommend(String question, String answer, List<SearchReference> references,
                                  ChatTraceRecorder traceRecorder) {
        ChatProperties.Recommendation cfg = properties.getRecommendation();
        if (cfg.getEnabled() == null || !cfg.getEnabled()) {
            log.debug("推荐追问已关闭，跳过");
            return List.of();
        }
        if (answer == null || answer.isBlank()) {
            return List.of();
        }

        ChatTraceRecorder.StageHandle stage = startRecommendStage(traceRecorder);
        try {
            // 阶段：同步调用 LLM，trace 在 callText 内部即时记录，不丢
            List<String> result = doRecommend(question, answer, references, cfg, traceRecorder);
            completeRecommendStage(traceRecorder, stage, "生成 " + result.size() + " 条追问");
            return result;
        } catch (Exception e) {
            log.warn("推荐追问失败 → err={}", e.getMessage());
            failRecommendStage(traceRecorder, stage, e.getMessage());
            return List.of();
        }
    }

    private List<String> doRecommend(String question, String answer, List<SearchReference> references,
                                     ChatProperties.Recommendation cfg, ChatTraceRecorder traceRecorder) {
        String prompt = buildPrompt(question, answer, references, cfg);
        String content;
        try {
            content = observedChatModelService.callText(
                    ChatTraceStageCode.RECOMMENDATION.name(), prompt, null,
                    traceRecorder == null ? null : traceRecorder.traceSink());
        } catch (Exception e) {
            // 阶段：模型调用失败已在 service 落 trace，这里抛出让外层 exceptionally 兜底
            throw new RuntimeException(e.getMessage(), e);
        }
        if (content == null || content.isBlank()) {
            log.warn("推荐追问返回空内容 → conversationId={}",
                    traceRecorder == null ? null : traceRecorder.getConversationId());
            return List.of();
        }
        return parseRecommendations(content, cfg);
    }

    private String buildPrompt(String question, String answer, List<SearchReference> references,
                               ChatProperties.Recommendation cfg) {
        int maxCount = cfg.getMaxCount() == null ? 3 : cfg.getMaxCount();
        Map<String, String> vars = Map.of(
                "basePrompt", ChatTexts.safe(cfg.getPrompt()),
                "recentContext", buildReferenceContext(references),
                "question", ChatTexts.safe(question),
                "answer", ChatTexts.safe(answer),
                "maxCount", String.valueOf(maxCount)
        );
        return promptTemplateService.render(ChatPromptNames.RECOMMENDATION_USER, vars);
    }

    /** 把引用标题/文档名拼成上下文块，帮助追问聚焦证据来源。 */
    private String buildReferenceContext(List<SearchReference> references) {
        if (references == null || references.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        int limit = Math.min(references.size(), 5);
        for (int i = 0; i < limit; i++) {
            SearchReference ref = references.get(i);
            if (ref == null) {
                continue;
            }
            String title = ref.getTitle();
            if (title == null || title.isBlank()) {
                title = ref.getDocumentName();
            }
            if (title == null || title.isBlank()) {
                continue;
            }
            sb.append("- ").append(ChatTexts.clip(title, 80)).append('\n');
        }
        return sb.toString().trim();
    }

    private List<String> parseRecommendations(String content, ChatProperties.Recommendation cfg) {
        String jsonArray = jsonCodec.extractFirstBalancedArray(content);
        if (jsonArray == null || jsonArray.isBlank()) {
            log.warn("推荐追问输出非有效 JSON 数组 → contentHead={}", ChatTexts.clip(content, 120));
            return List.of();
        }
        List<String> raw = jsonCodec.parseList(jsonArray, String.class);
        if (raw.isEmpty()) {
            return List.of();
        }
        int maxCount = cfg.getMaxCount() == null ? 3 : cfg.getMaxCount();
        LinkedHashSet<String> unique = new LinkedHashSet<>();
        for (String item : raw) {
            if (item != null && !item.isBlank()) {
                unique.add(item.trim());
            }
            if (unique.size() >= maxCount) {
                break;
            }
        }
        return new ArrayList<>(unique);
    }

    private ChatTraceRecorder.StageHandle startRecommendStage(ChatTraceRecorder recorder) {
        if (recorder == null) {
            return null;
        }
        return recorder.startStage(ChatTraceStageCode.RECOMMENDATION, null, "生成推荐追问", null);
    }

    private void completeRecommendStage(ChatTraceRecorder recorder, ChatTraceRecorder.StageHandle stage, String summary) {
        if (recorder == null || stage == null) {
            return;
        }
        recorder.completeStage(stage, summary, null);
    }

    private void failRecommendStage(ChatTraceRecorder recorder, ChatTraceRecorder.StageHandle stage, String error) {
        if (recorder == null || stage == null) {
            return;
        }
        recorder.failStage(stage, "推荐追问失败", error, null);
    }
}
