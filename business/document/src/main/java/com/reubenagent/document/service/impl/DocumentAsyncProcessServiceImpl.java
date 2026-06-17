package com.reubenagent.document.service.impl;

import com.alibaba.fastjson.JSON;
import com.reubenagent.document.entity.Document;
import com.reubenagent.document.entity.DocumentTask;
import com.reubenagent.document.entity.DocumentTaskLog;
import com.reubenagent.document.enums.*;
import com.reubenagent.document.mapper.IDocumentMapper;
import com.reubenagent.document.mapper.IDocumentTaskLogMapper;
import com.reubenagent.document.mapper.IDocumentTaskMapper;
import com.reubenagent.document.service.IDocumentAsyncProcessService;
import com.reubenagent.document.service.IDocumentStorageService;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Date;
import java.util.Map;

/**
 * 文档异步处理 —— 编排解析+策略路由。当前为 stub，解析逻辑待接入 {@code IDocumentParseResultService}。
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

    @Override
    public void handleParseStrategyRoute(Long documentId, Long taskId) {
        // 确认文档和任务存在
        Document document = documentMapper.selectById(documentId);
        DocumentTask task = documentTaskMapper.selectById(taskId);
        if (document == null || task == null) {
            log.warn("异步处理对应的文档、任务不存在 documentId = {} taskId = {}", documentId, taskId);
            return;
        }

        Date startTime = new Date();
        try {
            // 更新 document task taskLog 三者状态
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

            // 文件解析


        } catch (Exception e) {
            log.error("文档解析失败 documentId={} taskId={}", documentId, taskId, e);
            task.setTaskStatus(DocumentTaskStatusEnum.FAILED.getCode());
            task.setErrorMsg(e.getMessage());
            documentTaskMapper.updateById(task);
        }

    }
}
