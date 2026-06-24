package com.reubenagent.chat.model;

import lombok.Builder;
import lombok.Value;

import java.util.List;

/**
 * 引文来源统一模型。
 *
 * <p>区分 document/web 由字段决定（{@code documentId} 非空为文档引用，{@code url} 非空为联网结果），
 * 不在构造器硬塞 {@code sourceType}（修正 super-agent 硬编码 {@code WEB}/{@code toolName}）。</p>
 *
 * @author reuben
 * @since 2026-06-24
 */
@Value
@Builder
public class SearchReference {

    String referenceId;
    String title;
    String url;
    String snippet;
    Long documentId;
    String documentName;
    Long chunkId;
    Long parentBlockId;
    String sectionPath;
    Double score;
    Integer subQuestionIndex;
    String subQuestion;
    String channel;
    String toolName;
    Integer itemIndex;

    /** 唯一键，用于去重。 */
    public String uniqueKey() {
        if (documentId != null && chunkId != null) {
            return "doc:" + documentId + ":" + chunkId;
        }
        if (url != null) {
            return "web:" + url;
        }
        return String.valueOf(referenceId);
    }

    public static List<SearchReference> emptyIfNull(List<SearchReference> list) {
        return list == null ? List.of() : list;
    }
}
