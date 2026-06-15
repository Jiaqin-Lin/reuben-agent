package com.reubenagent.document.service.impl;

import com.alibaba.fastjson.JSON;
import com.reubenagent.common.enums.DocumentManageCode;
import com.reubenagent.common.exception.DocumentException;
import com.reubenagent.document.dto.DocumentUploadDto;
import com.reubenagent.document.entity.Document;
import com.reubenagent.document.entity.DocumentTask;
import com.reubenagent.document.entity.DocumentTaskLog;
import com.reubenagent.document.enums.*;
import com.reubenagent.document.mapper.IDocumentMapper;
import com.reubenagent.document.mapper.IDocumentTaskLogMapper;
import com.reubenagent.document.mapper.IDocumentTaskMapper;
import com.reubenagent.document.model.StoredObjectInfo;
import com.reubenagent.document.service.IDocumentManageService;
import com.reubenagent.document.service.IDocumentStorageService;
import com.reubenagent.document.vo.DocumentUploadVo;
import com.reubenagent.framework.uid.UidGenerator;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.Map;

@Slf4j
@AllArgsConstructor
@Service
public class DocumentManageServiceImpl implements IDocumentManageService {

    private final UidGenerator uidGenerator;
    private final TransactionTemplate transactionTemplate;

    private final IDocumentStorageService documentStorageService;

    private final IDocumentMapper documentMapper;
    private final IDocumentTaskMapper documentTaskMapper;
    private final IDocumentTaskLogMapper documentTaskLogMapper;

    @Override
    public DocumentUploadVo upload(MultipartFile file, DocumentUploadDto documentUploadDto) {
        if (file == null || file.isEmpty()) {
            throw new DocumentException(DocumentManageCode.EMPTY_FILE);
        }

        String originalFileName = file.getOriginalFilename();
        if (StringUtils.isBlank(originalFileName)) {
            throw new DocumentException(DocumentManageCode.EMPTY_ORIGINAL_FILE_NAME);
        }
        DocumentFileTypeEnum fileType = DocumentFileTypeEnum.getEnumFromFileName(originalFileName);
        if (fileType == null) {
            throw new DocumentException(DocumentManageCode.UNSUPPORTED_FILE_TYPE, "当前仅支持 PDF / DOC / DOCX / TXT / MD / HTML");
        }

        byte[] fileBytes = getFileBytes(file);
        Long documentId = uidGenerator.getUid();
        StoredObjectInfo storedObjectInfo = documentStorageService.uploadOriginalFile(documentId, originalFileName, fileBytes, file.getContentType());

        Document document = Document.builder()
                .id(documentId)
                .documentName(StringUtils.isNotBlank(documentUploadDto.getDocumentName()) ? documentUploadDto.getDocumentName() : originalFileName)
                .originalFileName(originalFileName)
                .fileType(fileType.getCode())
                .mediaType(file.getContentType())
                .fileSize((long) fileBytes.length)
                .storageType(DocumentStorageTypeEnum.MINIO.getCode())
                .bucketName(storedObjectInfo.getBucketName())
                .objectName(storedObjectInfo.getObjectName())
                .objectUrl(storedObjectInfo.getObjectUrl())
                .parseStatus(DocumentParseStatusEnum.WAIT_TO_PARSE.getCode())
                .strategyStatus(DocumentStrategyStatusEnum.WAIT_TO_RECOMMEND.getCode())
                .indexStatus(DocumentIndexStatusEnum.WAIT_TO_BUILD.getCode())
                .charCount(0)
                .tokenCount(0)
                .knowledgeScopeCode(StringUtils.trimToNull(documentUploadDto.getKnowledgeScopeCode()))
                .knowledgeScopeName(StringUtils.trimToNull(documentUploadDto.getKnowledgeScopeName()))
                .businessCategory(StringUtils.trimToNull(documentUploadDto.getBusinessCategory()))
                .documentTags(StringUtils.trimToNull(documentUploadDto.getDocumentTags()))
                .build();

        Long taskId = uidGenerator.getUid();
        Long operatorId = parseOptionalLong(documentUploadDto.getOperatorId());
        DocumentTask task = DocumentTask.builder()
                .id(taskId)
                .documentId(documentId)
                .taskType(DocumentTaskTypeEnum.PARSE_ROUTE.getCode())
                .taskStatus(DocumentTaskStatusEnum.NEW.getCode())
                .currentStage(DocumentTaskStageEnum.FILE_UPLOAD.getCode())
                .triggerSource(resolveTriggerSource(operatorId))
                .retryCount(0)
                .build();

        Long taskLogId = uidGenerator.getUid();
        DocumentTaskLog taskLog = DocumentTaskLog.builder()
                .id(taskLogId)
                .taskId(taskId)
                .documentId(documentId)
                .stageType(DocumentTaskStageEnum.FILE_UPLOAD.getCode())
                .eventType(DocumentTaskEventTypeEnum.COMPLETE.getCode())
                .logLevel(DocumentLogLevelEnum.INFO.getCode())
                .operatorType(resolveOperatorType(operatorId))
                .operatorId(operatorId)
                .content("文件上传完成，已进入解析与策略推荐队列")
                .detailJson(JSON.toJSONString(Map.of("originalFileName", originalFileName, "fileSize", fileBytes.length)))
                .build();

        DocumentUploadVo documentUploadVo = transactionTemplate.execute(status -> {
            documentMapper.insert(document);
            documentTaskMapper.insert(task);
            documentTaskLogMapper.insert(taskLog);

            return new DocumentUploadVo(documentId, taskId, document.getDocumentName(), document.getParseStatus(), document.getStrategyStatus(), document.getIndexStatus());
        });

        return documentUploadVo;
    }

    private byte[] getFileBytes (MultipartFile file){
        try {
            return file.getBytes();
        } catch (IOException e) {
            throw new DocumentException(DocumentManageCode.READ_FILE_FAIL, e.getMessage(), e);
        }
    }

    private Long parseOptionalLong(String value) {
        if (StringUtils.isBlank(value)) {
            return null;
        }
        try {
            Long parsedValue = Long.valueOf(value);
            return parsedValue > 0 ? parsedValue : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private Integer resolveTriggerSource(Long operatorId) {
        return operatorId == null ? DocumentTriggerSourceEnum.SYSTEM.getCode() : DocumentTriggerSourceEnum.USER.getCode();
    }

    private Integer resolveOperatorType(Long operatorId) {
        return operatorId == null ? DocumentOperatorTypeEnum.SYSTEM.getCode() : DocumentOperatorTypeEnum.USER.getCode();
    }
}