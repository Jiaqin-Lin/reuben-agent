-- =============================================================================
-- reuben-agent PostgreSQL pgvector DDL
-- 数据库: reuben_agent_pgvector
-- 依赖: CREATE EXTENSION IF NOT EXISTS vector;
-- =============================================================================

-- 手动执行:
-- CREATE DATABASE reuben_agent_pgvector;
-- \c reuben_agent_pgvector
-- CREATE EXTENSION IF NOT EXISTS vector;

-- ---------------------------------------------------------------------------
-- 文档嵌入表
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS public.reuben_agent_document_embedding (
    id              BIGINT NOT NULL PRIMARY KEY,
    document_id     BIGINT NOT NULL,
    task_id         BIGINT,
    chunk_id        BIGINT,
    chunk_no        INT,
    parent_block_id BIGINT,
    section_path    VARCHAR(1024),
    chunk_text      TEXT,
    char_count      INT,
    embedding       VECTOR,
    embedding_model VARCHAR(128),
    metadata_json   JSONB DEFAULT '{}'::jsonb,
    create_time     TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    edit_time       TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- 向量索引 (IVFFlat 适合百万级以下数据，更大规模用 HNSW)
CREATE INDEX IF NOT EXISTS idx_embedding_vector
    ON public.reuben_agent_document_embedding
    USING ivfflat (embedding vector_cosine_ops)
    WITH (lists = 100);

-- 业务索引
CREATE INDEX IF NOT EXISTS idx_embedding_document_id
    ON public.reuben_agent_document_embedding (document_id);

CREATE INDEX IF NOT EXISTS idx_embedding_chunk_id
    ON public.reuben_agent_document_embedding (chunk_id);

-- 全文搜索（可选，用于混合检索）
ALTER TABLE public.reuben_agent_document_embedding
    ADD COLUMN IF NOT EXISTS chunk_text_tsv TSVECTOR;

CREATE INDEX IF NOT EXISTS idx_embedding_tsv
    ON public.reuben_agent_document_embedding
    USING GIN (chunk_text_tsv);

-- 自动更新 tsvector 的触发器函数
CREATE OR REPLACE FUNCTION public.update_embedding_tsv()
RETURNS TRIGGER AS $$
BEGIN
    NEW.chunk_text_tsv = to_tsvector('simple', COALESCE(NEW.chunk_text, ''));
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

-- 触发器（如果不存在则创建）
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_trigger WHERE tgname = 'trg_update_embedding_tsv'
    ) THEN
        CREATE TRIGGER trg_update_embedding_tsv
            BEFORE INSERT OR UPDATE OF chunk_text
            ON public.reuben_agent_document_embedding
            FOR EACH ROW
            EXECUTE FUNCTION public.update_embedding_tsv();
    END IF;
END;
$$;
