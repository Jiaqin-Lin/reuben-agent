package com.reubenagent.document.service.impl;

import com.alibaba.fastjson.JSON;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.reubenagent.common.enums.DocumentManageCode;
import com.reubenagent.document.entity.Document;
import com.reubenagent.document.entity.DocumentChunk;
import com.reubenagent.document.entity.DocumentParentBlock;
import com.reubenagent.document.entity.DocumentStrategyPlan;
import com.reubenagent.document.entity.DocumentStrategyStep;
import com.reubenagent.document.entity.DocumentStructureNode;
import com.reubenagent.document.entity.DocumentTask;
import com.reubenagent.document.entity.DocumentTaskLog;
import com.reubenagent.document.enums.DocumentChunkSourceTypeEnum;
import com.reubenagent.document.enums.DocumentFileTypeEnum;
import com.reubenagent.document.enums.DocumentIndexStatusEnum;
import com.reubenagent.document.enums.DocumentLogLevelEnum;
import com.reubenagent.document.enums.DocumentOperatorTypeEnum;
import com.reubenagent.document.enums.DocumentParseStatusEnum;
import com.reubenagent.document.enums.DocumentPlanSourceEnum;
import com.reubenagent.document.enums.DocumentPlanStatusEnum;
import com.reubenagent.document.enums.DocumentStrategyExecuteStatusEnum;
import com.reubenagent.document.enums.DocumentStrategyStatusEnum;
import com.reubenagent.document.enums.DocumentTaskEventTypeEnum;
import com.reubenagent.document.enums.DocumentTaskStageEnum;
import com.reubenagent.document.enums.DocumentTaskStatusEnum;
import com.reubenagent.document.enums.DocumentVectorStatusEnum;
import com.reubenagent.document.enums.DocumentVectorStoreTypeEnum;
import com.reubenagent.document.mapper.IDocumentChunkMapper;
import com.reubenagent.document.mapper.IDocumentMapper;
import com.reubenagent.document.mapper.IDocumentParentBlockMapper;
import com.reubenagent.document.mapper.IDocumentStrategyPlanMapper;
import com.reubenagent.document.mapper.IDocumentStrategyStepMapper;
import com.reubenagent.document.mapper.IDocumentTaskLogMapper;
import com.reubenagent.document.mapper.IDocumentTaskMapper;
import com.reubenagent.document.model.ChunkCandidate;
import com.reubenagent.document.model.DocumentParseResult;
import com.reubenagent.document.model.DocumentStrategyPlanDraft;
import com.reubenagent.document.model.DocumentStrategyStepDraft;
import com.reubenagent.document.model.DocumentVectorizationResult;
import com.reubenagent.document.model.ParentBlockCandidate;
import com.reubenagent.document.service.IDocumentAsyncProcessService;
import com.reubenagent.document.service.IDocumentParseResultService;
import com.reubenagent.document.service.IDocumentProfileService;
import com.reubenagent.document.service.IDocumentStorageService;
import com.reubenagent.document.service.IDocumentStrategyService;
import com.reubenagent.document.service.IDocumentStructureNodeService;
import com.reubenagent.document.service.IDocumentVectorGateway;
import com.reubenagent.document.service.keyword.IDocumentKeywordSearchGateway;
import com.reubenagent.framework.uid.UidGenerator;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;

/**
 * 文档异步处理 —— 编排 document/task/taskLog 三表状态流转。
 *
 * @author reuben
 * @since 2026-06-14
 */
@Slf4j
@AllArgsConstructor
@Service
public class DocumentAsyncProcessServiceImpl implements IDocumentAsyncProcessService {

    private final IDocumentMapper documentMapper;
    private final IDocumentTaskMapper documentTaskMapper;
    private final IDocumentTaskLogMapper documentTaskLogMapper;
    private final IDocumentStrategyPlanMapper strategyPlanMapper;
    private final IDocumentStrategyStepMapper strategyStepMapper;

    private final IDocumentStorageService documentStorageService;
    private final IDocumentParseResultService documentParseResultService;
    private final IDocumentStructureNodeService structureNodeService;
    private final IDocumentProfileService documentProfileService;
    private final IDocumentStrategyService strategyService;

    private final IDocumentParentBlockMapper parentBlockMapper;
    private final IDocumentChunkMapper chunkMapper;
    private final IDocumentVectorGateway vectorGateway;
    private final IDocumentKeywordSearchGateway keywordSearchGateway;

    private final UidGenerator uidGenerator;

    @Override
    public void handleParseStrategyRoute(Long documentId, Long taskId) {
        log.info("异步解析任务启动: documentId={}, taskId={}", documentId, taskId);

        Document document = documentMapper.selectById(documentId);
        DocumentTask task = documentTaskMapper.selectById(taskId);
        if (document == null || task == null) {
            log.warn("异步处理对应的文档、任务不存在 documentId={} taskId={}", documentId, taskId);
            return;
        }

        Date startTime = new Date();
        DocumentParseResult parseResult = null;
        List<DocumentStructureNode> structureNodes = null;
        String parsedTextPath = null;

        try {
            // 阶段 1：状态初始化
            documentMapper.updateById(Document.builder()
                    .id(document.getId())
                    .parseStatus(DocumentParseStatusEnum.PARSING.getCode())
                    .build());

            Integer currentStage = DocumentTaskStageEnum.CONTENT_PARSE.getCode();
            task.setCurrentStage(currentStage);
            task.setStartTime(startTime);
            documentTaskMapper.updateById(DocumentTask.builder()
                    .id(task.getId())
                    .taskStatus(DocumentTaskStatusEnum.RUNNING.getCode())
                    .currentStage(currentStage)
                    .startTime(startTime)
                    .build());

            documentTaskLogMapper.insert(DocumentTaskLog.builder()
                    .id(uidGenerator.getUid())
                    .taskId(taskId)
                    .documentId(documentId)
                    .stageType(DocumentTaskStageEnum.CONTENT_PARSE.getCode())
                    .eventType(DocumentTaskEventTypeEnum.START.getCode())
                    .logLevel(DocumentLogLevelEnum.INFO.getCode())
                    .operatorType(DocumentOperatorTypeEnum.SYSTEM.getCode())
                    .content("开始解析文档内容")
                    .detailJson(JSON.toJSONString(Map.of("objectName", document.getObjectName())))
                    .build());

            // 阶段 2：文件解析
            byte[] fileBytes = documentStorageService.downloadObject(document.getObjectName());
            parseResult = documentParseResultService.parse(
                    fileBytes, document.getOriginalFileName(), document.getMediaType(),
                    DocumentFileTypeEnum.getFromCode(document.getFileType()));

            // 阶段 3：解析文本存入 MinIO
            parsedTextPath = documentStorageService.uploadParsedText(
                    documentId, parseResult.getParsedText());

            // 阶段 4：结构节点持久化
            structureNodes = structureNodeService.saveNodes(
                    documentId, taskId, parseResult.getStructureNodes());

            // 阶段 5：生成文档画像
            documentProfileService.generateProfile(documentId, parseResult, structureNodes);

            // 内容解析完成
            finishContentParse(document, task, parseResult, structureNodes, parsedTextPath, startTime);

            // 阶段 6：策略推荐
            handleStrategyRecommend(document, task, parseResult);

            // 阶段 7：成功收尾
            finishParseSuccess(document, task, parseResult, structureNodes, parsedTextPath);

        } catch (Exception e) {
            log.error("文档解析失败 documentId={} taskId={}", documentId, taskId, e);
            handleParseFailure(document, task, startTime, e);
        }
    }

    @Override
    public void handleIndexBuild(Long documentId, Long taskId, Long planId) {
        log.info("索引构建任务启动: documentId={}, taskId={}, planId={}", documentId, taskId, planId);

        Document document = documentMapper.selectById(documentId);
        DocumentTask task = documentTaskMapper.selectById(taskId);
        DocumentStrategyPlan plan = strategyPlanMapper.selectById(planId);
        if (document == null || task == null || plan == null) {
            log.warn("索引构建对应的文档/任务/方案不存在 documentId={} taskId={} planId={}", documentId, taskId, planId);
            return;
        }

        Date startTime = new Date();
        List<ParentBlockCandidate> parentCandidates = null;
        List<DocumentParentBlock> parentBlocks = null;
        List<DocumentChunk> chunks = null;

        try {
            // 阶段 1：切块执行
            parentCandidates = executeChunkStage(document, task, plan, startTime);

            // 阶段 2：切块后处理
            List<ParentBlockCandidate> validCandidates = parentCandidates.stream()
                    .filter(DocumentAsyncProcessServiceImpl::isValidParentBlock)
                    .toList();
            log.info("父块过滤完成: total={} valid={}", parentCandidates.size(), validCandidates.size());

            ParentChildEntityBundle bundle = buildParentChildEntities(document, task, plan, validCandidates);
            parentBlocks = bundle.parentBlocks();
            chunks = bundle.childChunks();

            finishChunkPostProcess(document, task, parentBlocks, chunks);

            // 阶段 3：向量化
            executeVectorizeStage(document, task, chunks);

            // 阶段 4：入库完成
            finishIndexSuccess(document, task, plan, parentBlocks, chunks, startTime);

        } catch (Exception e) {
            log.error("索引构建失败 documentId={} taskId={} planId={}", documentId, taskId, planId, e);
            handleIndexFailure(document, task, plan, startTime, e);
        }
    }

    // ============ 索引阶段方法 ============

    /**
     * 阶段 1：切块执行 —— 下载解析文本，调用策略引擎生成 parent/child 候选。
     */
    private List<ParentBlockCandidate> executeChunkStage(Document document, DocumentTask task,
                                                          DocumentStrategyPlan plan, Date startTime) {
        Integer currentStage = DocumentTaskStageEnum.CHUNK_EXECUTE.getCode();
        updateTaskStage(task.getId(), currentStage);

        // 更新 document/task 状态
        documentMapper.updateById(Document.builder()
                .id(document.getId())
                .indexStatus(DocumentIndexStatusEnum.BUILDING.getCode())
                .build());

        // 更新计划步骤为执行中
        updatePlanStepsStatus(plan.getId(), DocumentStrategyExecuteStatusEnum.EXECUTING.getCode());

        documentTaskLogMapper.insert(DocumentTaskLog.builder()
                .id(uidGenerator.getUid())
                .taskId(task.getId())
                .documentId(document.getId())
                .stageType(currentStage)
                .eventType(DocumentTaskEventTypeEnum.START.getCode())
                .logLevel(DocumentLogLevelEnum.INFO.getCode())
                .operatorType(DocumentOperatorTypeEnum.SYSTEM.getCode())
                .content("开始策略切块执行")
                .detailJson(JSON.toJSONString(Map.of("planId", plan.getId())))
                .build());

        // 下载解析后的纯文本
        byte[] textBytes = documentStorageService.downloadObject(document.getParseSuccessTextPath());
        String parsedText = new String(textBytes, StandardCharsets.UTF_8);

        // 加载策略步骤
        List<DocumentStrategyStep> steps = strategyStepMapper.selectList(
                new LambdaQueryWrapper<DocumentStrategyStep>()
                        .eq(DocumentStrategyStep::getPlanId, plan.getId())
                        .orderByAsc(DocumentStrategyStep::getStepNo));

        // 执行策略引擎
        List<ParentBlockCandidate> candidates = strategyService.buildParentBlocks(document, plan, steps, parsedText);
        log.info("策略切块完成: documentId={} parentCount={}", document.getId(), candidates.size());

        return candidates;
    }

    /**
     * 阶段 2：切块后处理 —— 持久化 parentBlocks 和 chunks。
     */
    private void finishChunkPostProcess(Document document, DocumentTask task,
                                         List<DocumentParentBlock> parentBlocks,
                                         List<DocumentChunk> chunks) {
        Integer currentStage = DocumentTaskStageEnum.CHUNK_POST_PROCESS.getCode();
        updateTaskStage(task.getId(), currentStage);

        documentTaskLogMapper.insert(DocumentTaskLog.builder()
                .id(uidGenerator.getUid())
                .taskId(task.getId())
                .documentId(document.getId())
                .stageType(currentStage)
                .eventType(DocumentTaskEventTypeEnum.START.getCode())
                .logLevel(DocumentLogLevelEnum.INFO.getCode())
                .operatorType(DocumentOperatorTypeEnum.SYSTEM.getCode())
                .content("开始切块后处理")
                .build());

        // 批量持久化
        for (DocumentParentBlock pb : parentBlocks) {
            parentBlockMapper.insert(pb);
        }
        for (DocumentChunk chunk : chunks) {
            chunkMapper.insert(chunk);
        }

        documentTaskLogMapper.insert(DocumentTaskLog.builder()
                .id(uidGenerator.getUid())
                .taskId(task.getId())
                .documentId(document.getId())
                .stageType(currentStage)
                .eventType(DocumentTaskEventTypeEnum.COMPLETE.getCode())
                .logLevel(DocumentLogLevelEnum.INFO.getCode())
                .operatorType(DocumentOperatorTypeEnum.SYSTEM.getCode())
                .content("切块后处理完成")
                .detailJson(JSON.toJSONString(Map.of(
                        "parentCount", parentBlocks.size(),
                        "chunkCount", chunks.size())))
                .build());
    }

    /**
     * 阶段 3：向量化 —— 调用向量网关，根据结果更新 chunk 状态，同步关键字索引。
     */
    private void executeVectorizeStage(Document document, DocumentTask task, List<DocumentChunk> chunks) {
        Integer currentStage = DocumentTaskStageEnum.VECTORIZE.getCode();
        updateTaskStage(task.getId(), currentStage);

        // 标记所有 chunk 为向量化中
        updateChunksVectorStatus(task.getId(), DocumentVectorStatusEnum.VECTORIZING.getCode());

        documentTaskLogMapper.insert(DocumentTaskLog.builder()
                .id(uidGenerator.getUid())
                .taskId(task.getId())
                .documentId(document.getId())
                .stageType(currentStage)
                .eventType(DocumentTaskEventTypeEnum.START.getCode())
                .logLevel(DocumentLogLevelEnum.INFO.getCode())
                .operatorType(DocumentOperatorTypeEnum.SYSTEM.getCode())
                .content("开始向量化")
                .detailJson(JSON.toJSONString(Map.of("chunkCount", chunks.size())))
                .build());

        // 调用向量网关
        DocumentVectorizationResult result = vectorGateway.vectorize(chunks);

        // 根据结果批量更新 chunk 状态
        if (!result.getSuccessChunkIds().isEmpty()) {
            updateChunkVectorStatusByIds(result.getSuccessChunkIds(),
                    DocumentVectorStatusEnum.VECTOR_SUCCESS.getCode());
        }
        if (!result.getFailedChunkIds().isEmpty()) {
            updateChunkVectorStatusByIds(result.getFailedChunkIds(),
                    DocumentVectorStatusEnum.VECTOR_FAILED.getCode());
        }

        // 同步到关键字搜索网关
        List<DocumentChunk> successChunks = chunks.stream()
                .filter(c -> result.getSuccessChunkIds().contains(c.getId()))
                .toList();
        keywordSearchGateway.indexChunks(successChunks);

        documentTaskLogMapper.insert(DocumentTaskLog.builder()
                .id(uidGenerator.getUid())
                .taskId(task.getId())
                .documentId(document.getId())
                .stageType(currentStage)
                .eventType(DocumentTaskEventTypeEnum.COMPLETE.getCode())
                .logLevel(DocumentLogLevelEnum.INFO.getCode())
                .operatorType(DocumentOperatorTypeEnum.SYSTEM.getCode())
                .content("向量化完成")
                .detailJson(JSON.toJSONString(Map.of(
                        "totalCount", result.getTotalCount(),
                        "successCount", result.getSuccessCount(),
                        "failedCount", result.getFailedCount())))
                .build());

        if (result.getFailedCount() > 0) {
            log.warn("部分向量化失败: documentId={} total={} success={} failed={}",
                    document.getId(), result.getTotalCount(),
                    result.getSuccessCount(), result.getFailedCount());
        }
    }

    /**
     * 阶段 4：成功收尾 —— 更新 plan/document/task 为终态。
     */
    private void finishIndexSuccess(Document document, DocumentTask task, DocumentStrategyPlan plan,
                                     List<DocumentParentBlock> parentBlocks, List<DocumentChunk> chunks,
                                     Date startTime) {
        Date finishTime = new Date();
        long costMillis = finishTime.getTime() - startTime.getTime();
        Integer currentStage = DocumentTaskStageEnum.STORE_COMPLETE.getCode();

        // 更新方案为已执行
        strategyPlanMapper.updateById(DocumentStrategyPlan.builder()
                .id(plan.getId())
                .planStatus(DocumentPlanStatusEnum.EXECUTED.getCode())
                .build());

        // 更新步骤为成功
        updatePlanStepsStatus(plan.getId(), DocumentStrategyExecuteStatusEnum.SUCCESS.getCode());

        // 更新文档状态
        documentMapper.updateById(Document.builder()
                .id(document.getId())
                .indexStatus(DocumentIndexStatusEnum.BUILD_SUCCESS.getCode())
                .latestIndexTaskId(task.getId())
                .build());

        // 更新任务状态
        documentTaskMapper.updateById(DocumentTask.builder()
                .id(task.getId())
                .taskStatus(DocumentTaskStatusEnum.SUCCESS.getCode())
                .currentStage(currentStage)
                .finishTime(finishTime)
                .costMillis(costMillis)
                .errorCode(null)
                .errorMsg(null)
                .build());

        documentTaskLogMapper.insert(DocumentTaskLog.builder()
                .id(uidGenerator.getUid())
                .taskId(task.getId())
                .documentId(document.getId())
                .stageType(currentStage)
                .eventType(DocumentTaskEventTypeEnum.COMPLETE.getCode())
                .logLevel(DocumentLogLevelEnum.INFO.getCode())
                .operatorType(DocumentOperatorTypeEnum.SYSTEM.getCode())
                .content("索引构建完成")
                .detailJson(JSON.toJSONString(Map.of(
                        "costMillis", costMillis,
                        "parentCount", parentBlocks.size(),
                        "chunkCount", chunks.size())))
                .build());

        log.info("索引构建成功: documentId={} costMillis={} parentCount={} chunkCount={}",
                document.getId(), costMillis, parentBlocks.size(), chunks.size());
    }

    /**
     * 索引构建失败处理 —— 回写失败状态、标记未向量化的 chunk、记录错误日志。
     */
    private void handleIndexFailure(Document document, DocumentTask task, DocumentStrategyPlan plan,
                                     Date startTime, Exception e) {
        Date finishTime = new Date();
        Integer currentStage = task.getCurrentStage() != null
                ? task.getCurrentStage()
                : DocumentTaskStageEnum.CHUNK_EXECUTE.getCode();

        // 更新文档为构建失败
        documentMapper.updateById(Document.builder()
                .id(document.getId())
                .indexStatus(DocumentIndexStatusEnum.BUILD_FAIL.getCode())
                .build());

        // 将待向量化的 chunk 批量标记为失败（已成功的 chunk 不受影响）
        chunkMapper.markVectorFailedByTaskId(task.getId());

        // 更新方案步骤为失败
        updatePlanStepsStatus(plan.getId(), DocumentStrategyExecuteStatusEnum.FAILED.getCode());

        // 更新任务为失败
        documentTaskMapper.updateById(DocumentTask.builder()
                .id(task.getId())
                .taskStatus(DocumentTaskStatusEnum.FAILED.getCode())
                .currentStage(currentStage)
                .finishTime(finishTime)
                .costMillis(finishTime.getTime() - startTime.getTime())
                .errorCode(DocumentManageCode.INDEX_BUILD_FAILED.getCode().toString())
                .errorMsg(e.getMessage())
                .build());

        documentTaskLogMapper.insert(DocumentTaskLog.builder()
                .id(uidGenerator.getUid())
                .taskId(task.getId())
                .documentId(document.getId())
                .stageType(currentStage)
                .eventType(DocumentTaskEventTypeEnum.FAILED.getCode())
                .logLevel(DocumentLogLevelEnum.ERROR.getCode())
                .operatorType(DocumentOperatorTypeEnum.SYSTEM.getCode())
                .content("索引构建失败: " + e.getMessage())
                .detailJson(JSON.toJSONString(Map.of(
                        "errorCode", DocumentManageCode.INDEX_BUILD_FAILED.getCode().toString(),
                        "currentStage", currentStage,
                        "exceptionClass", e.getClass().getName())))
                .build());
    }

    // ============ 阶段方法 ============

    private void handleStrategyRecommend(Document document, DocumentTask task, DocumentParseResult parseResult) {
        Integer currentStage = DocumentTaskStageEnum.STRATEGY_ROUTE.getCode();
        task.setCurrentStage(currentStage);
        documentTaskMapper.updateById(DocumentTask.builder()
                .id(task.getId())
                .currentStage(currentStage)
                .build());

        DocumentStrategyPlanDraft planDraft = strategyService.recommendStrategy(document, parseResult);

        // 生成方案版本号
        DocumentStrategyPlan latestPlan = strategyPlanMapper.selectOne(
                new LambdaQueryWrapper<DocumentStrategyPlan>()
                        .eq(DocumentStrategyPlan::getDocumentId, document.getId())
                        .orderByDesc(DocumentStrategyPlan::getPlanVersion)
                        .last("LIMIT 1"));
        int planVersion = (latestPlan != null && latestPlan.getPlanVersion() != null)
                ? latestPlan.getPlanVersion() + 1 : 1;

        Long planId = uidGenerator.getUid();
        int strategyCount = planDraft.getParentSteps().size() + planDraft.getChildSteps().size();

        DocumentStrategyPlan plan = DocumentStrategyPlan.builder()
                .id(planId)
                .documentId(document.getId())
                .planVersion(planVersion)
                .planSource(DocumentPlanSourceEnum.SYSTEM_RECOMMEND.getCode())
                .planStatus(DocumentPlanStatusEnum.WAIT_CONFIRM.getCode())
                .strategyCount(strategyCount)
                .strategySnapshot(planDraft.getStrategySnapshot())
                .recommendReason(planDraft.getRecommendReason())
                .build();
        strategyPlanMapper.insert(plan);

        // 持久化步骤（父管道 + 子管道统一编号）
        List<DocumentStrategyStep> allSteps = new ArrayList<>();
        int stepNo = 1;
        for (DocumentStrategyStepDraft draft : planDraft.getParentSteps()) {
            allSteps.add(toStepEntity(uidGenerator.getUid(), planId, document.getId(), stepNo++, draft));
        }
        for (DocumentStrategyStepDraft draft : planDraft.getChildSteps()) {
            allSteps.add(toStepEntity(uidGenerator.getUid(), planId, document.getId(), stepNo++, draft));
        }
        for (DocumentStrategyStep step : allSteps) {
            strategyStepMapper.insert(step);
        }

        // 更新文档策略状态
        documentMapper.updateById(Document.builder()
                .id(document.getId())
                .strategyStatus(DocumentStrategyStatusEnum.RECOMMENDED.getCode())
                .currentStrategyPlanId(planId)
                .build());

        documentTaskLogMapper.insert(DocumentTaskLog.builder()
                .id(uidGenerator.getUid())
                .taskId(task.getId())
                .documentId(document.getId())
                .stageType(DocumentTaskStageEnum.STRATEGY_ROUTE.getCode())
                .eventType(DocumentTaskEventTypeEnum.RECOMMEND_STRATEGY.getCode())
                .logLevel(DocumentLogLevelEnum.INFO.getCode())
                .operatorType(DocumentOperatorTypeEnum.SYSTEM.getCode())
                .content("策略推荐完成")
                .detailJson(JSON.toJSONString(Map.of(
                        "planId", planId,
                        "planVersion", planVersion,
                        "strategySnapshot", planDraft.getStrategySnapshot(),
                        "strategyCount", strategyCount,
                        "recommendReason", planDraft.getRecommendReason())))
                .build());

        log.info("策略推荐完成: documentId={} planId={} snapshot={}", document.getId(), planId,
                planDraft.getStrategySnapshot());
    }

    private void finishContentParse(Document document, DocumentTask task, DocumentParseResult parseResult,
                                     List<DocumentStructureNode> structureNodes, String parsedTextPath,
                                     Date startTime) {
        long costMillis = System.currentTimeMillis() - startTime.getTime();
        int nodeCount = structureNodes != null ? structureNodes.size() : 0;

        documentTaskLogMapper.insert(DocumentTaskLog.builder()
                .id(uidGenerator.getUid())
                .taskId(task.getId())
                .documentId(document.getId())
                .stageType(DocumentTaskStageEnum.CONTENT_PARSE.getCode())
                .eventType(DocumentTaskEventTypeEnum.COMPLETE.getCode())
                .logLevel(DocumentLogLevelEnum.INFO.getCode())
                .operatorType(DocumentOperatorTypeEnum.SYSTEM.getCode())
                .content("文档解析完成")
                .detailJson(JSON.toJSONString(Map.of(
                        "charCount", parseResult.getCharCount(),
                        "tokenCount", parseResult.getTokenCount(),
                        "headingCount", parseResult.getHeadingCount(),
                        "paragraphCount", parseResult.getParagraphCount(),
                        "maxParagraphLength", parseResult.getMaxParagraphLength(),
                        "structureLevel", parseResult.getStructureLevel(),
                        "contentQualityLevel", parseResult.getContentQualityLevel(),
                        "structureNodeCount", nodeCount,
                        "costMillis", costMillis)))
                .build());
    }

    private void finishParseSuccess(Document document, DocumentTask task, DocumentParseResult parseResult,
                                     List<DocumentStructureNode> structureNodes, String parsedTextPath) {
        Date finishTime = new Date();
        long costMillis = finishTime.getTime() - task.getStartTime().getTime();
        int nodeCount = structureNodes != null ? structureNodes.size() : 0;

        documentMapper.updateById(Document.builder()
                .id(document.getId())
                .parseStatus(DocumentParseStatusEnum.PARSE_SUCCESS.getCode())
                .charCount(parseResult.getCharCount())
                .tokenCount(parseResult.getTokenCount())
                .structureLevel(parseResult.getStructureLevel())
                .contentQualityLevel(parseResult.getContentQualityLevel())
                .parseSuccessTextPath(parsedTextPath)
                .parseErrorMsg(null)
                .structureNodeCount(nodeCount)
                .latestParseTaskId(task.getId())
                .build());

        documentTaskMapper.updateById(DocumentTask.builder()
                .id(task.getId())
                .taskStatus(DocumentTaskStatusEnum.SUCCESS.getCode())
                .currentStage(DocumentTaskStageEnum.STRATEGY_ROUTE.getCode())
                .finishTime(finishTime)
                .costMillis(costMillis)
                .errorCode(null)
                .errorMsg(null)
                .build());

        log.info("文档解析成功: documentId={} costMillis={}", document.getId(), costMillis);
    }

    private void handleParseFailure(Document document, DocumentTask task, Date startTime, Exception e) {
        Date finishTime = new Date();
        Integer currentStage = task.getCurrentStage();

        documentMapper.updateById(Document.builder()
                .id(document.getId())
                .parseStatus(DocumentParseStatusEnum.PARSE_FAIL.getCode())
                .parseErrorMsg(e.getMessage())
                .build());

        documentTaskMapper.updateById(DocumentTask.builder()
                .id(task.getId())
                .taskStatus(DocumentTaskStatusEnum.FAILED.getCode())
                .currentStage(currentStage)
                .finishTime(finishTime)
                .costMillis(finishTime.getTime() - startTime.getTime())
                .errorCode("TASK_FAILED")
                .errorMsg(e.getMessage())
                .build());

        documentTaskLogMapper.insert(DocumentTaskLog.builder()
                .id(uidGenerator.getUid())
                .taskId(task.getId())
                .documentId(document.getId())
                .stageType(currentStage)
                .eventType(DocumentTaskEventTypeEnum.FAILED.getCode())
                .logLevel(DocumentLogLevelEnum.ERROR.getCode())
                .operatorType(DocumentOperatorTypeEnum.SYSTEM.getCode())
                .content("文档解析失败: " + e.getMessage())
                .detailJson(JSON.toJSONString(Map.of(
                        "errorCode", "TASK_FAILED",
                        "currentStage", currentStage,
                        "exceptionClass", e.getClass().getName())))
                .build());
    }

    // ============ 辅助方法 ============

    private DocumentStrategyStep toStepEntity(Long id, Long planId, Long documentId, int stepNo,
                                               DocumentStrategyStepDraft draft) {
        return DocumentStrategyStep.builder()
                .id(id)
                .planId(planId)
                .documentId(documentId)
                .stepNo(stepNo)
                .pipelineType(draft.getPipelineType())
                .strategyType(draft.getStrategyType())
                .strategyRole(draft.getStrategyRole())
                .sourceType(draft.getSourceType())
                .executeStatus(DocumentStrategyExecuteStatusEnum.WAIT_EXECUTE.getCode())
                .recommendReason(draft.getRecommendReason())
                .build();
    }

    // ============ 索引构建辅助方法 ============

    /**
     * 将内存候选模型转换为数据库实体，分配雪花 ID。
     */
    private ParentChildEntityBundle buildParentChildEntities(Document document, DocumentTask task,
                                                               DocumentStrategyPlan plan,
                                                               List<ParentBlockCandidate> candidates) {
        List<DocumentParentBlock> parentBlocks = new ArrayList<>();
        List<DocumentChunk> childChunks = new ArrayList<>();
        int globalChunkNo = 1;

        for (int i = 0; i < candidates.size(); i++) {
            ParentBlockCandidate candidate = candidates.get(i);
            Long parentBlockId = uidGenerator.getUid();
            int parentNo = i + 1;
            List<ChunkCandidate> children = candidate.getChildChunks();
            int childCount = children != null ? children.size() : 0;

            parentBlocks.add(DocumentParentBlock.builder()
                    .id(parentBlockId)
                    .documentId(document.getId())
                    .taskId(task.getId())
                    .planId(plan.getId())
                    .parentNo(parentNo)
                    .sourceType(candidate.getSourceType() != null
                            ? candidate.getSourceType()
                            : DocumentChunkSourceTypeEnum.ORIGINAL.getCode())
                    .sectionPath(candidate.getSectionPath())
                    .structureNodeId(candidate.getStructureNodeId())
                    .structureNodeType(candidate.getStructureNodeType())
                    .canonicalPath(candidate.getCanonicalPath())
                    .itemIndex(candidate.getItemIndex())
                    .parentText(candidate.getText())
                    .charCount(candidate.getText() != null ? candidate.getText().length() : 0)
                    .tokenCount(estimateTokenCount(candidate.getText()))
                    .childCount(childCount)
                    .startChunkNo(globalChunkNo)
                    .endChunkNo(childCount > 0 ? globalChunkNo + childCount - 1 : globalChunkNo - 1)
                    .build());

            if (children != null) {
                for (ChunkCandidate child : children) {
                    childChunks.add(DocumentChunk.builder()
                            .id(uidGenerator.getUid())
                            .documentId(document.getId())
                            .taskId(task.getId())
                            .planId(plan.getId())
                            .parentBlockId(parentBlockId)
                            .chunkNo(globalChunkNo++)
                            .sourceType(child.getSourceType() != null
                                    ? child.getSourceType()
                                    : DocumentChunkSourceTypeEnum.ORIGINAL.getCode())
                            .sectionPath(child.getSectionPath())
                            .structureNodeId(child.getStructureNodeId())
                            .structureNodeType(child.getStructureNodeType())
                            .canonicalPath(child.getCanonicalPath())
                            .itemIndex(child.getItemIndex())
                            .chunkText(child.getText())
                            .charCount(child.getText() != null ? child.getText().length() : 0)
                            .tokenCount(estimateTokenCount(child.getText()))
                            .vectorStatus(DocumentVectorStatusEnum.WAIT_VECTOR.getCode())
                            .vectorStoreType(DocumentVectorStoreTypeEnum.PG_VECTOR.getCode())
                            .build());
                }
            }
        }

        return new ParentChildEntityBundle(parentBlocks, childChunks);
    }

    /**
     * 过滤无效父块 —— 提取 stream filter 为命名方法。
     */
    private static boolean isValidParentBlock(ParentBlockCandidate candidate) {
        return candidate != null
                && candidate.getText() != null
                && !candidate.getText().isBlank();
    }

    /**
     * 估算文本的 token 数。
     *
     * <p>TODO: 集成 TikToken (com.knuddels:jtokkit) 做精确 cl100k_base 计算，
     * 当前使用英文字符数 / 4 的粗略估算。</p>
     */
    private static int estimateTokenCount(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        return Math.max(1, text.length() / 4);
    }

    /** 更新任务当前阶段。 */
    private void updateTaskStage(Long taskId, Integer stage) {
        documentTaskMapper.updateById(DocumentTask.builder()
                .id(taskId)
                .currentStage(stage)
                .build());
    }

    /** 批量更新方案下所有步骤的执行状态。 */
    private void updatePlanStepsStatus(Long planId, Integer executeStatus) {
        strategyStepMapper.update(null,
                new LambdaUpdateWrapper<DocumentStrategyStep>()
                        .eq(DocumentStrategyStep::getPlanId, planId)
                        .set(DocumentStrategyStep::getExecuteStatus, executeStatus));
    }

    /** 批量更新任务下所有 chunk 的向量状态。 */
    private void updateChunksVectorStatus(Long taskId, Integer vectorStatus) {
        chunkMapper.update(null,
                new LambdaUpdateWrapper<DocumentChunk>()
                        .eq(DocumentChunk::getTaskId, taskId)
                        .set(DocumentChunk::getVectorStatus, vectorStatus));
    }

    /** 按 ID 列表批量更新 chunk 向量状态。 */
    private void updateChunkVectorStatusByIds(List<Long> chunkIds, Integer vectorStatus) {
        chunkMapper.update(null,
                new LambdaUpdateWrapper<DocumentChunk>()
                        .in(DocumentChunk::getId, chunkIds)
                        .set(DocumentChunk::getVectorStatus, vectorStatus));
    }

    // ============ 内部类型 ============

    /**
     * 父块-子块实体束 —— buildParentChildEntities 的返回值。
     */
    record ParentChildEntityBundle(List<DocumentParentBlock> parentBlocks,
                                   List<DocumentChunk> childChunks) {
    }
}
