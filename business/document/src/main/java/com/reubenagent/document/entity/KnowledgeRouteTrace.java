package com.reubenagent.document.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.reubenagent.common.data.BaseTableData;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * 知识路由追踪 —— 映射 {@code reuben_agent_knowledge_route_trace} 表。
 *
 * <p>记录每次知识路由的完整决策过程，包括候选范围/主题/文档、
 * 最终选择、置信度及命中情况，用于后续分析和调优。</p>
 *
 * @author reuben
 * @since 2026-06-28
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("reuben_agent_knowledge_route_trace")
@EqualsAndHashCode(callSuper = true)
public class KnowledgeRouteTrace extends BaseTableData {

    @TableId(type = IdType.INPUT)
    private Long id;

    /** 会话 ID */
    private String conversationId;

    /** 轮次 ID */
    private Long turnId;

    /** 原始问题 */
    private String question;

    /** 改写后问题 */
    private String rewriteQuestion;

    /** 路由模式：shadow/auto */
    private String mode;

    /** 候选知识范围（JSON） */
    private String topScopesJson;

    /** 候选主题（JSON） */
    private String topTopicsJson;

    /** 候选文档（JSON） */
    private String topDocumentsJson;

    /** 实际选用的文档 ID */
    private Long selectedDocumentId;

    /** 候选是否命中实际选用文档 */
    private Integer hitSelectedDocument;

    /** 整体置信度 */
    private BigDecimal confidence;

    /** 路由状态：1=成功 2=低置信 3=失败 */
    private Integer routeStatus;

    /** 失败原因 */
    private String errorMsg;
}
