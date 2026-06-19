package com.reubenagent.document.service.impl;

import com.alibaba.fastjson.JSON;
import com.reubenagent.document.entity.Document;
import com.reubenagent.document.entity.DocumentTask;
import com.reubenagent.document.entity.DocumentTaskLog;
import com.reubenagent.document.enums.*;
import com.reubenagent.document.mapper.IDocumentMapper;
import com.reubenagent.document.mapper.IDocumentTaskLogMapper;
import com.reubenagent.document.mapper.IDocumentTaskMapper;
import com.reubenagent.document.model.DocumentParseResult;
import com.reubenagent.document.service.IDocumentAsyncProcessService;
import com.reubenagent.document.service.IDocumentParseResultService;
import com.reubenagent.document.service.IDocumentStorageService;
import com.reubenagent.document.service.IDocumentStructureNodeService;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

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

    private final IDocumentStorageService documentStorageService;
    private final IDocumentParseResultService documentParseResultService;
    private final IDocumentStructureNodeService structureNodeService;

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
        try {
            // 阶段 1：状态初始化
            document.setParseStatus(DocumentParseStatusEnum.PARSING.getCode());
            documentMapper.updateById(document);

            task.setTaskStatus(DocumentTaskStatusEnum.RUNNING.getCode());
            task.setCurrentStage(DocumentTaskStageEnum.CONTENT_PARSE.getCode());
            task.setStartTime(startTime);
            documentTaskMapper.updateById(task);

            DocumentTaskLog taskLog = DocumentTaskLog.builder()
                    .taskId(taskId)
                    .documentId(documentId)
                    .stageType(DocumentTaskStageEnum.CONTENT_PARSE.getCode())
                    .eventType(DocumentTaskEventTypeEnum.START.getCode())
                    .logLevel(DocumentLogLevelEnum.INFO.getCode())
                    .operatorType(DocumentOperatorTypeEnum.SYSTEM.getCode())
                    .operatorId(null)
                    .content("开始解析文档内容")
                    .detailJson(JSON.toJSONString(Map.of("objectName", document.getObjectName())))
                    .build();
            documentTaskLogMapper.insert(taskLog);

            // 阶段 2：文件解析
            byte[] fileBytes = documentStorageService.downloadObject(document.getObjectName());
            DocumentParseResult documentParseResult = documentParseResultService.parse(
                    fileBytes, document.getOriginalFileName(), document.getMediaType(),
                    DocumentFileTypeEnum.getFromCode(document.getFileType()));

            // 阶段 3：解析文本存入 MinIO
            String parsedTextPath = documentStorageService.uploadParsedText(
                    documentId, documentParseResult.getParsedText());

            // 阶段 4：结构节点持久化
            structureNodeService.saveNodes(documentId, taskId, documentParseResult.getStructureNodes());

        } catch (Exception e) {
            log.error("文档解析失败 documentId={} taskId={}", documentId, taskId, e);
            task.setTaskStatus(DocumentTaskStatusEnum.FAILED.getCode());
            task.setErrorMsg(e.getMessage());
            documentTaskMapper.updateById(task);
        }

    }
}
