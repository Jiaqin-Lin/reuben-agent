package com.reubenagent.document.vo;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Date;

/**
 * 文档详情 VO —— 供查询接口使用。
 *
 * <p>除基础元数据外，附带当前生效方案、最近任务、最近索引任务等驱动工作台状态判定的字段。</p>
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
    private Integer tokenCount;
    private Integer structureLevel;

    @JsonProperty("qualityLevel")
    private Integer contentQualityLevel;

    private String parseErrorMsg;

    private String knowledgeScopeCode;
    private String knowledgeScopeName;
    private String businessCategory;
    private String documentTags;

    /** 当前生效策略方案ID */
    private Long currentPlanId;

    /** 最近索引任务ID */
    private Long latestIndexTaskId;

    /** 最近任务ID（任意类型） */
    private Long latestTaskId;
    private Integer latestTaskType;
    private Integer latestTaskStatus;

    private Date createTime;
    private Date updateTime;
}
