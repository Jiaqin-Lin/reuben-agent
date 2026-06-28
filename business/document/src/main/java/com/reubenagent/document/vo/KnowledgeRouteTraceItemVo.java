package com.reubenagent.document.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.math.BigDecimal;
import java.util.Date;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class KnowledgeRouteTraceItemVo {
    private Long id;
    private String conversationId;
    private Long turnId;
    private String question;
    private String rewriteQuestion;
    private String mode;
    private String topScopesJson;
    private String topTopicsJson;
    private String topDocumentsJson;
    private Long selectedDocumentId;
    private Integer hitSelectedDocument;
    private BigDecimal confidence;
    private Integer routeStatus;
    private String errorMsg;
    private Date createTime;
}
