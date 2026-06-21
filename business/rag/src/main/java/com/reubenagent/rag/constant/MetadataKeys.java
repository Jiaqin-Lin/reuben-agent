package com.reubenagent.rag.constant;

/**
 * ES 索引字段和 metadata JSON key 常量 —— 避免字符串字面量散落各处。
 *
 * <p>对齐 {@code EsDocumentKeywordSearchGateway} 的索引映射字段。</p>
 *
 * @author reuben
 * @since 2026-06-21
 */
public final class MetadataKeys {

    private MetadataKeys() {
    }

    // ============ ES 索引字段 ============

    public static final String CHUNK_ID = "chunkId";
    public static final String CHUNK_TEXT = "chunkText";
    public static final String DOCUMENT_ID = "documentId";
    public static final String PARENT_BLOCK_ID = "parentBlockId";
    public static final String SECTION_PATH = "sectionPath";
    public static final String TASK_ID = "taskId";
    public static final String PLAN_ID = "planId";
    public static final String CHUNK_NO = "chunkNo";
    public static final String CHAR_COUNT = "charCount";
    public static final String SOURCE_TYPE = "sourceType";
}
