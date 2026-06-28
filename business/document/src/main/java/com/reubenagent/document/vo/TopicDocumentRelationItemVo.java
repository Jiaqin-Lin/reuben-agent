package com.reubenagent.document.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TopicDocumentRelationItemVo {
    private Long id;
    private String topicCode;
    private Long documentId;
    private String documentName;
    private String scopeName;
    private BigDecimal relationScore;
    private String relationSource;
    private String reason;
}
