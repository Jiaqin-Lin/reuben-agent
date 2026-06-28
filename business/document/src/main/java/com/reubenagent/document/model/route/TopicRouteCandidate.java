package com.reubenagent.document.model.route;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;

/**
 * Topic 路由候选。
 *
 * @author reuben
 * @since 2026-06-28
 */
@Data
@Builder
public class TopicRouteCandidate {

    /** 主题编码 */
    private String topicCode;

    /** 主题名称 */
    private String topicName;

    /** 所属 scopeCode */
    private String scopeCode;

    /** 综合得分 */
    private BigDecimal score;

    /** 得分原因 */
    private String reason;
}
