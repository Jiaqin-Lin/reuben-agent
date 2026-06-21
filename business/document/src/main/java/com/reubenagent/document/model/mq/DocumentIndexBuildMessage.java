package com.reubenagent.document.model.mq;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Kafka 消息 —— 索引构建任务。
 *
 * <p>由策略确认端点发送，触发 {@code DocumentAsyncProcessServiceImpl.handleIndexBuild()}。</p>
 *
 * @author reuben
 * @since 2026-06-21
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DocumentIndexBuildMessage {

    /** 文档ID */
    private Long documentId;

    /** 任务ID */
    private Long taskId;

    /** 策略方案ID */
    private Long planId;
}
