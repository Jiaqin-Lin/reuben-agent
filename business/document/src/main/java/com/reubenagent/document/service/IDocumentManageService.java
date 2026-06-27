package com.reubenagent.document.service;

import com.reubenagent.common.dto.PageVo;
import com.reubenagent.document.dto.DocumentIndexBuildDto;
import com.reubenagent.document.dto.DocumentPageQueryDto;
import com.reubenagent.document.dto.DocumentStrategyConfirmDto;
import com.reubenagent.document.dto.DocumentUploadDto;
import com.reubenagent.document.vo.DocumentChunkDetailVo;
import com.reubenagent.document.vo.DocumentChunkVo;
import com.reubenagent.document.vo.DocumentDeleteVo;
import com.reubenagent.document.vo.DocumentDetailVo;
import com.reubenagent.document.vo.DocumentIndexBuildVo;
import com.reubenagent.document.vo.DocumentListItemVo;
import com.reubenagent.document.vo.DocumentStrategyConfirmVo;
import com.reubenagent.document.vo.DocumentStrategyPlanVo;
import com.reubenagent.document.vo.DocumentTaskLogQueryVo;
import com.reubenagent.document.vo.DocumentUploadVo;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

public interface IDocumentManageService {

    // ======================== 写入 ========================

    DocumentUploadVo upload(MultipartFile file, DocumentUploadDto documentUploadDto);

    /**
     * 确认策略方案并触发索引构建。
     */
    DocumentStrategyConfirmVo confirmStrategy(DocumentStrategyConfirmDto dto);

    /**
     * 手动触发索引构建 —— 复用已有策略方案直接创建 BUILD_INDEX 任务。
     */
    DocumentIndexBuildVo buildIndex(DocumentIndexBuildDto dto);

    /**
     * 级联删除文档 —— MinIO → PGVector → ES → DB。
     */
    DocumentDeleteVo deleteDocument(Long documentId);

    // ======================== 查询 ========================

    /**
     * 文档分页列表查询。
     */
    PageVo<DocumentListItemVo> pageQuery(DocumentPageQueryDto dto);

    /**
     * 查询文档详情。
     */
    DocumentDetailVo getDocument(Long documentId);

    /**
     * 获取文档的策略方案列表。
     */
    List<DocumentStrategyPlanVo> getPlans(Long documentId);

    /**
     * 获取文档的 Chunk 列表（按创建顺序）。
     *
     * @param documentId 文档ID
     * @param taskId     可选的任务ID过滤，为 null 时取最近一次索引任务
     */
    PageVo<DocumentChunkVo> listChunks(Long documentId, Long taskId, int pageNo, int pageSize);

    /**
     * 查询 Chunk 详情 —— 含父块信息和同父兄弟 chunks。
     */
    DocumentChunkDetailVo getChunkDetail(Long chunkId);

    /**
     * 查询任务日志。
     */
    DocumentTaskLogQueryVo getTaskLogs(Long taskId, int pageNo, int pageSize);
}
