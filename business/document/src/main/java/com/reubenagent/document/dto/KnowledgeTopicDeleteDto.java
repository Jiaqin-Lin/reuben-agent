package com.reubenagent.document.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class KnowledgeTopicDeleteDto {
    @NotBlank(message = "topicCode 不能为空")
    private String topicCode;
}
