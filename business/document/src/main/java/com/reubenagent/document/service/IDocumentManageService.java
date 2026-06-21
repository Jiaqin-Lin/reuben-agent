package com.reubenagent.document.service;

import com.reubenagent.document.dto.DocumentStrategyConfirmDto;
import com.reubenagent.document.dto.DocumentUploadDto;
import com.reubenagent.document.vo.DocumentDetailVo;
import com.reubenagent.document.vo.DocumentStrategyConfirmVo;
import com.reubenagent.document.vo.DocumentStrategyPlanVo;
import com.reubenagent.document.vo.DocumentUploadVo;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

public interface IDocumentManageService {

    DocumentUploadVo upload(MultipartFile file, DocumentUploadDto documentUploadDto);

    /**
     * 查询文档详情。
     */
    DocumentDetailVo getDocument(Long documentId);

    /**
     * 获取文档的策略方案列表。
     */
    List<DocumentStrategyPlanVo> getPlans(Long documentId);

    /**
     * 确认策略方案并触发索引构建。
     *
     * @param dto 确认请求（documentId + planId + 可选 confirmUserId）
     * @return 确认结果（新创建的索引任务 ID 等信息）
     */
    DocumentStrategyConfirmVo confirmStrategy(DocumentStrategyConfirmDto dto);
}
