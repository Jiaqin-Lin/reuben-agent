package com.reubenagent.document.model.route;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;

/**
 * Scope 路由候选。
 *
 * @author reuben
 * @since 2026-06-28
 */
@Data
@Builder
public class ScopeRouteCandidate {

    /** 范围编码 */
    private String scopeCode;

    /** 范围名称 */
    private String scopeName;

    /** 综合得分 */
    private BigDecimal score;

    /** 得分原因 */
    private String reason;
}
