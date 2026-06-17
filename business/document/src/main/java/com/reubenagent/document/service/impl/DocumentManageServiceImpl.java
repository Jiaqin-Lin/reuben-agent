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

    /**
     * 上传文档并创建解析任务。
     *
     * <p>流程：校验文件 → 生成 ID → 上传 MinIO → 在事务中写入 Document/Task/TaskLog 三表。</p>
     *
     * @param file               上传的文件
     * @param documentUploadDto  上传元数据（可为 null，此时使用默认值）
     * @return 上传结果 VO，含 documentId、taskId 和初始状态
     */
    @Override
    public DocumentUploadVo upload(MultipartFile file, DocumentUploadDto documentUploadDto) {
        // 防御性编程：调用方（Controller）已保证非 null，但保留守卫以防其他调用方
        if (documentUploadDto == null) {
            documentUploadDto = new DocumentUploadDto();
        }
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

    /**
     * 从 MultipartFile 安全读取字节数组。
     *
     * @param file 上传文件
     * @return 文件字节数组
     * @throws DocumentException 读取失败时抛出
     */
    private byte[] getFileBytes(MultipartFile file) {
        try {
            return file.getBytes();
        } catch (IOException e) {
            throw new DocumentException(DocumentManageCode.READ_FILE_FAIL, e.getMessage(), e);
        }
    }

    /**
     * 将字符串解析为可选的 Long 型操作人 ID。
     *
     * @param value 字符串形式的 ID
     * @return 解析后的正 Long 值，空白/无效/非正数时返回 null
     */
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

    /**
     * 根据操作人 ID 判断任务触发来源。
     *
     * @param operatorId 操作人 ID，null 时返回 SYSTEM
     * @return 触发来源枚举 code
     */
    private Integer resolveTriggerSource(Long operatorId) {
        return operatorId == null ? DocumentTriggerSourceEnum.SYSTEM.getCode() : DocumentTriggerSourceEnum.USER.getCode();
    }

    /**
     * 根据操作人 ID 判断操作者类型。
     *
     * @param operatorId 操作人 ID，null 时返回 SYSTEM
     * @return 操作者类型枚举 code
     */
    private Integer resolveOperatorType(Long operatorId) {
        return operatorId == null ? DocumentOperatorTypeEnum.SYSTEM.getCode() : DocumentOperatorTypeEnum.USER.getCode();
    }
}