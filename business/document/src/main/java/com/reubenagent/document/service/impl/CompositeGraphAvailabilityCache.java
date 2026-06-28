package com.reubenagent.document.service.impl;

import java.util.concurrent.ConcurrentHashMap;

/**
 * 图可用性缓存 —— per documentId 的布尔标记，避免每次查询都做一次 Cypher 探活。
 *
 * <p>投影成功后由 {@code Neo4jDocumentStructureGraphProjectionService.markAvailable} 设 true，
 * 删除后设 false。{@code CompositeDocumentStructureGraphService.delegate} 读取此缓存决定路由。</p>
 *
 * @author reuben
 * @since 2026-06-28
 */
public final class CompositeGraphAvailabilityCache {

    private static final ConcurrentHashMap<Long, Boolean> CACHE = new ConcurrentHashMap<>();

    private CompositeGraphAvailabilityCache() {
    }

    public static void set(Long documentId, boolean available) {
        if (documentId == null) {
            return;
        }
        if (available) {
            CACHE.put(documentId, Boolean.TRUE);
        } else {
            CACHE.put(documentId, Boolean.FALSE);
        }
    }

    /** 返回 null 表示未知（未投影过），需回退到 Neo4j 实时探活 */
    public static Boolean get(Long documentId) {
        return documentId == null ? null : CACHE.get(documentId);
    }

    public static void evict(Long documentId) {
        if (documentId != null) {
            CACHE.remove(documentId);
        }
    }
}
