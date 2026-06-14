package com.reubenagent.document.service;

import com.reubenagent.document.dto.DocumentUploadDto;
import com.reubenagent.document.vo.DocumentUploadVo;
import org.springframework.web.multipart.MultipartFile;

public interface IDocumentManageService {

    DocumentUploadVo upload(MultipartFile file, DocumentUploadDto documentUploadDto);
}
