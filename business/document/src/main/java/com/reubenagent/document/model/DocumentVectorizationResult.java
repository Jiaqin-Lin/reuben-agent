package com.reubenagent.document.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * 向量化结果 VO —— 让调用方显式知道哪些 chunk 成功、哪些失败。
 *
 * <p>与 super-agent 不同，vectorize() 不直接修改入参 DocumentChunk，
 * 而是通过本 VO 返回结果，由调用方决定 MySQL 状态回写策略。</p>
 *
 * @author reuben
 * @since 2026-06-20
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DocumentVectorizationResult {

    /** 传入 chunk 总数 */
    private int totalCount;

    /** 向量化成功数 */
    private int successCount;

    /** 向量化失败数 */
    private int failedCount;

    /** 向量化成功的 chunk ID 列表 */
    @Builder.Default
    private List<Long> successChunkIds = new ArrayList<>();

    /** 向量化失败的 chunk ID 列表 */
    @Builder.Default
    private List<Long> failedChunkIds = new ArrayList<>();
}
