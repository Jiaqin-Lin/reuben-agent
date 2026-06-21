package com.reubenagent.document.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 文档详情 VO —— 供查询接口使用。
 *
 * @author reuben
 * @since 2026-06-21
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DocumentDetailVo {

    private Long documentId;
    private String documentName;
    private String originalFileName;
    private Integer fileType;
    private Long fileSize;
    private Integer parseStatus;
    private Integer strategyStatus;
    private Integer indexStatus;
    private Integer charCount;
    private Integer structureLevel;
    private Integer contentQualityLevel;
}
