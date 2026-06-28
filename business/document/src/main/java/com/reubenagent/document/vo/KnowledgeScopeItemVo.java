package com.reubenagent.document.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class KnowledgeScopeItemVo {
    private Long id;
    private String scopeCode;
    private String scopeName;
    private String parentScopeCode;
    private String description;
    private String aliases;
    private String examples;
    private Integer sortOrder;
}
