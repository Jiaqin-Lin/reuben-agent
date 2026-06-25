package com.reubenagent.chat.orchestrate;

import com.reubenagent.chat.config.ChatProperties;
import com.reubenagent.chat.model.SearchReference;
import com.reubenagent.chat.model.orchestrate.ChatRetrievalResult;
import com.reubenagent.chat.model.orchestrate.ConversationExecutionPlan;
import com.reubenagent.chat.support.ChatPromptTemplateService;
import com.reubenagent.chat.support.ChatTexts;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * RAG 回答 Prompt 组装服务 —— 把检索结果 + 历史 transcript + 用户问题拼成 system / user prompt。
 *
 * <p>对标 super-agent {@code RagPromptAssemblyService}，reuben 简化与修正：
 * <ul>
 *   <li>不支持 StringTemplate 的 {@code <if(...)>} 语法（{@link ChatPromptTemplateService} 仅做
 *       简单 {@code <key>} 替换）—— 改为按场景决定是否拼入整块文本（空串则占位符消失）。</li>
 *   <li>证据块裁剪用 {@link ChatTexts#clip}，移除 super-agent 散落 {@code trimSnippet}。</li>
 *   <li>budget 计算：总预算 + 每子问题预算，复用 super-agent 的双重 budget 逻辑，
 *       但简化为单层循环（不分 web/document 模板，统一 {@code [n] documentName / sectionPath}）。</li>
 *   <li>历史 transcript 取 {@code answerRecentTranscript}（只含答案摘要，省 token），
 *       从 {@link ConversationExecutionPlan#getRecentTranscript()} 读，组装时包成块文本。</li>
 *   <li>不重造 web/document 双模板分支——reuben Phase 6 仅文档检索，统一格式。
 *       Phase 7 联网工具如需 web 引用块，可在 {@link SearchReference} 上扩展。</li>
 * </ul></p>
 *
 * @author reuben
 * @since 2026-06-25
 */
@Slf4j
@Service
@AllArgsConstructor
public class ChatRagPromptAssemblyService {

    private static final String TEMPLATE_SYSTEM = "rag-answer-system";
    private static final String TEMPLATE_USER = "rag-answer-user";

    private final ChatPromptTemplateService templateService;
    private final ChatProperties properties;

    /**
     * 组装 RAG 回答 prompt。
     *
     * @param plan            执行计划（含 originalQuestion / rewrittenQuery / subQuestions / 历史 transcript）
     * @param retrievalResults adapter 产出的检索结果（每个子问题一组）
     * @param currentDateText 当前日期文本（注入到 user prompt 顶部）
     * @return 含 systemPrompt / userPrompt / 渲染统计（rendered / omitted / 已用 budget）
     */
    public AssemblyResult assemble(ConversationExecutionPlan plan,
                                   List<ChatRetrievalResult> retrievalResults,
                                   String currentDateText) {
        int charBudget = properties.getRag().getCharBudget();
        Budget budget = new Budget(charBudget);

        EvidenceBlocks evidence = buildEvidenceBlocks(retrievalResults, budget);
        String systemPrompt = templateService.render(TEMPLATE_SYSTEM, Map.of());
        String userPrompt = templateService.render(TEMPLATE_USER, Map.of(
                "currentDate", ChatTexts.safe(currentDateText),
                "originalQuestion", ChatTexts.safe(plan == null ? null : plan.getOriginalQuestion()),
                "retrievalQuestion", buildRetrievalQuestion(plan),
                "historyContextBlock", buildHistoryContextBlock(plan),
                "subQuestionsBlock", buildSubQuestionsBlock(plan),
                "evidenceBlocks", evidence.rendered
        ));
        return new AssemblyResult(systemPrompt, userPrompt,
                evidence.renderedReferenceCount, evidence.omittedReferenceCount,
                budget.totalBudget, budget.consumed);
    }

    private String buildRetrievalQuestion(ConversationExecutionPlan plan) {
        if (plan == null) {
            return "";
        }
        String rewritten = plan.getRewrittenQuery();
        String original = plan.getOriginalQuestion();
        if (rewritten == null || rewritten.isBlank() || rewritten.equals(original)) {
            return "";
        }
        return rewritten;
    }

    private String buildHistoryContextBlock(ConversationExecutionPlan plan) {
        if (plan == null) {
            return "";
        }
        String transcript = plan.getRecentTranscript();
        if (transcript == null || transcript.isBlank()) {
            return "";
        }
        return "\n对话承接上下文（仅用于理解指代，不可作为事实来源）：\n" + transcript.trim() + "\n";
    }

    private String buildSubQuestionsBlock(ConversationExecutionPlan plan) {
        if (plan == null || plan.getRewriteResult() == null
                || !plan.getRewriteResult().hasSubQuestions()) {
            return "";
        }
        List<String> subs = plan.getRewriteResult().getSubQuestions();
        if (subs.size() <= 1) {
            return "";
        }
        StringBuilder sb = new StringBuilder("\n问题已拆分为以下子问题：\n");
        for (int i = 0; i < subs.size(); i++) {
            sb.append(i + 1).append(". ").append(subs.get(i)).append("\n");
        }
        return sb.toString();
    }

    private EvidenceBlocks buildEvidenceBlocks(List<ChatRetrievalResult> retrievalResults, Budget budget) {
        if (retrievalResults == null || retrievalResults.isEmpty()) {
            return new EvidenceBlocks("", 0, 0);
        }
        StringBuilder sb = new StringBuilder();
        Map<String, Integer> renderedKeys = new LinkedHashMap<>();
        int renderedCount = 0;
        int omittedCount = 0;
        int itemIndex = 1;
        for (ChatRetrievalResult group : retrievalResults) {
            if (group == null) {
                continue;
            }
            List<SearchReference> refs = group.getReferences();
            if (refs == null || refs.isEmpty()) {
                continue;
            }
            for (SearchReference ref : refs) {
                if (ref == null) {
                    continue;
                }
                String uniqueKey = ref.uniqueKey();
                Integer existing = renderedKeys.get(uniqueKey);
                if (existing != null) {
                    String reuseLine = "[" + existing + "] （同上）\n";
                    if (budget.tryConsume(reuseLine.length())) {
                        sb.append(reuseLine);
                    }
                    continue;
                }
                String block = renderEvidenceBlock(ref, itemIndex);
                if (!budget.tryConsume(block.length())) {
                    omittedCount++;
                    break;
                }
                sb.append(block);
                renderedKeys.put(uniqueKey, itemIndex);
                renderedCount++;
                itemIndex++;
            }
        }
        if (sb.length() == 0) {
            return new EvidenceBlocks("", 0, omittedCount);
        }
        return new EvidenceBlocks(sb.toString().trim(), renderedCount, omittedCount);
    }

    private String renderEvidenceBlock(SearchReference ref, int itemIndex) {
        String docName = ChatTexts.safe(ref.getDocumentName());
        if (docName.isEmpty()) {
            docName = ChatTexts.safe(ref.getTitle());
        }
        if (docName.isEmpty()) {
            docName = "文档来源";
        }
        String section = ChatTexts.safe(ref.getSectionPath());
        if (section.isEmpty()) {
            section = "未识别";
        }
        String snippet = ChatTexts.clip(ref.getSnippet(), 800);
        return "[" + itemIndex + "] " + docName + " / " + section + "\n"
                + (snippet.isEmpty() ? "（无文本片段）" : snippet) + "\n\n";
    }

    /** Prompt 组装结果。 */
    @Getter
    @AllArgsConstructor
    public static class AssemblyResult {
        private final String systemPrompt;
        private final String userPrompt;
        private final int renderedReferenceCount;
        private final int omittedReferenceCount;
        private final int totalBudget;
        private final int consumedBudget;
    }

    private static final class EvidenceBlocks {
        final String rendered;
        final int renderedReferenceCount;
        final int omittedReferenceCount;
        EvidenceBlocks(String rendered, int renderedCount, int omittedCount) {
            this.rendered = rendered;
            this.renderedReferenceCount = renderedCount;
            this.omittedReferenceCount = omittedCount;
        }
    }

    private static final class Budget {
        final int totalBudget;
        int consumed;
        Budget(int totalBudget) {
            this.totalBudget = totalBudget <= 0 ? Integer.MAX_VALUE : totalBudget;
        }
        boolean tryConsume(int size) {
            if (consumed + size > totalBudget) {
                return false;
            }
            consumed += size;
            return true;
        }
    }
}
