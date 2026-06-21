package com.reubenagent.rag.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * RAG 混合检索请求体。
 *
 * @author reuben
 * @since 2026-06-21
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "RAG 混合检索请求")
public class RagRetrieveRequest {

    /** 检索查询文本（必填） */
    @NotBlank(message = "查询文本不能为空")
    @Schema(description = "检索查询文本", required = true, example = "如何配置 Kafka 消费者？")
    private String query;

    /** 返回数量，默认取 {@code reuben.rag.retrieval.finalTopK} */
    @Schema(description = "返回数量", example = "5")
    private Integer topK;

    /** 过滤条件，key 为字段名（如 documentId），value 为过滤值 */
    @Schema(description = "过滤条件 (如 documentId → \"123\")", example = "{\"documentId\": \"123\"}")
    private Map<String, String> filterFields;
}
