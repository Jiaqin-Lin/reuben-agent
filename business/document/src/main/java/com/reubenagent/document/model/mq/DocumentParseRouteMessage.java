package com.reubenagent.document.model.mq;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Kafka 消息 —— 文档解析与策略路由任务。
 *
 * <p>由 {@code DocumentManageServiceImpl.upload()} 在文件上传完成后发送，
 * 触发 {@code DocumentAsyncProcessServiceImpl.handleParseStrategyRoute()}。</p>
 *
 * @author reuben
 * @since 2026-06-21
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DocumentParseRouteMessage {

    /** 文档ID */
    private Long documentId;

    /** 任务ID */
    private Long taskId;
}
