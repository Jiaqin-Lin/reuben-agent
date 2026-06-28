package com.reubenagent.document.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class KnowledgeTopicItemVo {
    private Long id;
    private String topicCode;
    private String topicName;
    private String scopeCode;
    private String description;
    private String aliases;
    private String examples;
    private String answerShape;
    private String executionPreference;
    private Integer sortOrder;
}
