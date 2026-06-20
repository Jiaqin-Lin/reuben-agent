package com.reubenagent.document.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * 文档检索请求 DTO —— 关键字 / 混合检索的入参。
 *
 * @author reuben
 * @since 2026-06-21
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DocumentRetrieveRequest {

    /** 检索查询文本 */
    private String query;

    /** 返回结果数量上限 */
    @Builder.Default
    private Integer topK = 10;

    /** 过滤字段（如 documentId、knowledgeScopeCode 等） */
    private Map<String, String> filterFields;

    /** 最低相关性分数阈值 */
    @Builder.Default
    private Double scoreThreshold = 0.0;
}
