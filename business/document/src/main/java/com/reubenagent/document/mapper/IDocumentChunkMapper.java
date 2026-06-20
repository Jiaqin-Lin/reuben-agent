package com.reubenagent.document.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.reubenagent.document.entity.DocumentChunk;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/**
 * 文档切块 Mapper —— 映射 {@code reuben_agent_document_chunk} 表。
 *
 * @author reuben
 * @since 2026-06-20
 */
@Mapper
public interface IDocumentChunkMapper extends BaseMapper<DocumentChunk> {

    /**
     * 将指定任务下所有待向量化的 chunk 批量标记为失败。
     * 仅在 catch 块中使用，区分"向量化失败的 chunk"和"已成功但后续阶段失败的 chunk"。
     *
     * @param taskId 索引任务id
     * @return 受影响行数
     */
    @Update("UPDATE reuben_agent_document_chunk SET vector_status = 4 WHERE task_id = #{taskId} AND vector_status = 1")
    int markVectorFailedByTaskId(@Param("taskId") Long taskId);
}
