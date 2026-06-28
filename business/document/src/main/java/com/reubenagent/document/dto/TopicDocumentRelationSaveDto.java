package com.reubenagent.document.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TopicDocumentRelationSaveDto {
    @NotBlank(message = "topicCode 不能为空")
    private String topicCode;
    @NotNull(message = "documentId 不能为空")
    private Long documentId;
    private BigDecimal relationScore;
    private String relationSource;
    private String reason;
}
