package com.reubenagent.chat.model.orchestrate;

import com.reubenagent.chat.enums.ExecutionMode;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * 文档导航决策 —— 由问题路由器对"问题 vs 文档结构"判断后产出。
 *
 * <p>对标 super-agent {@code DocumentNavigationDecision}：承载动作 + 范围模式 + 范围锚点
 * （章节路径 / 父块ID）+ 结构锚点 + 条目锚点 + 检索计划 + 检索关键词提示 + 软章节提示。</p>
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

    /** 结构锚点：目标章节定位（root/target/nodeId/canonicalPath/scopeMode） */
    private ConversationStructureAnchor structureAnchor;

    /** 条目锚点：目标步骤/条目定位 */
    private ConversationItemAnchor itemAnchor;

    /** 检索计划：改写主问题 + 子问题 */
    private ConversationRetrievalPlan retrievalPlan;

    /** 检索关键词提示（供证据检索加权） */
    @Builder.Default
    private List<String> queryContextHints = new ArrayList<>();

    /** RETRIEVAL 模式的软章节提示（不硬过滤，仅提示） */
    @Builder.Default
    private List<String> softSectionHints = new ArrayList<>();

    /** 决策理由（落 trace） */
    private String reason;

    /** 映射到的执行模式 */
    public ExecutionMode toExecutionMode() {
        if (action == null) {
            return ExecutionMode.RETRIEVAL;
        }
        return action.toExecutionMode();
    }
}
