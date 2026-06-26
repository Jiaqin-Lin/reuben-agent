package com.reubenagent.chat.model.orchestrate;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 文档导航决策 —— 由问题路由器对"问题 vs 文档结构"判断后产出。
 *
 * <p>对应 super-agent {@code DocumentNavigationDecision}，reuben 简化为：
 * 仅承载动作 + 范围模式 + 范围锚点（章节路径 / 父块ID），不携带 rerank 等执行细节。</p>
 *
 * @author reuben
 * @since 2026-06-25
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DocumentNavigationDecision {

    /** 导航动作 */
    private DocumentNavigationAction action;

    /** 检索范围模式 */
    private NavigationScopeMode scopeMode;

    /** 章节路径锚点（scopeMode=SECTION_SCOPE 时非空） */
    private String sectionPath;

    /** 父块ID锚点（scopeMode=PARENT_BLOCK_SCOPE 时非空） */
    private String parentBlockId;

    /** 决策理由（落 trace） */
    private String reason;

    /** 映射到的执行模式 */
    public com.reubenagent.chat.enums.ExecutionMode toExecutionMode() {
        if (action == null) {
            return com.reubenagent.chat.enums.ExecutionMode.RETRIEVAL;
        }
        return switch (action) {
            case DIRECT_RETRIEVAL -> com.reubenagent.chat.enums.ExecutionMode.RETRIEVAL;
            case LOCATE_THEN_RETRIEVE -> com.reubenagent.chat.enums.ExecutionMode.GRAPH_THEN_EVIDENCE;
        };
    }
}
