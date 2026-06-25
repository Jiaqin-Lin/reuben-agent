package com.reubenagent.chat.support;

import com.reubenagent.chat.model.SearchReference;
import com.reubenagent.rag.model.RetrievalResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 检索结果 → 引文映射器。
 *
 * <p>把 {@link RetrievalResult} 映射为统一的 {@link SearchReference}，供 SSE reference 事件
 * 与 {@code turn.source_snapshot_list} 持久化使用。</p>
 *
 * <p>核心修正：移除 super-agent 在构造器硬塞的 {@code sourceType="WEB"} /
 * {@code toolName="tavily_search"}——document/web 由字段决定
 * （{@code documentId != null} → 文档引用；{@code url != null} → 联网结果），
 * 不在构造器强行塞死。channel/toolName 由调用方按来源显式传入。</p>
 *
 * @author reuben
 * @since 2026-06-25
 */
@Slf4j
@Component
public class SearchReferenceMapper {

    /**
     * 单条映射（文档检索结果）。
     *
     * @param result           rag 检索结果
     * @param documentName     文档名（adapter 端解析，避免此处查表）
     * @param subQuestionIndex 子问题序号
     * @param subQuestion      子问题文本
     * @param itemIndex        全局证据块编号（用于 [1][2] 引用编号）
     */
    public SearchReference fromRetrieval(RetrievalResult result, String documentName,
                                         Integer subQuestionIndex, String subQuestion,
                                         Integer itemIndex) {
        if (result == null) {
            return null;
        }
        return SearchReference.builder()
                .referenceId(buildReferenceId(result, itemIndex))
                .title(documentName)
                .documentId(result.getDocumentId())
                .documentName(documentName)
                .chunkId(result.getChunkId())
                .parentBlockId(result.getParentBlockId())
                .sectionPath(result.getSectionPath())
                .snippet(clipSnippet(result.getChunkText()))
                .score(result.getScore())
                .channel(result.getSource())
                .subQuestionIndex(subQuestionIndex)
                .subQuestion(subQuestion)
                .itemIndex(itemIndex)
                .build();
    }

    /**
     * 批量映射，跳过 null 输入；返回的列表 itemIndex 从 {@code startIndex} 起递增。
     */
    public List<SearchReference> fromRetrievals(List<RetrievalResult> results, String documentName,
                                                Integer subQuestionIndex, String subQuestion,
                                                int startIndex) {
        List<SearchReference> list = new ArrayList<>();
        if (results == null || results.isEmpty()) {
            return list;
        }
        int idx = startIndex;
        for (RetrievalResult r : results) {
            SearchReference ref = fromRetrieval(r, documentName, subQuestionIndex, subQuestion, idx);
            if (ref != null) {
                list.add(ref);
                idx++;
            }
        }
        return list;
    }

    private String buildReferenceId(RetrievalResult result, Integer itemIndex) {
        if (result.getDocumentId() != null && result.getChunkId() != null) {
            return "doc:" + result.getDocumentId() + ":" + result.getChunkId();
        }
        return itemIndex == null ? null : String.valueOf(itemIndex);
    }

    /** 证据块预览裁剪到 200 字，避免 prompt 与 SSE 事件过大。 */
    private String clipSnippet(String chunkText) {
        return ChatTexts.clip(chunkText, 200);
    }
}
