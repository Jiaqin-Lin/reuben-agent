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
 *   <li><b>Stage 3</b> — {@link DocumentStructureHierarchyResolver}：信号 → 结构树 ✅</li>
 *   <li><b>Stage 4</b> — {@link DocumentStructureTreeValidator}：校验修复 ✅</li>
 * </ol>
 *
 * <p>四阶段完成后产出可直接用于切片策略和索引构建的最终结构树。</p>
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
    private final DocumentStructureHierarchyResolver hierarchyResolver;
    private final DocumentStructureTreeValidator treeValidator;

    /**
     * 从文档纯文本中提取结构节点列表。
     *
     * @param documentTitle 文档标题
     * @param parsedText    Tika 提取后的纯文本内容
     * @return 结构节点列表（空文本时返回 ROOT 兜底，正常文本返回四阶段验证修复后的最终节点）
     */
    public List<DocumentIntermediateStructureNode> extract(String documentTitle, String parsedText) {
        String normalizedTitle = StringUtils.defaultIfBlank(documentTitle, "文档").trim();
        String normalizedText = StringUtils.defaultIfBlank(parsedText, "").trim();

        log.debug("结构提取开始: title={}, textLen={}", normalizedTitle, normalizedText.length());

        // 空文本兜底：返回只含一个根节点的列表
        if (normalizedText.isBlank()) {
            return List.of(DocumentIntermediateStructureNode.builder()
                    .nodeNo(1)
                    .nodeType(DocumentStructureNodeTypeEnum.ROOT.getCode())
                    .depth(0)
                    .nodeCode("")
                    .title(normalizedTitle)
                    .anchorText(normalizedTitle)
                    .canonicalPath("/document")
                    .sectionPath("")
                    .contentText("")
                    .numericPath(new ArrayList<>())
                    .sourceFamily("document")
                    .confidence(1.0D)
                    .build());
        }

        // Stage 1: 规则引擎信号提取
        DocumentStructureNodeSignalBatch signalBatch =
                documentStructureNodeSignalExtractor.extract(normalizedTitle, normalizedText);

        // Stage 2: LLM 歧义消解（HEADING_CANDIDATE → HEADING / LIST_ITEM / BODY）
        List<DocumentStructureNodeSignal> resolvedSignals = ambiguityResolver.resolve(
                normalizedTitle,
                signalBatch.contextLines(),
                signalBatch.signals());

        // Stage 3: 层级解析 → 信号列表归并为树形草稿
        List<DocumentIntermediateStructureNode> drafts =
                hierarchyResolver.resolve(normalizedTitle, resolvedSignals);

        // Stage 4: 树验证修复 → 重建兄弟链接、规范化路径、折叠重复标题
        List<DocumentIntermediateStructureNode> validated =
                treeValidator.validateAndBuild(normalizedTitle, drafts);

        log.debug("Stage 1-4 完成: {} 条信号 → {} 个节点（验证修复后）",
                resolvedSignals.size(), validated.size());
        return validated;
    }
}
