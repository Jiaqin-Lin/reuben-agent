package com.reubenagent.rag;

import com.reubenagent.rag.model.RetrievalResult;
import com.reubenagent.rag.service.RrfFusionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * RRF 融合服务单元测试 —— 纯逻辑，无需 Spring 容器或 Docker。
 *
 * @author reuben
 * @since 2026-06-21
 */
@DisplayName("RRF 融合服务")
class RrfFusionServiceTest {

    private RrfFusionService fusionService;

    private static final int K = 60;

    @BeforeEach
    void setUp() {
        fusionService = new RrfFusionService();
    }

    // =====================================================================
    // 测试 1：两通道都有结果，部分重叠
    // =====================================================================

    @Test
    @DisplayName("混合融合 — 重叠 chunk 标记 hybrid 且分数累加")
    void hybridFusionWithOverlap() {
        // 向量通道结果（rank 1, 2, 3）
        List<RetrievalResult> vector = List.of(
                result(1L, "向量独有的 chunk A", 0.95, "/A"),
                result(2L, "两边都有的 chunk", 0.88, "/Shared"),
                result(3L, "向量独有的 chunk B", 0.82, "/B"));

        // 关键词通道结果（rank 1, 2, 3）—— chunkId=2 重叠，chunkId=4 仅关键词
        List<RetrievalResult> keyword = List.of(
                result(4L, "关键词独有的 chunk", 2.1, "/KW"),
                result(2L, "两边都有的 chunk", 1.8, "/Shared"),
                result(5L, "另一个关键词 chunk", 1.5, "/KW2"));

        List<RetrievalResult> fused = fusionService.fuse(vector, keyword, K, 10);

        assertThat(fused).hasSize(5);

        // chunkId=2 应该标记为 hybrid，分数 = 1/(60+2) + 1/(60+2) = 1/62 + 1/62
        RetrievalResult hybrid = findByChunkId(fused, 2L);
        assertThat(hybrid).isNotNull();
        assertThat(hybrid.getSource()).isEqualTo("hybrid");
        double expectedHybridScore = 1.0 / (K + 2) + 1.0 / (K + 2);
        assertThat(hybrid.getScore()).isEqualTo(expectedHybridScore);

        // chunkId=1（向量 rank=1）: score = 1/61
        RetrievalResult v1 = findByChunkId(fused, 1L);
        assertThat(v1.getSource()).isEqualTo("vector");
        assertThat(v1.getScore()).isEqualTo(1.0 / (K + 1));

        // chunkId=4（关键词 rank=1）: score = 1/61
        RetrievalResult k1 = findByChunkId(fused, 4L);
        assertThat(k1.getSource()).isEqualTo("keyword");
        assertThat(k1.getScore()).isEqualTo(1.0 / (K + 1));

        // 验证按分数降序：hybrid (chunkId=2) 分数最高，应该排第一
        assertThat(fused.get(0).getChunkId()).isEqualTo(2L);
    }

    // =====================================================================
    // 测试 2：仅向量通道有结果
    // =====================================================================

    @Test
    @DisplayName("纯向量 — 全部标记 vector，RRF 分数从 rank 1 起算")
    void vectorOnly() {
        List<RetrievalResult> vector = List.of(
                result(10L, "向量结果 A", 0.99, "/A"),
                result(20L, "向量结果 B", 0.95, "/B"),
                result(30L, "向量结果 C", 0.90, "/C"));

        List<RetrievalResult> fused = fusionService.fuse(vector, List.of(), K, 10);

        assertThat(fused).hasSize(3);
        assertThat(fused).allMatch(r -> "vector".equals(r.getSource()));

        // rank 1 → 1/61, rank 2 → 1/62, rank 3 → 1/63
        assertThat(fused.get(0).getScore()).isEqualTo(1.0 / (K + 1));
        assertThat(fused.get(1).getScore()).isEqualTo(1.0 / (K + 2));
        assertThat(fused.get(2).getScore()).isEqualTo(1.0 / (K + 3));

        // 原始余弦分数被 RRF 分数覆盖
        assertThat(fused.get(0).getScore()).isNotEqualTo(0.99);
    }

    // =====================================================================
    // 测试 3：仅关键词通道有结果
    // =====================================================================

    @Test
    @DisplayName("纯关键词 — 全部标记 keyword，RRF 分数从 rank 1 起算")
    void keywordOnly() {
        List<RetrievalResult> keyword = List.of(
                result(100L, "关键词结果 X", 3.2, "/X"),
                result(200L, "关键词结果 Y", 2.8, "/Y"));

        List<RetrievalResult> fused = fusionService.fuse(List.of(), keyword, K, 10);

        assertThat(fused).hasSize(2);
        assertThat(fused).allMatch(r -> "keyword".equals(r.getSource()));
        assertThat(fused.get(0).getScore()).isEqualTo(1.0 / (K + 1));
        assertThat(fused.get(1).getScore()).isEqualTo(1.0 / (K + 2));
    }

    // =====================================================================
    // 测试 4：两通道都为空
    // =====================================================================

    @Test
    @DisplayName("两通道都为空 — 返回空列表")
    void bothEmpty() {
        List<RetrievalResult> fused = fusionService.fuse(List.of(), List.of(), K, 5);
        assertThat(fused).isEmpty();
    }

    // =====================================================================
    // 测试 5：null 输入防御
    // =====================================================================

    @Test
    @DisplayName("null 输入 — 不抛异常，当作空列表处理")
    void nullInputsTreatedAsEmpty() {
        // 两个 null
        List<RetrievalResult> fused = fusionService.fuse(null, null, K, 5);
        assertThat(fused).isEmpty();

        // 向量 null，关键词有值
        List<RetrievalResult> keyword = List.of(result(1L, "KW only", 1.0, "/KW"));
        fused = fusionService.fuse(null, keyword, K, 5);
        assertThat(fused).hasSize(1);
        assertThat(fused.get(0).getSource()).isEqualTo("keyword");

        // 关键词 null，向量有值
        List<RetrievalResult> vector = List.of(result(1L, "V only", 0.9, "/V"));
        fused = fusionService.fuse(vector, null, K, 5);
        assertThat(fused).hasSize(1);
        assertThat(fused.get(0).getSource()).isEqualTo("vector");
    }

    // =====================================================================
    // 测试 6：finalTopK 截断
    // =====================================================================

    @Test
    @DisplayName("finalTopK 截断 — 融合结果数不超过 finalTopK")
    void finalTopKTruncation() {
        // 7 个不重叠的 chunk
        List<RetrievalResult> vector = new ArrayList<>();
        List<RetrievalResult> keyword = new ArrayList<>();
        for (long i = 1; i <= 4; i++) {
            vector.add(result(i, "V chunk " + i, 0.9, "/V" + i));
        }
        for (long i = 5; i <= 7; i++) {
            keyword.add(result(i, "KW chunk " + i, 2.0, "/KW" + i));
        }

        List<RetrievalResult> fused = fusionService.fuse(vector, keyword, K, 3);

        assertThat(fused).hasSize(3);
        // 前 3 名应该是 rank 最高的（rank 1 vector + rank 1 keyword + rank 2 vector）
        // rank 1: 1/61 for chunkId=1 (vector) and chunkId=5 (keyword) — equal scores
        // rank 2: 1/62 for chunkId=2 (vector) and chunkId=6 (keyword) — equal scores
        // Due to LinkedHashMap ordering, for equal scores the first inserted comes first
    }

    // =====================================================================
    // 测试 7：finalTopK 大于总数 — 返回全部
    // =====================================================================

    @Test
    @DisplayName("finalTopK 大于总数 — 返回全部")
    void finalTopKLargerThanTotal() {
        List<RetrievalResult> vector = List.of(
                result(1L, "A", 0.9, "/A"),
                result(2L, "B", 0.8, "/B"));

        List<RetrievalResult> fused = fusionService.fuse(vector, List.of(), K, 100);
        assertThat(fused).hasSize(2);
    }

    // =====================================================================
    // 测试 8：相同 chunkId 在两通道不同 rank — 分数准确累加
    // =====================================================================

    @Test
    @DisplayName("相同 chunk 在不同 rank — 分数 = 1/(K+r1) + 1/(K+r2)")
    void sameChunkDifferentRanks() {
        // chunkId=1 在向量 rank=1，在关键词 rank=3
        List<RetrievalResult> vector = List.of(
                result(1L, "共享 chunk", 0.95, "/Shared"),
                result(2L, "向量独有", 0.80, "/VOnly"));

        List<RetrievalResult> keyword = List.of(
                result(3L, "关键词 rank1", 3.0, "/K1"),
                result(4L, "关键词 rank2", 2.5, "/K2"),
                result(1L, "共享 chunk", 1.2, "/Shared"));

        List<RetrievalResult> fused = fusionService.fuse(vector, keyword, K, 10);

        RetrievalResult shared = findByChunkId(fused, 1L);
        assertThat(shared).isNotNull();
        assertThat(shared.getSource()).isEqualTo("hybrid");
        // RRF: 1/(60+1) + 1/(60+3) = 1/61 + 1/63
        assertThat(shared.getScore()).isEqualTo(1.0 / (K + 1) + 1.0 / (K + 3));
    }

    // =====================================================================
    // 测试 9：验证分数严格降序
    // =====================================================================

    @Test
    @DisplayName("排序 — 结果按分数严格降序排列")
    void scoreDescendingOrder() {
        List<RetrievalResult> vector = new ArrayList<>();
        List<RetrievalResult> keyword = new ArrayList<>();
        for (long i = 1; i <= 10; i++) {
            vector.add(result(i, "V" + i, 0.9, "/V" + i));
        }
        for (long i = 11; i <= 20; i++) {
            keyword.add(result(i, "K" + i, 2.0, "/K" + i));
        }

        List<RetrievalResult> fused = fusionService.fuse(vector, keyword, K, 20);

        for (int i = 0; i < fused.size() - 1; i++) {
            assertThat(fused.get(i).getScore())
                    .as("score at index %d should be >= score at index %d", i, i + 1)
                    .isGreaterThanOrEqualTo(fused.get(i + 1).getScore());
        }
    }

    // =====================================================================
    // 测试 10：所有 chunk 都重叠 — 全部 hybrid
    // =====================================================================

    @Test
    @DisplayName("全部重叠 — 所有结果标记 hybrid")
    void allOverlapping() {
        List<RetrievalResult> vector = List.of(
                result(1L, "共享 A", 0.9, "/A"),
                result(2L, "共享 B", 0.8, "/B"));

        List<RetrievalResult> keyword = List.of(
                result(2L, "共享 B", 2.0, "/B"),
                result(1L, "共享 A", 1.5, "/A"));

        List<RetrievalResult> fused = fusionService.fuse(vector, keyword, K, 5);

        assertThat(fused).hasSize(2);
        assertThat(fused).allMatch(r -> "hybrid".equals(r.getSource()));

        // chunkId=1: rank 1 in vector + rank 2 in keyword = 1/61 + 1/62
        RetrievalResult r1 = findByChunkId(fused, 1L);
        assertThat(r1.getScore()).isEqualTo(1.0 / (K + 1) + 1.0 / (K + 2));

        // chunkId=2: rank 2 in vector + rank 1 in keyword = 1/62 + 1/61
        RetrievalResult r2 = findByChunkId(fused, 2L);
        assertThat(r2.getScore()).isEqualTo(1.0 / (K + 2) + 1.0 / (K + 1));

        // 两者分数相同（对称），但顺序取决于 LinkedHashMap 插入顺序
    }

    // =====================================================================
    // helper
    // =====================================================================

    private RetrievalResult result(Long chunkId, String chunkText, double score, String sectionPath) {
        return RetrievalResult.builder()
                .chunkId(chunkId)
                .chunkText(chunkText)
                .score(score)
                .sectionPath(sectionPath)
                .documentId(chunkId * 10)
                .parentBlockId(chunkId * 100)
                .source("unset")
                .build();
    }

    private RetrievalResult findByChunkId(List<RetrievalResult> results, Long chunkId) {
        return results.stream()
                .filter(r -> chunkId.equals(r.getChunkId()))
                .findFirst()
                .orElse(null);
    }
}
