package com.reubenagent.document.service.impl;

import com.alibaba.fastjson.JSON;
import com.reubenagent.common.enums.DocumentManageCode;
import com.reubenagent.common.exception.DocumentException;
import com.reubenagent.document.dto.DocumentStrategyConfirmDto;
import com.reubenagent.document.dto.DocumentUploadDto;
import com.reubenagent.document.entity.Document;
import com.reubenagent.document.entity.DocumentStrategyPlan;
import com.reubenagent.document.entity.DocumentTask;
import com.reubenagent.document.entity.DocumentTaskLog;
import com.reubenagent.document.enums.*;
import com.reubenagent.document.mapper.IDocumentMapper;
import com.reubenagent.document.mapper.IDocumentStrategyPlanMapper;
import com.reubenagent.document.mapper.IDocumentTaskLogMapper;
import com.reubenagent.document.mapper.IDocumentTaskMapper;
import com.reubenagent.document.model.StoredObjectInfo;
import com.reubenagent.document.model.mq.DocumentIndexBuildMessage;
import com.reubenagent.document.model.mq.DocumentParseRouteMessage;
import com.reubenagent.document.mq.DocumentKafkaProducer;
import com.reubenagent.document.service.IDocumentManageService;
import com.reubenagent.document.service.IDocumentStorageService;
import com.reubenagent.document.vo.DocumentStrategyConfirmVo;
import com.reubenagent.document.vo.DocumentUploadVo;
import com.reubenagent.framework.uid.UidGenerator;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.Map;

/**
 * 文档管理服务实现 —— 上传文档到 MinIO 并创建解析-策略-索引异步任务。
 *
 * <p>上传流程：校验文件 → 生成雪花 ID → 上传 MinIO → 事务写入 Document/Task/TaskLog 三表。</p>
 *
 * @author reuben
 * @since 2026-06-14
 */
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
    private final IDocumentStrategyPlanMapper strategyPlanMapper;

    private final ObjectProvider<DocumentKafkaProducer> kafkaProducerProvider;

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

        log.info("文档上传完成 documentId={} taskId={} fileName={}", documentId, taskId, originalFileName);

        // 事务提交后同步投递 Kafka 解析路由消息
        DocumentKafkaProducer kafkaProducer = kafkaProducerProvider.getIfAvailable();
        if (kafkaProducer != null) {
            kafkaProducer.sendParseRoute(new DocumentParseRouteMessage(documentId, taskId));
        } else {
            log.debug("Kafka 不可用，跳过 parse-route 消息发送");
        }

        return documentUploadVo;
    }

    /**
     * 确认策略方案并触发索引构建。
     *
     * <p>流程：校验收敛 → 创建 BUILD_INDEX 任务 → 更新方案为已确认 → 投递 Kafka 索引构建消息。</p>
     */
    @Override
    public DocumentStrategyConfirmVo confirmStrategy(DocumentStrategyConfirmDto dto) {
        // 校验方案存在且状态为待确认
        DocumentStrategyPlan plan = strategyPlanMapper.selectById(dto.getPlanId());
        if (plan == null) {
            throw new DocumentException(DocumentManageCode.PLAN_NOT_FOUND,
                    "planId=" + dto.getPlanId());
        }
        if (!DocumentPlanStatusEnum.WAIT_CONFIRM.getCode().equals(plan.getPlanStatus())) {
            throw new DocumentException(DocumentManageCode.PLAN_STATUS_INVALID,
                    "planId=" + dto.getPlanId() + " currentStatus=" + plan.getPlanStatus() + " expected=WAIT_CONFIRM");
        }

        Document document = documentMapper.selectById(dto.getDocumentId());
        if (document == null) {
            throw new DocumentException(DocumentManageCode.PLAN_NOT_FOUND,
                    "documentId=" + dto.getDocumentId());
        }

        Long operatorId = dto.getConfirmUserId();

        // 创建索引构建任务
        Long taskId = uidGenerator.getUid();
        DocumentTask task = DocumentTask.builder()
                .id(taskId)
                .documentId(dto.getDocumentId())
                .strategyPlanId(dto.getPlanId())
                .taskType(DocumentTaskTypeEnum.BUILD_INDEX.getCode())
                .taskStatus(DocumentTaskStatusEnum.NEW.getCode())
                .currentStage(DocumentTaskStageEnum.STRATEGY_CONFIRM.getCode())
                .triggerSource(resolveTriggerSource(operatorId))
                .retryCount(0)
                .build();

        // 任务日志
        Long taskLogId = uidGenerator.getUid();
        DocumentTaskLog taskLog = DocumentTaskLog.builder()
                .id(taskLogId)
                .taskId(taskId)
                .documentId(dto.getDocumentId())
                .stageType(DocumentTaskStageEnum.STRATEGY_CONFIRM.getCode())
                .eventType(DocumentTaskEventTypeEnum.COMPLETE.getCode())
                .logLevel(DocumentLogLevelEnum.INFO.getCode())
                .operatorType(resolveOperatorType(operatorId))
                .operatorId(operatorId)
                .content("策略方案已确认，已投递索引构建任务")
                .build();

        // 更新方案状态为已确认
        plan.setPlanStatus(DocumentPlanStatusEnum.CONFIRMED.getCode());
        plan.setConfirmUserId(operatorId);
        plan.setConfirmTime(new java.util.Date());

        // 事务写入任务 + 更新方案
        transactionTemplate.executeWithoutResult(status -> {
            documentTaskMapper.insert(task);
            documentTaskLogMapper.insert(taskLog);
            strategyPlanMapper.updateById(plan);

            // 更新文档的 currentStrategyPlanId 和 strategyStatus
            documentMapper.updateById(Document.builder()
                    .id(document.getId())
                    .currentStrategyPlanId(plan.getId())
                    .strategyStatus(DocumentStrategyStatusEnum.RECOMMENDED.getCode())
                    .build());
        });

        log.info("策略方案已确认: documentId={} planId={} taskId={}", dto.getDocumentId(), dto.getPlanId(), taskId);

        // 投递 Kafka 索引构建消息（Kafka 不可用时跳过）
        DocumentKafkaProducer kafkaProducer = kafkaProducerProvider.getIfAvailable();
        if (kafkaProducer != null) {
            kafkaProducer.sendIndexBuild(new DocumentIndexBuildMessage(
                    dto.getDocumentId(), taskId, dto.getPlanId()));
        } else {
            log.debug("Kafka 不可用，跳过 index-build 消息发送");
        }

        return DocumentStrategyConfirmVo.builder()
                .taskId(taskId)
                .planId(dto.getPlanId())
                .documentId(dto.getDocumentId())
                .planStatus(DocumentPlanStatusEnum.CONFIRMED.getCode())
                .build();
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