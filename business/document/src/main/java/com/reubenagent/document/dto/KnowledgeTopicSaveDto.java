package com.reubenagent.document.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 知识主题保存请求。
 *
 * @author reuben
 * @since 2026-06-28
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class KnowledgeTopicSaveDto {

    private Long id;

    @NotBlank(message = "topicCode 不能为空")
    private String topicCode;

    @NotBlank(message = "topicName 不能为空")
    private String topicName;

    @NotBlank(message = "scopeCode 不能为空")
    private String scopeCode;

    private String description;
    private String aliases;
    private String examples;
    private String answerShape;
    private String executionPreference;
    private Integer sortOrder;
}
