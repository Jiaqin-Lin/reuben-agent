package com.reubenagent.rag.service;

import com.reubenagent.rag.model.RetrievalResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 父块提升服务 —— 将小 chunk 替换为所属 parent block 的完整文本，返回更完整的上下文。
 *
 * <h3>流程</h3>
 * <ol>
 *   <li>收集融合结果中的 {@code parentBlockId}（跳过 null）</li>
 *   <li>批量查询 MySQL {@code reuben_agent_document_parent_block} 获取完整 parentText</li>
 *   <li>替换 chunkText → parentText，source → source+"+parent"</li>
 *   <li>去重：同一 parent block 只保留分数最高的一条</li>
 * </ol>
 *
 * <h3>与 super-agent 的差异</h3>
 * <ul>
 *   <li>super-agent 通过 entity 层 + MyBatis-Plus 访问 —— reuben-agent 直连 MySQL JDBC，更简单</li>
 *   <li>去重用 LinkedHashMap（O(n)），保持分数优先语义</li>
 * </ul>
 *
 * @author reuben
 * @since 2026-06-22
 */
@Slf4j
@Component
public class ParentBlockElevationService {

    private final JdbcTemplate mysqlJdbcTemplate;

    public ParentBlockElevationService(
            @Qualifier("ragMySqlJdbcTemplate") JdbcTemplate mysqlJdbcTemplate) {
        this.mysqlJdbcTemplate = mysqlJdbcTemplate;
    }

    /**
     * 对融合后的检索结果执行父块提升。
     *
     * @param fusedResults RRF 融合后的结果列表（已按 score 降序排列）
     * @return 父块提升后的结果列表（去重、按分数降序）
     */
    public List<RetrievalResult> elevate(List<RetrievalResult> fusedResults) {
        if (fusedResults == null || fusedResults.isEmpty()) {
            return List.of();
        }

        // 阶段 1：收集所有 parentBlockId（跳过 null）
        Set<Long> parentBlockIds = fusedResults.stream()
                .map(RetrievalResult::getParentBlockId)
                .filter(id -> id != null)
                .collect(Collectors.toSet());

        if (parentBlockIds.isEmpty()) {
            log.debug("无有效 parentBlockId，跳过父块提升，原样返回 {} 条", fusedResults.size());
            return new ArrayList<>(fusedResults);
        }

        // 阶段 2：批量查询 MySQL parent_text
        Map<Long, String> parentTextMap = queryParentTexts(parentBlockIds);
        if (parentTextMap.isEmpty()) {
            log.debug("MySQL 未查到匹配的 parent block，原样返回 {} 条", fusedResults.size());
            return new ArrayList<>(fusedResults);
        }

        // 阶段 3：替换 chunkText → parentText，source → source+"+parent"，去重
        LinkedHashMap<Long, RetrievalResult> elevatedMap = new LinkedHashMap<>();

        for (RetrievalResult r : fusedResults) {
            Long pbId = r.getParentBlockId();
            String parentText = (pbId != null) ? parentTextMap.get(pbId) : null;
            if (parentText == null) {
                // 无 parent text → 保留原始结果
                elevatedMap.putIfAbsent(r.getChunkId(), r);
                continue;
            }

            // 同一 parent block 去重：保留分数更高的一条
            RetrievalResult existing = elevatedMap.get(pbId);
            if (existing != null) {
                if (r.getScore() != null && (existing.getScore() == null || r.getScore() > existing.getScore())) {
                    elevatedMap.put(pbId, buildElevatedResult(r, parentText));
                }
                // 否则保留已有的（分数更高或相等）
            } else {
                elevatedMap.put(pbId, buildElevatedResult(r, parentText));
            }
        }

        // 阶段 4：按分数降序排列
        List<RetrievalResult> results = new ArrayList<>(elevatedMap.values());
        results.sort(Comparator.comparing(
                RetrievalResult::getScore,
                Comparator.nullsLast(Comparator.reverseOrder())));

        log.debug("父块提升完成: {} 条 → {} 条 (提升 {} 条, 去重 {} 条)",
                fusedResults.size(), results.size(),
                parentTextMap.size(), fusedResults.size() - results.size());

        return results;
    }

    /** 查询 MySQL parent_text。 */
    private Map<Long, String> queryParentTexts(Set<Long> parentBlockIds) {
        if (parentBlockIds.isEmpty()) {
            return Collections.emptyMap();
        }

        // 构建 IN 子句占位符
        String placeholders = parentBlockIds.stream()
                .map(id -> "?")
                .collect(Collectors.joining(","));

        String sql = "SELECT id, parent_text FROM reuben_agent_document_parent_block"
                + " WHERE id IN (" + placeholders + ")"
                + " AND is_deleted = 0";

        Object[] params = parentBlockIds.toArray();

        try {
            List<Map<String, Object>> rows = mysqlJdbcTemplate.queryForList(sql, params);
            Map<Long, String> result = new LinkedHashMap<>();
            for (Map<String, Object> row : rows) {
                Long id = toLong(row.get("id"));
                String parentText = (String) row.get("parent_text");
                if (id != null && parentText != null && !parentText.isBlank()) {
                    result.put(id, parentText);
                }
            }
            log.debug("MySQL parent block 查询: 请求 {} 条, 命中 {} 条", parentBlockIds.size(), result.size());
            return result;
        } catch (Exception e) {
            log.warn("MySQL parent block 查询失败，降级原样返回: {}", e.getMessage());
            return Collections.emptyMap();
        }
    }

    /** 构建父块提升后的结果。 */
    private RetrievalResult buildElevatedResult(RetrievalResult original, String parentText) {
        String newSource = (original.getSource() != null ? original.getSource() : "unknown") + "+parent";
        return RetrievalResult.builder()
                .chunkId(original.getChunkId())
                .chunkText(parentText)
                .score(original.getScore())
                .sectionPath(original.getSectionPath())
                .documentId(original.getDocumentId())
                .parentBlockId(original.getParentBlockId())
                .source(newSource)
                .build();
    }

    /** 安全转换 Object → Long。 */
    private Long toLong(Object value) {
        if (value == null) return null;
        if (value instanceof Long l) return l;
        if (value instanceof Integer i) return i.longValue();
        if (value instanceof Number n) return n.longValue();
        return null;
    }
}
