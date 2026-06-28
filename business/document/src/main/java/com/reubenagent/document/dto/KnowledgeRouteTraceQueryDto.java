package com.reubenagent.document.dto;

import jakarta.validation.constraints.Min;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class KnowledgeRouteTraceQueryDto {
    private String conversationId;
    private String mode;
    private Integer routeStatus;

    @Min(1)
    @Builder.Default
    private int pageNo = 1;

    @Min(1)
    @Builder.Default
    private int pageSize = 20;
}
