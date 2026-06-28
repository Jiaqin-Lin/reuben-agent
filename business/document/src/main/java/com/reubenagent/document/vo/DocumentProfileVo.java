package com.reubenagent.document.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Date;

/**
 * 文档画像出参 VO —— 仅投影对外字段。
 *
 * @author reuben
 * @since 2026-06-28
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DocumentProfileVo {
    private Long id;
    private Long documentId;
    private String documentName;
    private Integer profileVersion;
    private String documentSummary;
    private String documentType;
    private String coreTopics;
    private String exampleQuestions;
    private Integer graphFriendly;
    private Integer supportsGraphOutline;
    private Integer supportsItemLookup;
    private Integer supportsGraphAssist;
    private String profileSource;
    private Integer profileStatus;
    private String errorMsg;
    private Date createTime;
    private Date editTime;
}
