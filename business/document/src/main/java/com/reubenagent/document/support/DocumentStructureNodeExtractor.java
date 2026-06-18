package com.reubenagent.document.support;

import com.reubenagent.document.enums.DocumentStructureNodeTypeEnum;
import com.reubenagent.document.model.DocumentIntermediateStructureNode;
import com.reubenagent.document.model.DocumentStructureNodeSignal;
import com.reubenagent.document.model.DocumentStructureNodeSignalBatch;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 文档结构节点提取器 —— 将信号列表归并为结构节点树（Signal → StructureNode）。
 *
 * <p>四阶段管线（与 super-agent 对齐）：</p>
 * <ol>
 *   <li><b>Stage 1</b> — {@link DocumentStructureNodeSignalExtractor}：规则引擎逐行分类 ✅</li>
 *   <li><b>Stage 2</b> — {@link DocumentStructureNodeAmbiguityResolver}：LLM 二次判定模糊信号 ✅</li>
 *   <li><b>Stage 3</b> — hierarchyResolver：信号 → 结构树（TODO）</li>
 *   <li><b>Stage 4</b> — treeValidator：校验修复（TODO）</li>
 * </ol>
 *
 * <p>空文本时返回仅含 ROOT 节点的兜底列表。</p>
 *
 * @author reuben
 * @since 2026-06-14
 */
@Slf4j
@AllArgsConstructor
@Component
public class DocumentStructureNodeExtractor {

    private final DocumentStructureNodeSignalExtractor documentStructureNodeSignalExtractor;
    private final DocumentStructureNodeAmbiguityResolver ambiguityResolver;

    /**
     * 从文档纯文本中提取结构节点列表。
     *
     * <p>空文本兜底：返回仅包含一个 ROOT 节点的列表，确保调用方始终拿到有效结构树。</p>
     *
     * @param documentTitle 文档标题
     * @param parsedText    Tika 提取后的纯文本内容
     * @return 结构节点列表（当前为空文本时返回 ROOT 兜底，正常文本返回空列表 —— TODO 待实现完整管线）
     */
    public List<DocumentIntermediateStructureNode> extract(String documentTitle, String parsedText) {
        String normalizedTitle = StringUtils.defaultIfBlank(documentTitle, "文档").trim();
        String normalizedText = StringUtils.defaultIfBlank(parsedText, "").trim();

        // 空文本兜底：返回只含一个根节点的列表
        if (normalizedText.isBlank()) {
            return List.of(new DocumentIntermediateStructureNode(
                    1,
                    DocumentStructureNodeTypeEnum.ROOT.getCode(),
                    null,   // parentNodeNo —— 根节点无父节点
                    null,   // prevSiblingNodeNo —— 孤立节点无兄弟
                    null,   // nextSiblingNodeNo
                    0,      // depth —— 根节点深度为 0
                    "",
                    normalizedTitle,
                    normalizedTitle,
                    "/document",
                    "",
                    "",
                    null    // itemIndex —— 非列表项无序号
            ));
        }

        // Stage 1: 规则引擎信号提取
        DocumentStructureNodeSignalBatch signalBatch =
                documentStructureNodeSignalExtractor.extract(documentTitle, parsedText);

        // Stage 2: LLM 歧义消解（HEADING_CANDIDATE → HEADING / LIST_ITEM / BODY）
        List<DocumentStructureNodeSignal> resolvedSignals = ambiguityResolver.resolve(
                normalizedTitle,
                signalBatch.contextLines(),
                signalBatch.signals());

        // TODO Stage 3/4: hierarchyResolver + treeValidator（后续实现）
        // 1. 遍历 resolvedSignals，按 HEADING 信号确定层级和父子关系
        // 2. 标题间的 BODY/STEP_ITEM/LIST_ITEM 归入对应叶子节点 contentText
        // 3. 计算各节点的 nodeCode / canonicalPath / sectionPath
        // 4. 构建双向链表（prevSiblingNodeNo / nextSiblingNodeNo）

        log.debug("Stage 1+2 完成: 共 {} 条信号（含虚拟标题），待实现结构节点归并",
                resolvedSignals.size());
        return new ArrayList<>();
    }
}