package com.reubenagent.document.service.impl;

import com.alibaba.fastjson.JSON;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.reubenagent.document.entity.Document;
import com.reubenagent.document.entity.DocumentStrategyPlan;
import com.reubenagent.document.entity.DocumentStrategyStep;
import com.reubenagent.document.entity.DocumentStructureNode;
import com.reubenagent.document.entity.DocumentTask;
import com.reubenagent.document.entity.DocumentTaskLog;
import com.reubenagent.document.enums.DocumentFileTypeEnum;
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
import com.reubenagent.document.mapper.IDocumentMapper;
import com.reubenagent.document.mapper.IDocumentStrategyPlanMapper;
import com.reubenagent.document.mapper.IDocumentStrategyStepMapper;
import com.reubenagent.document.mapper.IDocumentTaskLogMapper;
import com.reubenagent.document.mapper.IDocumentTaskMapper;
import com.reubenagent.document.model.DocumentParseResult;
import com.reubenagent.document.model.DocumentStrategyPlanDraft;
import com.reubenagent.document.model.DocumentStrategyStepDraft;
import com.reubenagent.document.service.IDocumentAsyncProcessService;
import com.reubenagent.document.service.IDocumentParseResultService;
import com.reubenagent.document.service.IDocumentProfileService;
import com.reubenagent.document.service.IDocumentStorageService;
import com.reubenagent.document.service.IDocumentStrategyService;
import com.reubenagent.document.service.IDocumentStructureNodeService;
import com.reubenagent.framework.uid.UidGenerator;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

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
}
