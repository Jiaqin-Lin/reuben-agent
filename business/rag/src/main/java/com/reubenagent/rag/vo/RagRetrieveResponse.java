package com.reubenagent.rag.vo;

import com.reubenagent.rag.model.RetrievalResult;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * RAG 检索响应体。
 *
 * @author reuben
 * @since 2026-06-21
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "RAG 检索响应")
public class RagRetrieveResponse {

    /** 检索结果列表 */
    @Schema(description = "检索结果列表")
    private List<RetrievalResult> results;

    /** 总耗时（ms） */
    @Schema(description = "总耗时（毫秒）", example = "125")
    private long totalCostMs;

    /** 改写后的查询文本（可观测），如果未改写则为 null */
    @Schema(description = "改写后的查询文本", example = "Docker 容器化技术 应用部署 CI/CD 流水线")
    private String rewrittenQuery;
}
