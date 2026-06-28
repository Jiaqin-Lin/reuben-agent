package com.reubenagent.chat.orchestrate;

import com.reubenagent.chat.config.ChatProperties;
import com.reubenagent.document.model.graph.GraphItem;
import com.reubenagent.document.model.graph.GraphItemWithContext;
import com.reubenagent.document.model.graph.GraphQueryResult;
import com.reubenagent.document.model.graph.GraphSection;
import com.reubenagent.document.model.graph.GraphSectionWithChildren;
import com.reubenagent.document.model.graph.GraphSectionWithSiblings;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 图答案渲染器 —— 将 {@link GraphQueryResult} 等图查询结果渲染为面向用户的文本。
 *
 * <p>区分两类问题：
 * <ul>
 *   <li>邻接问题（上一节/下一节）→ 渲染目标 + 父 + 前后兄弟；</li>
 *   <li>大纲/子章节问题 → 渲染目标 + 子章节列表；</li>
 *   <li>条目问题 → 渲染目标条目 + 命中条目；</li>
 *   <li>默认 → 渲染章节标题 + 截断正文。</li>
 * </ul>
 * 关键词列表从 {@link ChatProperties.Navigation} 读取，可配置覆盖。</p>
 *
 * @author reuben
 * @since 2026-06-28
 */
@Slf4j
@Service
@AllArgsConstructor
public class GraphAnswerRenderer {

    private static final int CONTENT_TRUNCATE = 600;

    private final ChatProperties properties;

    /** 问题是否为邻接查询（上一节/下一节/属于哪个章节） */
    public boolean asksAdjacency(String question) {
        return matches(question, properties.getNavigation().getAdjacencyHints());
    }

    /** 问题是否为大纲/子章节查询 */
    public boolean asksChildren(String question) {
        return matches(question, properties.getNavigation().getOutlineHints());
    }

    /** 问题是否为条目查询 */
    public boolean asksItem(String question) {
        return matches(question, properties.getNavigation().getItemHints());
    }

    // ============ GRAPH_ONLY 渲染 ============

    /** GRAPH_ONLY 邻接渲染：目标 + 父 + 前后兄弟 */
    public String renderAdjacency(GraphSectionWithSiblings result) {
        if (result == null || result.getSection() == null) {
            return "未找到相关章节。";
        }
        GraphSection section = result.getSection();
        StringBuilder sb = new StringBuilder(256);
        sb.append("当前章节：").append(section.displayTitle()).append('\n');
        if (result.getParent() != null) {
            sb.append("所属父章节：").append(result.getParent().displayTitle()).append('\n');
        }
        if (result.getPreviousSibling() != null) {
            sb.append("上一节：").append(result.getPreviousSibling().displayTitle()).append('\n');
        } else {
            sb.append("上一节：（已是同级首项）\n");
        }
        if (result.getNextSibling() != null) {
            sb.append("下一节：").append(result.getNextSibling().displayTitle()).append('\n');
        } else {
            sb.append("下一节：（已是同级末项）\n");
        }
        return sb.toString();
    }

    /** GRAPH_ONLY 子章节渲染：目标 + 子章节列表 */
    public String renderChildren(GraphSectionWithChildren result) {
        if (result == null || result.getSection() == null) {
            return "未找到相关章节。";
        }
        GraphSection section = result.getSection();
        List<GraphSection> children = result.getChildren();
        StringBuilder sb = new StringBuilder(256);
        sb.append("章节「").append(section.displayTitle()).append("」包含以下子章节：\n");
        if (children == null || children.isEmpty()) {
            sb.append("（该章节没有子章节）\n");
            return sb.toString();
        }
        for (int i = 0; i < children.size(); i++) {
            sb.append(i + 1).append(". ").append(children.get(i).displayTitle()).append('\n');
        }
        return sb.toString();
    }

    /** GRAPH_ONLY 默认渲染：章节标题 + 截断正文 */
    public String renderSectionContent(GraphSection section) {
        if (section == null) {
            return "未找到相关章节。";
        }
        StringBuilder sb = new StringBuilder(256);
        sb.append("章节：").append(section.displayTitle()).append('\n');
        String content = section.getContentText();
        if (content != null && !content.isBlank()) {
            sb.append(truncate(content, CONTENT_TRUNCATE));
        } else {
            sb.append("（该章节暂无正文内容）");
        }
        return sb.toString();
    }

    // ============ GRAPH_THEN_EVIDENCE 渲染 ============

    /** GRAPH_THEN_EVIDENCE 条目渲染：有目标 item → 第X步；有命中 → 命中列表；默认 → 正文 */
    public String renderItemEvidence(GraphItemWithContext result) {
        if (result == null) {
            return "未找到相关条目。";
        }
        StringBuilder sb = new StringBuilder(256);
        if (result.getSection() != null) {
            sb.append("所属章节：").append(result.getSection().displayTitle()).append('\n');
        }
        GraphItem target = result.getItem();
        if (target != null) {
            Integer idx = target.getItemIndex();
            sb.append(idx != null ? "第" + idx + "项：" : "目标条目：")
                    .append(target.displayText()).append('\n');
            return sb.toString();
        }
        List<GraphItem> siblings = result.getSiblingItems();
        if (siblings != null && !siblings.isEmpty()) {
            sb.append("该章节包含以下条目：\n");
            for (GraphItem item : siblings) {
                Integer idx = item.getItemIndex();
                sb.append(idx != null ? idx + ". " : "- ").append(item.displayText()).append('\n');
            }
            return sb.toString();
        }
        if (result.getSection() != null) {
            sb.append(truncate(result.getSection().getContentText(), CONTENT_TRUNCATE));
        }
        return sb.toString();
    }

    // ============ 工具 ============

    private boolean matches(String question, List<String> hints) {
        if (question == null || question.isBlank() || hints == null || hints.isEmpty()) {
            return false;
        }
        for (String hint : hints) {
            if (hint != null && !hint.isBlank() && question.contains(hint)) {
                return true;
            }
        }
        return false;
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max) + "……";
    }
}
