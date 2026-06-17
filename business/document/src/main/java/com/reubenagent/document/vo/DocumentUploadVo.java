package com.reubenagent.document.vo;

import com.reubenagent.document.enums.DocumentIndexStatusEnum;
import com.reubenagent.document.enums.DocumentParseStatusEnum;
import com.reubenagent.document.enums.DocumentStrategyStatusEnum;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 文档上传响应体。状态字段取值参见 {@link DocumentParseStatusEnum}、{@link DocumentStrategyStatusEnum}、{@link DocumentIndexStatusEnum}。
 *
 * @author reuben
 * @since 2026-06-14
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class DocumentUploadVo {

    /** 文档 ID（UidGenerator 生成的全局唯一 ID） */
    private Long documentId;

    /** 关联的任务 ID */
    private Long taskId;

    /** 文档名称 */
    private String documentName;

    /** 解析状态，取值见 {@link DocumentParseStatusEnum} */
    private Integer parseStatus;

    /** 策略状态，取值见 {@link DocumentStrategyStatusEnum} */
    private Integer strategyStatus;

    /** 索引状态，取值见 {@link DocumentIndexStatusEnum} */
    private Integer indexStatus;

}