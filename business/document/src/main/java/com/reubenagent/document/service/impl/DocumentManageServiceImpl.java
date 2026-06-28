package com.reubenagent.document.service.impl;

import com.alibaba.fastjson.JSON;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.reubenagent.common.dto.PageVo;
import com.reubenagent.common.enums.DocumentManageCode;
import com.reubenagent.common.exception.DocumentException;
import com.reubenagent.document.dto.DocumentIndexBuildDto;
import com.reubenagent.document.dto.DocumentPageQueryDto;
import com.reubenagent.document.dto.DocumentStrategyConfirmDto;
import com.reubenagent.document.dto.DocumentUploadDto;
import com.reubenagent.document.entity.Document;
import com.reubenagent.document.entity.DocumentChunk;
import com.reubenagent.document.entity.DocumentParentBlock;
import com.reubenagent.document.entity.DocumentProfile;
import com.reubenagent.document.entity.DocumentStrategyPlan;
import com.reubenagent.document.entity.DocumentStrategyStep;
import com.reubenagent.document.entity.DocumentStructureNode;
import com.reubenagent.document.entity.DocumentTask;
import com.reubenagent.document.entity.DocumentTaskLog;
import com.reubenagent.document.enums.DocumentFileTypeEnum;
import com.reubenagent.document.enums.DocumentIndexStatusEnum;
import com.reubenagent.document.enums.DocumentLogLevelEnum;
import com.reubenagent.document.enums.DocumentOperatorTypeEnum;
import com.reubenagent.document.enums.DocumentParseStatusEnum;
import com.reubenagent.document.enums.DocumentPlanStatusEnum;
import com.reubenagent.document.enums.DocumentStorageTypeEnum;
import com.reubenagent.document.enums.DocumentStrategyStatusEnum;
import com.reubenagent.document.enums.DocumentTaskEventTypeEnum;
import com.reubenagent.document.enums.DocumentTaskStageEnum;
import com.reubenagent.document.enums.DocumentTaskStatusEnum;
import com.reubenagent.document.enums.DocumentTaskTypeEnum;
import com.reubenagent.document.enums.DocumentTriggerSourceEnum;
import com.reubenagent.document.mapper.IDocumentChunkMapper;
import com.reubenagent.document.mapper.IDocumentMapper;
import com.reubenagent.document.mapper.IDocumentParentBlockMapper;
import com.reubenagent.document.mapper.IDocumentProfileMapper;
import com.reubenagent.document.mapper.IDocumentStrategyPlanMapper;
import com.reubenagent.document.mapper.IDocumentStrategyStepMapper;
import com.reubenagent.document.mapper.IDocumentStructureNodeMapper;
import com.reubenagent.document.mapper.IDocumentTaskLogMapper;
import com.reubenagent.document.mapper.IDocumentTaskMapper;
import com.reubenagent.document.model.StoredObjectInfo;
import com.reubenagent.document.model.mq.DocumentIndexBuildMessage;
import com.reubenagent.document.model.mq.DocumentParseRouteMessage;
import com.reubenagent.document.mq.DocumentKafkaProducer;
import com.reubenagent.document.service.keyword.IDocumentKeywordSearchGateway;
import com.reubenagent.document.service.IDocumentManageService;
import com.reubenagent.document.service.IDocumentStorageService;
import com.reubenagent.document.service.IDocumentVectorGateway;
import com.reubenagent.document.vo.DocumentChunkDetailVo;
import com.reubenagent.document.vo.DocumentChunkVo;
import com.reubenagent.document.vo.DocumentDeleteVo;
import com.reubenagent.document.vo.DocumentDetailVo;
import com.reubenagent.document.vo.DocumentIndexBuildVo;
import com.reubenagent.document.vo.DocumentListItemVo;
import com.reubenagent.document.vo.DocumentParentBlockVo;
import com.reubenagent.document.vo.DocumentStrategyConfirmVo;
import com.reubenagent.document.vo.DocumentStrategyPlanVo;
import com.reubenagent.document.vo.DocumentTaskLogQueryVo;
import com.reubenagent.document.vo.DocumentTaskLogVo;
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
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 文档管理服务实现 —— 上传、查询、删除、策略确认、索引构建。
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
    private final IDocumentVectorGateway documentVectorGateway;
    private final IDocumentKeywordSearchGateway documentKeywordSearchGateway;

    private final IDocumentMapper documentMapper;
    private final IDocumentTaskMapper documentTaskMapper;
    private final IDocumentTaskLogMapper documentTaskLogMapper;
    private final IDocumentStrategyPlanMapper strategyPlanMapper;
    private final IDocumentStrategyStepMapper strategyStepMapper;
    private final IDocumentChunkMapper documentChunkMapper;
    private final IDocumentParentBlockMapper documentParentBlockMapper;
    private final IDocumentStructureNodeMapper structureNodeMapper;
    private final IDocumentProfileMapper documentProfileMapper;

    private final ObjectProvider<DocumentKafkaProducer> kafkaProducerProvider;
    /** Neo4j 图投影（可选） */
    private final ObjectProvider<com.reubenagent.document.service.DocumentStructureGraphProjectionService> graphProjectionProvider;
    /** 导航 ES 索引（可选） */
    private final ObjectProvider<com.reubenagent.document.service.DocumentNavigationIndexService> navigationIndexProvider;
    /** 知识路由 ES 索引（可选） */
    private final ObjectProvider<com.reubenagent.document.service.KnowledgeRouteIndexService> knowledgeRouteIndexProvider;

    // =====================================================================
    // 写入：上传 / 策略确认 / 索引构建 / 删除
    // =====================================================================

    @Override
    public DocumentUploadVo upload(MultipartFile file, DocumentUploadDto documentUploadDto) {
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

        DocumentKafkaProducer kafkaProducer = kafkaProducerProvider.getIfAvailable();
        if (kafkaProducer != null) {
            kafkaProducer.sendParseRoute(new DocumentParseRouteMessage(documentId, taskId));
        } else {
            log.debug("Kafka 不可用，跳过 parse-route 消息发送");
        }

        return documentUploadVo;
    }

    @Override
    public DocumentStrategyConfirmVo confirmStrategy(DocumentStrategyConfirmDto dto) {
        DocumentStrategyPlan plan = strategyPlanMapper.selectById(dto.getPlanId());
        if (plan == null) {
            throw new DocumentException(DocumentManageCode.PLAN_NOT_FOUND, "planId=" + dto.getPlanId());
        }
        if (!DocumentPlanStatusEnum.WAIT_CONFIRM.getCode().equals(plan.getPlanStatus())) {
            throw new DocumentException(DocumentManageCode.PLAN_STATUS_INVALID,
                    "planId=" + dto.getPlanId() + " currentStatus=" + plan.getPlanStatus() + " expected=WAIT_CONFIRM");
        }

        Document document = documentMapper.selectById(dto.getDocumentId());
        if (document == null) {
            throw new DocumentException(DocumentManageCode.DOCUMENT_NOT_FOUND, "documentId=" + dto.getDocumentId());
        }

        Long operatorId = dto.getConfirmUserId();

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

        plan.setPlanStatus(DocumentPlanStatusEnum.CONFIRMED.getCode());
        plan.setConfirmUserId(operatorId);
        plan.setConfirmTime(new java.util.Date());

        transactionTemplate.executeWithoutResult(status -> {
            documentTaskMapper.insert(task);
            documentTaskLogMapper.insert(taskLog);
            strategyPlanMapper.updateById(plan);
            documentMapper.updateById(Document.builder()
                    .id(document.getId())
                    .currentStrategyPlanId(plan.getId())
                    .strategyStatus(DocumentStrategyStatusEnum.CONFIRMED.getCode())
                    .build());
        });

        log.info("策略方案已确认: documentId={} planId={} taskId={}", dto.getDocumentId(), dto.getPlanId(), taskId);

        DocumentKafkaProducer kafkaProducer = kafkaProducerProvider.getIfAvailable();
        if (kafkaProducer != null) {
            kafkaProducer.sendIndexBuild(new DocumentIndexBuildMessage(dto.getDocumentId(), taskId, dto.getPlanId()));
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

    @Override
    public DocumentIndexBuildVo buildIndex(DocumentIndexBuildDto dto) {
        DocumentStrategyPlan plan = strategyPlanMapper.selectById(dto.getPlanId());
        if (plan == null) {
            throw new DocumentException(DocumentManageCode.PLAN_NOT_FOUND, "planId=" + dto.getPlanId());
        }
        Document document = documentMapper.selectById(dto.getDocumentId());
        if (document == null) {
            throw new DocumentException(DocumentManageCode.DOCUMENT_NOT_FOUND, "documentId=" + dto.getDocumentId());
        }

        Long taskId = uidGenerator.getUid();
        Long operatorId = dto.getOperatorId();
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
                .content("手动触发索引构建")
                .build();

        // 更新文档索引状态
        transactionTemplate.executeWithoutResult(status -> {
            documentTaskMapper.insert(task);
            documentTaskLogMapper.insert(taskLog);
            documentMapper.updateById(Document.builder()
                    .id(document.getId())
                    .indexStatus(DocumentIndexStatusEnum.BUILDING.getCode())
                    .latestIndexTaskId(taskId)
                    .build());
        });

        log.info("手动触发索引构建: documentId={} planId={} taskId={}", dto.getDocumentId(), dto.getPlanId(), taskId);

        DocumentKafkaProducer kafkaProducer = kafkaProducerProvider.getIfAvailable();
        if (kafkaProducer != null) {
            kafkaProducer.sendIndexBuild(new DocumentIndexBuildMessage(dto.getDocumentId(), taskId, dto.getPlanId()));
        } else {
            log.debug("Kafka 不可用，跳过 index-build 消息发送");
        }

        return DocumentIndexBuildVo.builder()
                .documentId(dto.getDocumentId())
                .taskId(taskId)
                .taskType(DocumentTaskTypeEnum.BUILD_INDEX.getCode())
                .taskStatus(DocumentTaskStatusEnum.NEW.getCode())
                .indexStatus(DocumentIndexStatusEnum.BUILDING.getCode())
                .build();
    }

    @Override
    public DocumentDeleteVo deleteDocument(Long documentId) {
        Document document = documentMapper.selectById(documentId);
        if (document == null) {
            throw new DocumentException(DocumentManageCode.DOCUMENT_NOT_FOUND, "documentId=" + documentId);
        }

        // 检查是否有进行中的任务
        boolean hasActiveTask = documentTaskMapper.exists(
                new LambdaQueryWrapper<DocumentTask>()
                        .eq(DocumentTask::getDocumentId, documentId)
                        .in(DocumentTask::getTaskStatus, DocumentTaskStatusEnum.NEW.getCode(), DocumentTaskStatusEnum.RUNNING.getCode()));
        if (hasActiveTask) {
            throw new DocumentException(DocumentManageCode.DOCUMENT_HAS_ACTIVE_TASK,
                    "documentId=" + documentId);
        }

        boolean storageCleaned = false;
        boolean vectorCleaned = false;
        boolean keywordCleaned = false;

        // 级联 1：MinIO 存储（失败不阻断）
        try {
            documentStorageService.deleteByDocumentId(documentId);
            storageCleaned = true;
        } catch (Exception e) {
            log.warn("MinIO 清理失败 documentId={}", documentId, e);
        }

        // 级联 2：PGVector 向量库（失败不阻断）
        try {
            documentVectorGateway.deleteByDocumentId(documentId);
            vectorCleaned = true;
        } catch (Exception e) {
            log.warn("PGVector 清理失败 documentId={}", documentId, e);
        }

        // 级联 3：ES 关键字索引（失败不阻断）
        try {
            documentKeywordSearchGateway.deleteByDocumentId(documentId);
            keywordCleaned = true;
        } catch (Exception e) {
            log.warn("ES 清理失败 documentId={}", documentId, e);
        }

        // 级联 3.1：Neo4j 图数据 + 导航/路由 ES 索引（失败不阻断）
        cleanupStructureGraphAndIndices(documentId);

        // 级联 4：DB 事务内软删除所有关联数据
        String documentName = document.getDocumentName();
        transactionTemplate.executeWithoutResult(status -> {
            documentProfileMapper.delete(new LambdaQueryWrapper<DocumentProfile>()
                    .eq(DocumentProfile::getDocumentId, documentId));
            documentChunkMapper.delete(new LambdaQueryWrapper<DocumentChunk>()
                    .eq(DocumentChunk::getDocumentId, documentId));
            documentParentBlockMapper.delete(new LambdaQueryWrapper<DocumentParentBlock>()
                    .eq(DocumentParentBlock::getDocumentId, documentId));
            structureNodeMapper.delete(new LambdaQueryWrapper<DocumentStructureNode>()
                    .eq(DocumentStructureNode::getDocumentId, documentId));
            strategyStepMapper.delete(new LambdaQueryWrapper<DocumentStrategyStep>()
                    .eq(DocumentStrategyStep::getDocumentId, documentId));
            strategyPlanMapper.delete(new LambdaQueryWrapper<DocumentStrategyPlan>()
                    .eq(DocumentStrategyPlan::getDocumentId, documentId));
            documentTaskLogMapper.delete(new LambdaQueryWrapper<DocumentTaskLog>()
                    .eq(DocumentTaskLog::getDocumentId, documentId));
            documentTaskMapper.delete(new LambdaQueryWrapper<DocumentTask>()
                    .eq(DocumentTask::getDocumentId, documentId));
            // document (软删)
            documentMapper.deleteById(documentId);
        });

        log.info("文档已级联删除 documentId={} documentName={} storage={} vector={} keyword={}",
                documentId, documentName, storageCleaned, vectorCleaned, keywordCleaned);

        return DocumentDeleteVo.builder()
                .documentId(documentId)
                .documentName(documentName)
                .storageCleaned(storageCleaned)
                .vectorCleaned(vectorCleaned)
                .keywordCleaned(keywordCleaned)
                .build();
    }

    /** 清理文档的结构图与导航/路由索引（失败不阻断删除主流程） */
    private void cleanupStructureGraphAndIndices(Long documentId) {
        try {
            com.reubenagent.document.service.DocumentStructureGraphProjectionService projection =
                    graphProjectionProvider.getIfAvailable();
            if (projection != null) {
                projection.deleteByDocumentId(documentId);
            }
        } catch (Exception e) {
            log.warn("Neo4j 图清理失败 documentId={}", documentId, e);
        }
        try {
            com.reubenagent.document.service.DocumentNavigationIndexService navigation =
                    navigationIndexProvider.getIfAvailable();
            if (navigation != null) {
                navigation.deleteByDocumentId(documentId);
            }
        } catch (Exception e) {
            log.warn("导航索引清理失败 documentId={}", documentId, e);
        }
        try {
            com.reubenagent.document.service.KnowledgeRouteIndexService routeIndex =
                    knowledgeRouteIndexProvider.getIfAvailable();
            if (routeIndex != null) {
                routeIndex.deleteDocumentRoute(documentId);
            }
        } catch (Exception e) {
            log.warn("知识路由索引清理失败 documentId={}", documentId, e);
        }
    }

    // =====================================================================
    // 查询
    // =====================================================================

    @Override
    public PageVo<DocumentListItemVo> pageQuery(DocumentPageQueryDto dto) {
        int pageNo = dto.getPageNo() != null ? dto.getPageNo() : 1;
        int pageSize = dto.getPageSize() != null ? dto.getPageSize() : 10;

        LambdaQueryWrapper<Document> wrapper = new LambdaQueryWrapper<Document>();
        if (StringUtils.isNotBlank(dto.getKeyword())) {
            wrapper.and(w -> w.like(Document::getDocumentName, dto.getKeyword())
                    .or().like(Document::getOriginalFileName, dto.getKeyword()));
        }
        wrapper.orderByDesc(Document::getCreateTime)
                .orderByDesc(Document::getId);

        Page<Document> page = documentMapper.selectPage(new Page<>(pageNo, pageSize), wrapper);

        // 批量查询最近任务
        List<Long> documentIds = page.getRecords().stream()
                .map(Document::getId).collect(Collectors.toList());
        Map<Long, DocumentTask> latestTaskMap = documentIds.isEmpty()
                ? Collections.emptyMap()
                : resolveLatestTaskMap(documentIds);

        Long total = page.getTotal();
        List<DocumentListItemVo> records = page.getRecords().stream()
                .map(doc -> toListItem(doc, latestTaskMap.get(doc.getId())))
                .collect(Collectors.toList());

        return PageVo.of(total, pageNo, pageSize, records);
    }

    @Override
    public DocumentDetailVo getDocument(Long documentId) {
        Document doc = documentMapper.selectById(documentId);
        if (doc == null) {
            throw new DocumentException(DocumentManageCode.DOCUMENT_NOT_FOUND, "documentId=" + documentId);
        }
        return DocumentDetailVo.builder()
                .documentId(doc.getId())
                .documentName(doc.getDocumentName())
                .originalFileName(doc.getOriginalFileName())
                .fileType(doc.getFileType())
                .fileSize(doc.getFileSize())
                .parseStatus(doc.getParseStatus())
                .strategyStatus(doc.getStrategyStatus())
                .indexStatus(doc.getIndexStatus())
                .charCount(doc.getCharCount())
                .structureLevel(doc.getStructureLevel())
                .contentQualityLevel(doc.getContentQualityLevel())
                .createTime(doc.getCreateTime())
                .build();
    }

    @Override
    public List<DocumentStrategyPlanVo> getPlans(Long documentId) {
        List<DocumentStrategyPlan> plans = strategyPlanMapper.selectList(
                new LambdaQueryWrapper<DocumentStrategyPlan>()
                        .eq(DocumentStrategyPlan::getDocumentId, documentId)
                        .orderByDesc(DocumentStrategyPlan::getCreateTime));
        return plans.stream()
                .map(p -> DocumentStrategyPlanVo.builder()
                        .planId(p.getId())
                        .documentId(p.getDocumentId())
                        .planVersion(p.getPlanVersion())
                        .planSource(p.getPlanSource())
                        .planStatus(p.getPlanStatus())
                        .strategyCount(p.getStrategyCount())
                        .strategySnapshot(p.getStrategySnapshot())
                        .recommendReason(p.getRecommendReason())
                        .build())
                .collect(Collectors.toList());
    }

    @Override
    public PageVo<DocumentChunkVo> listChunks(Long documentId, Long taskId, int pageNo, int pageSize) {
        Long effectiveTaskId = resolveEffectiveIndexTaskId(documentId, taskId);

        LambdaQueryWrapper<DocumentChunk> wrapper = new LambdaQueryWrapper<DocumentChunk>()
                .eq(DocumentChunk::getDocumentId, documentId)
                .eq(DocumentChunk::getTaskId, effectiveTaskId)
                .orderByAsc(DocumentChunk::getChunkNo)
                .orderByAsc(DocumentChunk::getId);

        Page<DocumentChunk> page = documentChunkMapper.selectPage(new Page<>(pageNo, pageSize), wrapper);

        // 批量查询 parent blocks
        List<Long> parentBlockIds = page.getRecords().stream()
                .map(DocumentChunk::getParentBlockId).filter(Objects::nonNull).distinct()
                .collect(Collectors.toList());
        Map<Long, DocumentParentBlock> parentBlockMap = parentBlockIds.isEmpty()
                ? Collections.emptyMap()
                : documentParentBlockMapper.selectBatchIds(parentBlockIds).stream()
                        .collect(Collectors.toMap(DocumentParentBlock::getId, pb -> pb));

        List<DocumentChunkVo> records = page.getRecords().stream()
                .map(c -> toChunkVo(c, parentBlockMap.get(c.getParentBlockId())))
                .collect(Collectors.toList());

        return PageVo.of(page.getTotal(), pageNo, pageSize, records);
    }

    @Override
    public DocumentChunkDetailVo getChunkDetail(Long chunkId) {
        DocumentChunk chunk = documentChunkMapper.selectById(chunkId);
        if (chunk == null) {
            throw new DocumentException(DocumentManageCode.CHUNK_NOT_FOUND, "chunkId=" + chunkId);
        }

        // 父块
        DocumentParentBlock parentBlock = null;
        if (chunk.getParentBlockId() != null) {
            parentBlock = documentParentBlockMapper.selectById(chunk.getParentBlockId());
        }

        // 同父兄弟 chunks
        List<DocumentChunkVo> siblingChunks = Collections.emptyList();
        if (chunk.getParentBlockId() != null) {
            List<DocumentChunk> siblings = documentChunkMapper.selectList(
                    new LambdaQueryWrapper<DocumentChunk>()
                            .eq(DocumentChunk::getParentBlockId, chunk.getParentBlockId())
                            .orderByAsc(DocumentChunk::getChunkNo)
                            .orderByAsc(DocumentChunk::getId));
            Map<Long, DocumentParentBlock> pbMap = parentBlock != null
                    ? Map.of(parentBlock.getId(), parentBlock)
                    : Collections.emptyMap();
            siblingChunks = siblings.stream()
                    .map(c -> toChunkVo(c, pbMap.get(c.getParentBlockId())))
                    .collect(Collectors.toList());
        }

        return DocumentChunkDetailVo.builder()
                .documentId(chunk.getDocumentId())
                .taskId(chunk.getTaskId())
                .planId(chunk.getPlanId())
                .chunk(toChunkVo(chunk, parentBlock))
                .parentBlock(parentBlock != null ? toParentBlockVo(parentBlock) : null)
                .siblingChunks(siblingChunks)
                .build();
    }

    @Override
    public DocumentTaskLogQueryVo getTaskLogs(Long taskId, int pageNo, int pageSize) {
        DocumentTask task = documentTaskMapper.selectById(taskId);
        if (task == null) {
            throw new DocumentException(DocumentManageCode.TASK_NOT_FOUND, "taskId=" + taskId);
        }

        LambdaQueryWrapper<DocumentTaskLog> wrapper = new LambdaQueryWrapper<DocumentTaskLog>()
                .eq(DocumentTaskLog::getTaskId, taskId)
                .orderByAsc(DocumentTaskLog::getCreateTime)
                .orderByAsc(DocumentTaskLog::getId);

        // 非分页——日志量通常不大，全量返回
        List<DocumentTaskLog> taskLogs = documentTaskLogMapper.selectList(wrapper);

        List<DocumentTaskLogVo> logVos = taskLogs.stream()
                .map(tl -> DocumentTaskLogVo.builder()
                        .id(tl.getId())
                        .stageType(tl.getStageType())
                        .eventType(tl.getEventType())
                        .logLevel(tl.getLogLevel())
                        .content(tl.getContent())
                        .detailJson(tl.getDetailJson())
                        .createTime(tl.getCreateTime())
                        .build())
                .collect(Collectors.toList());

        return DocumentTaskLogQueryVo.builder()
                .taskId(task.getId())
                .documentId(task.getDocumentId())
                .taskType(task.getTaskType())
                .taskStatus(task.getTaskStatus())
                .currentStage(task.getCurrentStage())
                .startTime(task.getStartTime())
                .finishTime(task.getFinishTime())
                .costMillis(task.getCostMillis())
                .errorCode(task.getErrorCode())
                .errorMsg(task.getErrorMsg())
                .total((long) logVos.size())
                .logs(logVos)
                .build();
    }

    // =====================================================================
    // 私有映射方法
    // =====================================================================

    private DocumentListItemVo toListItem(Document doc, DocumentTask latestTask) {
        return DocumentListItemVo.builder()
                .documentId(doc.getId())
                .documentName(doc.getDocumentName())
                .originalFileName(doc.getOriginalFileName())
                .fileType(doc.getFileType())
                .fileSize(doc.getFileSize())
                .charCount(doc.getCharCount())
                .tokenCount(doc.getTokenCount())
                .parseStatus(doc.getParseStatus())
                .strategyStatus(doc.getStrategyStatus())
                .indexStatus(doc.getIndexStatus())
                .parseErrorMsg(doc.getParseErrorMsg())
                .knowledgeScopeCode(doc.getKnowledgeScopeCode())
                .knowledgeScopeName(doc.getKnowledgeScopeName())
                .businessCategory(doc.getBusinessCategory())
                .documentTags(doc.getDocumentTags())
                .currentPlanId(doc.getCurrentStrategyPlanId())
                .latestIndexTaskId(doc.getLatestIndexTaskId())
                .latestTaskId(latestTask != null ? latestTask.getId() : null)
                .latestTaskType(latestTask != null ? latestTask.getTaskType() : null)
                .latestTaskStatus(latestTask != null ? latestTask.getTaskStatus() : null)
                .createTime(doc.getCreateTime())
                .updateTime(doc.getUpdateTime())
                .build();
    }

    private DocumentChunkVo toChunkVo(DocumentChunk chunk, DocumentParentBlock parentBlock) {
        return DocumentChunkVo.builder()
                .chunkId(chunk.getId())
                .parentBlockId(chunk.getParentBlockId())
                .parentBlockNo(parentBlock != null ? parentBlock.getParentNo() : null)
                .parentChildCount(parentBlock != null ? parentBlock.getChildCount() : null)
                .parentStartChunkNo(parentBlock != null ? parentBlock.getStartChunkNo() : null)
                .parentEndChunkNo(parentBlock != null ? parentBlock.getEndChunkNo() : null)
                .chunkNo(chunk.getChunkNo())
                .sectionPath(chunk.getSectionPath())
                .sourceType(chunk.getSourceType())
                .charCount(chunk.getCharCount())
                .tokenCount(chunk.getTokenCount())
                .vectorStatus(chunk.getVectorStatus())
                .chunkText(chunk.getChunkText())
                .build();
    }

    private DocumentParentBlockVo toParentBlockVo(DocumentParentBlock pb) {
        return DocumentParentBlockVo.builder()
                .parentBlockId(pb.getId())
                .parentBlockNo(pb.getParentNo())
                .sectionPath(pb.getSectionPath())
                .sourceType(pb.getSourceType())
                .charCount(pb.getCharCount())
                .childCount(pb.getChildCount())
                .startChunkNo(pb.getStartChunkNo())
                .endChunkNo(pb.getEndChunkNo())
                .parentText(pb.getParentText())
                .build();
    }

    // =====================================================================
    // 私有辅助
    // =====================================================================

    /**
     * 为一批文档 ID 解析最近的任务（按 createTime DESC，每个文档取第一个）。
     */
    private Map<Long, DocumentTask> resolveLatestTaskMap(List<Long> documentIds) {
        // 简单实现：对每个 documentId 查最新一条任务
        // 用于列表页展示的快捷信息，非热点路径，逐个查可接受
        return documentIds.stream()
                .map(docId -> {
                    List<DocumentTask> tasks = documentTaskMapper.selectList(
                            new LambdaQueryWrapper<DocumentTask>()
                                    .eq(DocumentTask::getDocumentId, docId)
                                    .orderByDesc(DocumentTask::getCreateTime)
                                    .last("LIMIT 1"));
                    return tasks.isEmpty() ? null : tasks.get(0);
                })
                .filter(Objects::nonNull)
                .collect(Collectors.toMap(DocumentTask::getDocumentId, t -> t, (a, b) -> a));
    }

    /**
     * 解析有效的索引任务 ID：优先用传入的 taskId，否则用文档记录的 latestIndexTaskId。
     */
    private Long resolveEffectiveIndexTaskId(Long documentId, Long taskId) {
        if (taskId != null) {
            return taskId;
        }
        Document document = documentMapper.selectById(documentId);
        if (document != null && document.getLatestIndexTaskId() != null) {
            return document.getLatestIndexTaskId();
        }
        // 最终回退：查最近一次 BUILD_INDEX 任务
        List<DocumentTask> tasks = documentTaskMapper.selectList(
                new LambdaQueryWrapper<DocumentTask>()
                        .eq(DocumentTask::getDocumentId, documentId)
                        .eq(DocumentTask::getTaskType, DocumentTaskTypeEnum.BUILD_INDEX.getCode())
                        .orderByDesc(DocumentTask::getCreateTime)
                        .last("LIMIT 1"));
        if (!tasks.isEmpty()) {
            return tasks.get(0).getId();
        }
        throw new DocumentException(DocumentManageCode.TASK_NOT_FOUND,
                "未找到文档的索引任务 documentId=" + documentId);
    }

    private byte[] getFileBytes(MultipartFile file) {
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
