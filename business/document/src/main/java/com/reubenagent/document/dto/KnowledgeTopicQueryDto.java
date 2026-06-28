package com.reubenagent.document.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class KnowledgeTopicQueryDto {
    /** 按 scopeCode 筛选，为空时返回全部 */
    private String scopeCode;
}
