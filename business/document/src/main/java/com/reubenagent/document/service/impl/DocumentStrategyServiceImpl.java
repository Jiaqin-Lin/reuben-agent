package com.reubenagent.document.service.impl;

import com.reubenagent.common.enums.DocumentManageCode;
import com.reubenagent.common.exception.DocumentException;
import com.reubenagent.document.config.DocumentProperties;
import com.reubenagent.document.entity.Document;
import com.reubenagent.document.entity.DocumentStrategyPlan;
import com.reubenagent.document.entity.DocumentStrategyStep;
import com.reubenagent.document.entity.DocumentStructureNode;
import com.reubenagent.document.enums.DocumentChunkSourceTypeEnum;
import com.reubenagent.document.enums.DocumentFileTypeEnum;
import com.reubenagent.document.enums.DocumentStrategyPipelineTypeEnum;
import com.reubenagent.document.enums.DocumentStrategyRoleEnum;
import com.reubenagent.document.enums.DocumentStrategySourceTypeEnum;
import com.reubenagent.document.enums.DocumentStrategyTypeEnum;
import com.reubenagent.document.enums.DocumentStructureNodeTypeEnum;
import com.reubenagent.document.model.ChunkCandidate;
import com.reubenagent.document.model.DocumentParseResult;
import com.reubenagent.document.model.DocumentStrategyPlanDraft;
import com.reubenagent.document.model.DocumentStrategyStepDraft;
import com.reubenagent.document.model.ParentBlockCandidate;
import com.reubenagent.document.service.IDocumentStrategyService;
import com.reubenagent.document.service.IDocumentStructureNodeService;
import com.reubenagent.document.support.PromptTemplateService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 文档策略服务 —— 策略推荐 + 策略执行（分块引擎）。
 *
 * @author reuben
 * @since 2026-06-20
 */
@Slf4j
@Service
public class DocumentStrategyServiceImpl implements IDocumentStrategyService {

    private final DocumentProperties documentProperties;
    private final IDocumentStructureNodeService structureNodeService;
    private final PromptTemplateService promptTemplateService;
    private final ChatModel chatModel;

    public DocumentStrategyServiceImpl(
            DocumentProperties documentProperties,
            IDocumentStructureNodeService structureNodeService,
            PromptTemplateService promptTemplateService,
            @Qualifier("deepSeekChatModel") ChatModel chatModel) {
        this.documentProperties = documentProperties;
        this.structureNodeService = structureNodeService;
        this.promptTemplateService = promptTemplateService;
        this.chatModel = chatModel;
    }

    /** 预编译正则：句子边界（标点后可选的空白符） */
    private static final Pattern SENTENCE_SPLIT = Pattern.compile("(?<=[。.!?！？])\\s*");

    // ======================== 策略推荐 ========================

    @Override
    public DocumentStrategyPlanDraft recommendStrategy(Document document, DocumentParseResult parseResult) {
        DocumentFileTypeEnum fileType = DocumentFileTypeEnum.getFromCode(document.getFileType());

        boolean structureRecommended = shouldUseStructure(fileType, parseResult);
        boolean recursiveRecommended = shouldUseRecursive(parseResult);
        boolean semanticRecommended = shouldUseSemantic(parseResult);
        boolean llmRecommended = shouldUseLlm(parseResult);

        log.info("策略推荐分析: documentId={} structure={} recursive={} semantic={} llm={}",
                document.getId(), structureRecommended, recursiveRecommended, semanticRecommended, llmRecommended);

        // 父管道：始终 1 步
        List<DocumentStrategyTypeEnum> parentStrategyTypes = new ArrayList<>();
        Map<DocumentStrategyTypeEnum, String> parentReasonMap = new LinkedHashMap<>();
        List<String> reasonList = new ArrayList<>();

        if (structureRecommended) {
            parentStrategyTypes.add(DocumentStrategyTypeEnum.STRUCTURE);
            parentReasonMap.put(DocumentStrategyTypeEnum.STRUCTURE, "文档结构化程度高，按标题层级划分");
            reasonList.add("文档结构化程度高，按标题层级划分");
        } else {
            parentStrategyTypes.add(DocumentStrategyTypeEnum.RECURSIVE);
            parentReasonMap.put(DocumentStrategyTypeEnum.RECURSIVE, "文档无明显结构，递归固定长度划分");
            reasonList.add("文档无明显结构，递归固定长度划分");
        }

        // 子管道：1-2 步
        List<DocumentStrategyTypeEnum> childStrategyTypes = new ArrayList<>();
        Map<DocumentStrategyTypeEnum, String> childReasonMap = new LinkedHashMap<>();

        if (llmRecommended) {
            childStrategyTypes.add(DocumentStrategyTypeEnum.LLM);
            childReasonMap.put(DocumentStrategyTypeEnum.LLM, "文档内容质量较低，使用大模型语义感知切分");
            childStrategyTypes.add(DocumentStrategyTypeEnum.RECURSIVE);
            childReasonMap.put(DocumentStrategyTypeEnum.RECURSIVE, "递归固定长度兜底，处理大模型未覆盖的剩余段落");
            reasonList.add("文档内容质量较低，使用大模型语义感知切分");
            reasonList.add("递归固定长度兜底，处理大模型未覆盖的剩余段落");
        } else if (semanticRecommended) {
            childStrategyTypes.add(DocumentStrategyTypeEnum.SEMANTIC);
            childReasonMap.put(DocumentStrategyTypeEnum.SEMANTIC, "文档段落充足且质量良好，语义边界切分效果最佳");
            childStrategyTypes.add(DocumentStrategyTypeEnum.RECURSIVE);
            childReasonMap.put(DocumentStrategyTypeEnum.RECURSIVE, "递归固定长度兜底，确保超长段落被合理截断");
            reasonList.add("文档段落充足且质量良好，语义边界切分效果最佳");
            reasonList.add("递归固定长度兜底，确保超长段落被合理截断");
        } else {
            childStrategyTypes.add(DocumentStrategyTypeEnum.RECURSIVE);
            childReasonMap.put(DocumentStrategyTypeEnum.RECURSIVE, "递归固定长度作为通用回退策略");
            reasonList.add("递归固定长度作为通用回退策略");
        }

        // 构建草稿
        List<DocumentStrategyStepDraft> parentSteps = buildDraftSteps(
                DocumentStrategyPipelineTypeEnum.PARENT.getStringCode(), parentStrategyTypes, parentReasonMap);
        List<DocumentStrategyStepDraft> childSteps = buildDraftSteps(
                DocumentStrategyPipelineTypeEnum.CHILD.getStringCode(), childStrategyTypes, childReasonMap);

        String strategySnapshot = buildCombinedStrategySnapshot(parentSteps, childSteps);

        return DocumentStrategyPlanDraft.builder()
                .strategySnapshot(strategySnapshot)
                .recommendReason(String.join("；", reasonList))
                .parentSteps(parentSteps)
                .childSteps(childSteps)
                .build();
    }

    // ======================== 策略执行：buildParentBlocks ========================

    @Override
    public List<ParentBlockCandidate> buildParentBlocks(
            Document document,
            DocumentStrategyPlan plan,
            List<DocumentStrategyStep> steps,
            String parsedText) {

        if (parsedText == null || parsedText.isBlank()) {
            throw new DocumentException(DocumentManageCode.EMPTY_FILE, "解析文本为空，无法执行分块");
        }

        // 阶段 1：按管道类型分组
        List<DocumentStrategyStep> parentSteps = steps.stream()
                .filter(s -> DocumentStrategyPipelineTypeEnum.PARENT.getStringCode().equals(s.getPipelineType()))
                .collect(Collectors.toList());
        List<DocumentStrategyStep> childSteps = steps.stream()
                .filter(s -> DocumentStrategyPipelineTypeEnum.CHILD.getStringCode().equals(s.getPipelineType()))
                .collect(Collectors.toList());

        if (parentSteps.isEmpty() || childSteps.isEmpty()) {
            throw new DocumentException(DocumentManageCode.EMPTY_FILE,
                    String.format("策略步骤不完整: parentSteps=%d childSteps=%d",
                            parentSteps.size(), childSteps.size()));
        }

        // 阶段 2：加载结构节点
        List<DocumentStructureNode> structureNodes = structureNodeService.listDocumentNodes(
                document.getId(), null);
        log.info("加载结构节点: documentId={} nodeCount={}", document.getId(),
                structureNodes != null ? structureNodes.size() : 0);

        // 阶段 3：执行父管道 → parent seed 列表
        List<ChunkCandidate> parentSeeds = executeStepPipeline(
                parsedText, parentSteps, structureNodes, "parent");

        if (parentSeeds.isEmpty()) {
            log.warn("父管道无产出: documentId={}", document.getId());
            return Collections.emptyList();
        }

        // 阶段 4：对每个 parent seed 执行子管道 → child chunk 列表，组装 ParentBlockCandidate
        List<ParentBlockCandidate> blocks = new ArrayList<>();
        for (ChunkCandidate parentSeed : parentSeeds) {
            List<ChunkCandidate> childChunks = executeStepPipeline(
                    parentSeed.getText(), childSteps, structureNodes, "child");

            blocks.add(ParentBlockCandidate.builder()
                    .sectionPath(parentSeed.getSectionPath())
                    .structureNodeId(parentSeed.getStructureNodeId())
                    .structureNodeType(parentSeed.getStructureNodeType())
                    .canonicalPath(parentSeed.getCanonicalPath())
                    .itemIndex(parentSeed.getItemIndex())
                    .text(parentSeed.getText())
                    .sourceType(parentSeed.getSourceType())
                    .childChunks(childChunks)
                    .build());
        }

        // 阶段 5：去重
        List<ParentBlockCandidate> result = cleanupParentBlockList(blocks);
        log.info("分块执行完成: documentId={} parentCount={}", document.getId(), result.size());
        return result;
    }

    // ======================== 管线执行引擎 ========================

    /**
     * 按步骤角色驱动的管线执行：遍历 steps，按 role 决定如何处理每个 step 的产出。
     *
     * @param text  待切分文本
     * @param steps 管线步骤列表
     * @param nodes 结构节点（仅 STRUCTURE 策略使用）
     * @param stage 管线阶段标识（日志用）
     * @return 切分后的 chunk 列表
     */
    private List<ChunkCandidate> executeStepPipeline(String text,
                                                      List<DocumentStrategyStep> steps,
                                                      List<DocumentStructureNode> nodes,
                                                      String stage) {
        List<ChunkCandidate> result = new ArrayList<>();

        for (DocumentStrategyStep step : steps) {
            DocumentStrategyRoleEnum role = DocumentStrategyRoleEnum.getFromCode(step.getStrategyRole());

            // FALLBACK：仅在前序无产出时执行
            if (role == DocumentStrategyRoleEnum.FALLBACK && !result.isEmpty()) {
                log.debug("跳过兜底策略: stepNo={} 前序已有产出", step.getStepNo());
                continue;
            }

            List<ChunkCandidate> stepResult = executeSingleStep(text, step, nodes, result);

            if (stepResult == null || stepResult.isEmpty()) {
                log.debug("步骤无产出: stepNo={} role={} strategyType={}",
                        step.getStepNo(), role, step.getStrategyType());
                continue;
            }

            switch (role) {
                case PRIMARY:
                    result = stepResult;
                    break;
                case OPTIMIZE:
                    result = optimizeChunks(result, step);
                    result = cleanupChunkList(result);
                    break;
                case FALLBACK:
                    result = stepResult;
                    break;
                case ENHANCE:
                    result.addAll(stepResult);
                    result = cleanupChunkList(result);
                    break;
                default:
                    result = stepResult;
            }

            log.debug("步骤执行完成: stepNo={} role={} stage={} chunkCount={}",
                    step.getStepNo(), role, stage, result.size());
        }

        return result;
    }

    /**
     * 根据步骤的 strategyType 将文本路由到对应的切分策略。
     *
     * @param text         待切分文本
     * @param step         当前步骤
     * @param nodes        结构节点
     * @param currentChunks 当前已产出的 chunk（OPTIMIZE 步骤需要）
     * @return 切分结果
     */
    private List<ChunkCandidate> executeSingleStep(String text,
                                                    DocumentStrategyStep step,
                                                    List<DocumentStructureNode> nodes,
                                                    List<ChunkCandidate> currentChunks) {
        DocumentStrategyTypeEnum type = DocumentStrategyTypeEnum.getFromCode(step.getStrategyType());
        if (type == null) {
            log.warn("未知策略类型: stepNo={} strategyType={}", step.getStepNo(), step.getStrategyType());
            return Collections.emptyList();
        }

        return switch (type) {
            case STRUCTURE -> applyStructureChunking(text, step, nodes);
            case RECURSIVE -> applyRecursiveChunking(text, step);
            case SEMANTIC -> applySemanticChunking(text, step);
            case LLM -> applyLlmChunking(text, step);
        };
    }

    // ======================== 策略：结构化切分 ========================

    /**
     * 基于文档结构节点做切分。
     *
     * <p>PARENT 管道：以 CHAPTER 节点为边界，每个章节为一个 parent seed。
     * CHILD 管道：以段落（\\n\\n）为边界拆分文本为 child chunk。</p>
     */
    private List<ChunkCandidate> applyStructureChunking(String text,
                                                         DocumentStrategyStep step,
                                                         List<DocumentStructureNode> nodes) {
        boolean isParent = DocumentStrategyPipelineTypeEnum.PARENT.getStringCode()
                .equals(step.getPipelineType());

        if (isParent) {
            return structureParentChunking(text, nodes);
        } else {
            return structureChildChunking(text);
        }
    }

    /**
     * PARENT 管道结构化切分：每个章节节点作为一个父块种子。
     */
    private List<ChunkCandidate> structureParentChunking(String fullText,
                                                          List<DocumentStructureNode> nodes) {
        if (nodes == null || nodes.isEmpty()) {
            log.warn("结构节点为空，结构化父切分无产出");
            return Collections.emptyList();
        }

        // 构建 parentId → children 映射，用于聚合子树文本
        Map<Long, List<DocumentStructureNode>> childrenMap = new LinkedHashMap<>();
        for (DocumentStructureNode node : nodes) {
            if (node.getParentNodeId() != null) {
                childrenMap.computeIfAbsent(node.getParentNodeId(), k -> new ArrayList<>()).add(node);
            }
        }

        // 找出所有 CHAPTER 节点
        List<DocumentStructureNode> chapterNodes = nodes.stream()
                .filter(n -> DocumentStructureNodeTypeEnum.CHAPTER.getCode().equals(n.getNodeType()))
                .collect(Collectors.toList());

        if (chapterNodes.isEmpty()) {
            log.warn("无章节节点，结构化父切分无产出");
            return Collections.emptyList();
        }

        List<ChunkCandidate> candidates = new ArrayList<>();
        for (DocumentStructureNode chapter : chapterNodes) {
            // 聚合章节及其子树的全部文本
            String sectionText = collectSubtreeText(chapter, childrenMap);
            if (sectionText.isBlank()) {
                continue;
            }

            candidates.add(ChunkCandidate.builder()
                    .sectionPath(chapter.getSectionPath())
                    .structureNodeId(chapter.getId())
                    .structureNodeType(chapter.getNodeType())
                    .canonicalPath(chapter.getCanonicalPath())
                    .itemIndex(chapter.getNodeNo())
                    .text(sectionText)
                    .sourceType(DocumentChunkSourceTypeEnum.ORIGINAL.getCode())
                    .build());
        }

        log.debug("结构化父切分产出: chapterCount={}", candidates.size());
        return candidates;
    }

    /**
     * 递归收集节点及其子树的全部文本内容。
     */
    private String collectSubtreeText(DocumentStructureNode node,
                                       Map<Long, List<DocumentStructureNode>> childrenMap) {
        StringBuilder sb = new StringBuilder();
        if (node.getContentText() != null && !node.getContentText().isBlank()) {
            sb.append(node.getContentText().trim());
        }
        List<DocumentStructureNode> children = childrenMap.getOrDefault(node.getId(), Collections.emptyList());
        for (DocumentStructureNode child : children) {
            String childText = collectSubtreeText(child, childrenMap);
            if (!childText.isBlank()) {
                if (!sb.isEmpty()) {
                    sb.append("\n");
                }
                sb.append(childText);
            }
        }
        return sb.toString();
    }

    /**
     * CHILD 管道结构化切分：按段落（\\n\\n）拆分，过滤空行/噪声行。
     */
    private List<ChunkCandidate> structureChildChunking(String text) {
        String[] paragraphs = text.split("\\n{2,}");
        List<ChunkCandidate> candidates = new ArrayList<>();

        for (String para : paragraphs) {
            String trimmed = para.trim();
            if (trimmed.isEmpty() || isNoiseLine(trimmed)) {
                continue;
            }
            candidates.add(new ChunkCandidate(
                    null, trimmed, DocumentChunkSourceTypeEnum.ORIGINAL.getCode()));
        }

        return candidates;
    }

    // ======================== 策略：递归切分 ========================

    /**
     * 递归层级切分：段落 → 句子 → 固定窗口 + overlap。
     */
    private List<ChunkCandidate> applyRecursiveChunking(String text, DocumentStrategyStep step) {
        int maxChars = documentProperties.getStrategy().getRecursiveMaxChars();
        int overlapChars = documentProperties.getStrategy().getRecursiveOverlapChars();

        List<ChunkCandidate> candidates = new ArrayList<>();
        recursiveSplit(text, maxChars, overlapChars, candidates);
        return candidates;
    }

    /**
     * 递归切分：先按段落，再按句子，最后按固定窗口。
     */
    private void recursiveSplit(String text, int maxChars, int overlapChars,
                                 List<ChunkCandidate> result) {
        if (text == null || text.isBlank()) {
            return;
        }
        if (text.length() <= maxChars) {
            result.add(new ChunkCandidate(
                    null, text.trim(), DocumentChunkSourceTypeEnum.ORIGINAL.getCode()));
            return;
        }

        // 第一层：按段落切分
        String[] paragraphs = text.split("\\n{2,}");
        if (paragraphs.length > 1) {
            for (String para : paragraphs) {
                recursiveSplit(para.trim(), maxChars, overlapChars, result);
            }
            return;
        }

        // 第二层：按句子切分
        List<String> sentences = splitSentences(text);
        if (sentences.size() > 1) {
            StringBuilder buffer = new StringBuilder();
            for (String sentence : sentences) {
                if (buffer.length() + sentence.length() > maxChars && !buffer.isEmpty()) {
                    result.add(new ChunkCandidate(
                            null, buffer.toString().trim(),
                            DocumentChunkSourceTypeEnum.ORIGINAL.getCode()));
                    // overlap：保留最后 overlapChars 的内容衔接
                    String kept = buffer.length() > overlapChars
                            ? buffer.substring(buffer.length() - overlapChars)
                            : buffer.toString();
                    buffer = new StringBuilder(kept);
                }
                if (!buffer.isEmpty()) {
                    buffer.append(" ");
                }
                buffer.append(sentence);
            }
            if (!buffer.isEmpty()) {
                result.add(new ChunkCandidate(
                        null, buffer.toString().trim(),
                        DocumentChunkSourceTypeEnum.ORIGINAL.getCode()));
            }
            return;
        }

        // 第三层：固定窗口 + overlap
        int step = maxChars - overlapChars;
        if (step <= 0) {
            step = maxChars;
        }
        int start = 0;
        while (start < text.length()) {
            int end = Math.min(start + maxChars, text.length());
            String chunk = text.substring(start, end).trim();
            if (!chunk.isEmpty()) {
                result.add(new ChunkCandidate(
                        null, chunk, DocumentChunkSourceTypeEnum.ORIGINAL.getCode()));
            }
            start += step;
        }
    }

    // ======================== 策略：语义切分 ========================

    /**
     * 基于相邻句子 Jaccard 相似度的语义切分。
     *
     * <p>以句子为最小单元，计算相邻句间词袋 Jaccard 相似度，
     * 低于阈值的边界作为语义断点进行切分。</p>
     */
    private List<ChunkCandidate> applySemanticChunking(String text, DocumentStrategyStep step) {
        double threshold = documentProperties.getStrategy().getSemanticSimilarityThreshold();
        List<String> sentences = splitSentences(text);

        if (sentences.size() <= 1) {
            return List.of(new ChunkCandidate(
                    null, text.trim(), DocumentChunkSourceTypeEnum.ORIGINAL.getCode()));
        }

        // 找到语义断点
        List<Integer> breakpoints = new ArrayList<>();
        breakpoints.add(0); // 起始
        for (int i = 0; i < sentences.size() - 1; i++) {
            double similarity = jaccardSimilarity(sentences.get(i), sentences.get(i + 1));
            if (similarity < threshold) {
                breakpoints.add(i + 1);
            }
        }
        breakpoints.add(sentences.size()); // 结束

        // 按断点合并句子为 chunk，跳过过小块
        int minChars = 50; // 最小 chunk 字符数，避免碎片化
        List<ChunkCandidate> candidates = new ArrayList<>();
        StringBuilder buffer = new StringBuilder();
        int segmentStart = breakpoints.get(0);

        for (int i = 1; i < breakpoints.size(); i++) {
            int segmentEnd = breakpoints.get(i);
            StringBuilder segment = new StringBuilder();
            for (int j = segmentStart; j < segmentEnd; j++) {
                if (!segment.isEmpty()) {
                    segment.append(" ");
                }
                segment.append(sentences.get(j));
            }
            segmentStart = segmentEnd;

            String segText = segment.toString().trim();
            if (segText.isEmpty()) {
                continue;
            }

            // 如果当前 segment 过小，与 buffer 合并
            if (segText.length() < minChars && !buffer.isEmpty()) {
                buffer.append(" ").append(segText);
            } else if (segText.length() < minChars) {
                buffer.append(segText);
            } else {
                if (!buffer.isEmpty()) {
                    candidates.add(new ChunkCandidate(
                            null, buffer.toString().trim(),
                            DocumentChunkSourceTypeEnum.ORIGINAL.getCode()));
                }
                buffer = new StringBuilder(segText);
            }
        }
        if (!buffer.isEmpty()) {
            candidates.add(new ChunkCandidate(
                    null, buffer.toString().trim(),
                    DocumentChunkSourceTypeEnum.ORIGINAL.getCode()));
        }

        return candidates;
    }

    // ======================== 策略：LLM 切分 ========================

    /**
     * 使用大模型识别语义断点进行切分。
     *
     * <p>将文本按 LLM 输入长度限制分段，每段发送给 LLM 识别断点，
     * 解析返回的断点索引列表后切分。</p>
     */
    private List<ChunkCandidate> applyLlmChunking(String text, DocumentStrategyStep step) {
        int maxInputChars = documentProperties.getStrategy().getLlmMaxInputChars();

        if (text.length() <= maxInputChars) {
            return llmChunkSingleSegment(text);
        }

        // 文本过长时，先按段落粗切为多个 LLM 输入段
        List<String> segments = splitForLlmInput(text, maxInputChars);
        List<ChunkCandidate> allCandidates = new ArrayList<>();
        for (String segment : segments) {
            List<ChunkCandidate> segmentCandidates = llmChunkSingleSegment(segment);
            allCandidates.addAll(segmentCandidates);
        }
        return allCandidates;
    }

    /**
     * 对单段文本调用 LLM 识别语义断点。
     */
    private List<ChunkCandidate> llmChunkSingleSegment(String text) {
        List<String> sentences = splitSentences(text);
        if (sentences.size() <= 1) {
            return List.of(new ChunkCandidate(
                    null, text.trim(), DocumentChunkSourceTypeEnum.ORIGINAL.getCode()));
        }

        // 构建 sentence 索引视图供 LLM 参考
        StringBuilder indexedView = new StringBuilder();
        for (int i = 0; i < sentences.size(); i++) {
            indexedView.append("[").append(i).append("] ").append(sentences.get(i)).append("\n");
        }

        try {
            String promptText = promptTemplateService.render("document-llm-chunking",
                    Map.of("sentences", indexedView.toString(), "sentenceCount", String.valueOf(sentences.size())));

            Prompt prompt = new Prompt(new UserMessage(promptText));
            ChatResponse response = chatModel.call(prompt);
            String llmResponse = response.getResult().getOutput().getText();
            String content = llmResponse != null ? llmResponse : "";

            // 解析 LLM 返回的断点索引
            List<Integer> breakpoints = parseLlmBreakpoints(content, sentences.size());
            return buildChunksFromBreakpoints(sentences, breakpoints);
        } catch (Exception e) {
            log.error("LLM 切分失败，降级为递归切分", e);
            // 降级：按句子直接作为 chunk
            return sentences.stream()
                    .filter(s -> !s.isBlank())
                    .map(s -> new ChunkCandidate(
                            null, s.trim(), DocumentChunkSourceTypeEnum.ORIGINAL.getCode()))
                    .collect(Collectors.toList());
        }
    }

    /**
     * 解析 LLM 返回的断点索引。
     *
     * <p>期望 LLM 返回 JSON 数组或逗号分隔的数字列表，如 {@code [3,7,12]}。</p>
     */
    static List<Integer> parseLlmBreakpoints(String response, int maxIndex) {
        List<Integer> breakpoints = new ArrayList<>();
        try {
            // 尝试提取 JSON 数组
            String trimmed = response.trim();
            // 移除 markdown 代码块标记
            if (trimmed.startsWith("```")) {
                int endFence = trimmed.indexOf("\n");
                if (endFence > 0) {
                    trimmed = trimmed.substring(endFence + 1);
                }
                if (trimmed.endsWith("```")) {
                    trimmed = trimmed.substring(0, trimmed.length() - 3);
                }
            }

            // 提取方括号内容
            int bracketStart = trimmed.indexOf('[');
            int bracketEnd = trimmed.lastIndexOf(']');
            if (bracketStart >= 0 && bracketEnd > bracketStart) {
                trimmed = trimmed.substring(bracketStart + 1, bracketEnd);
            }

            // 按逗号/空格拆分提取数字
            for (String part : trimmed.split("[,\\s]+")) {
                part = part.trim();
                if (!part.isEmpty()) {
                    try {
                        int idx = Integer.parseInt(part);
                        if (idx > 0 && idx < maxIndex) {
                            breakpoints.add(idx);
                        }
                    } catch (NumberFormatException ignored) {
                        // 非数字 token 跳过
                    }
                }
            }
        } catch (Exception e) {
            log.warn("解析 LLM 断点返回失败，降级为整段单 chunk: {}", e.getMessage());
        }
        return breakpoints;
    }

    /**
     * 根据断点列表从句子数组构建 chunk。
     */
    static List<ChunkCandidate> buildChunksFromBreakpoints(List<String> sentences,
                                                            List<Integer> breakpoints) {
        if (breakpoints.isEmpty()) {
            // 无断点 → 整体作为一个 chunk
            String text = String.join(" ", sentences).trim();
            return List.of(new ChunkCandidate(
                    null, text, DocumentChunkSourceTypeEnum.ORIGINAL.getCode()));
        }

        List<Integer> allBreakpoints = new ArrayList<>();
        allBreakpoints.add(0);
        allBreakpoints.addAll(breakpoints.stream().sorted().collect(Collectors.toList()));
        allBreakpoints.add(sentences.size());

        List<ChunkCandidate> candidates = new ArrayList<>();
        for (int i = 0; i < allBreakpoints.size() - 1; i++) {
            int start = allBreakpoints.get(i);
            int end = allBreakpoints.get(i + 1);
            if (start >= end || start >= sentences.size()) {
                continue;
            }
            end = Math.min(end, sentences.size());
            StringBuilder chunk = new StringBuilder();
            for (int j = start; j < end; j++) {
                if (!chunk.isEmpty()) {
                    chunk.append(" ");
                }
                chunk.append(sentences.get(j));
            }
            String chunkText = chunk.toString().trim();
            if (!chunkText.isEmpty()) {
                candidates.add(new ChunkCandidate(
                        null, chunkText, DocumentChunkSourceTypeEnum.ORIGINAL.getCode()));
            }
        }
        return candidates;
    }

    /**
     * 将超长文本按段落粗切为 LLM 可接受的输入段。
     */
    private List<String> splitForLlmInput(String text, int maxChars) {
        List<String> segments = new ArrayList<>();
        String[] paragraphs = text.split("\\n{2,}");
        StringBuilder buffer = new StringBuilder();

        for (String para : paragraphs) {
            String trimmed = para.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            if (buffer.length() + trimmed.length() > maxChars && !buffer.isEmpty()) {
                segments.add(buffer.toString().trim());
                buffer = new StringBuilder();
            }
            if (!buffer.isEmpty()) {
                buffer.append("\n\n");
            }
            buffer.append(trimmed);
        }
        if (!buffer.isEmpty()) {
            segments.add(buffer.toString().trim());
        }
        return segments;
    }

    // ======================== OPTIMIZE 步骤：合并/拆分 ========================

    /**
     * 优化 chunk 列表：将过小的相邻 chunk 合并，将过大的 chunk 拆分。
     */
    private List<ChunkCandidate> optimizeChunks(List<ChunkCandidate> chunks,
                                                 DocumentStrategyStep step) {
        if (chunks == null || chunks.isEmpty()) {
            return chunks;
        }

        int maxChars = documentProperties.getStrategy().getRecursiveMaxChars();
        int overlapChars = documentProperties.getStrategy().getRecursiveOverlapChars();
        int minChars = Math.max(50, maxChars / 10);

        List<ChunkCandidate> optimized = new ArrayList<>();
        ChunkCandidate buffer = null;

        for (ChunkCandidate chunk : chunks) {
            if (chunk.getText() == null || chunk.getText().isBlank()) {
                continue;
            }

            // 过大 → 拆分
            if (chunk.getText().length() > maxChars) {
                if (buffer != null) {
                    optimized.add(buffer);
                    buffer = null;
                }
                List<ChunkCandidate> splits = new ArrayList<>();
                recursiveSplit(chunk.getText(), maxChars, overlapChars, splits);
                // 继承父 chunk 的元数据
                for (ChunkCandidate split : splits) {
                    split.setSectionPath(chunk.getSectionPath());
                    split.setStructureNodeId(chunk.getStructureNodeId());
                    split.setStructureNodeType(chunk.getStructureNodeType());
                    split.setCanonicalPath(chunk.getCanonicalPath());
                    split.setItemIndex(chunk.getItemIndex());
                }
                optimized.addAll(splits);
                continue;
            }

            // 过小 → 尝试与 buffer 合并
            if (chunk.getText().length() < minChars) {
                if (buffer == null) {
                    buffer = ChunkCandidate.builder()
                            .sectionPath(chunk.getSectionPath())
                            .text(chunk.getText())
                            .sourceType(chunk.getSourceType())
                            .build();
                } else {
                    buffer.setText(buffer.getText() + "\n" + chunk.getText());
                }
                continue;
            }

            // 正常大小
            if (buffer != null) {
                // 检查 buffer 是否可以独立成块或与当前 chunk 合并
                if (buffer.getText().length() < minChars) {
                    chunk.setText(buffer.getText() + "\n" + chunk.getText());
                } else {
                    optimized.add(buffer);
                }
                buffer = null;
            }
            optimized.add(chunk);
        }

        if (buffer != null) {
            optimized.add(buffer);
        }

        return optimized;
    }

    // ======================== 辅助方法 ========================

    /**
     * ChunkCandidate 去重：基于 (sectionPath, text) 组合去重，过滤空文本。
     */
    List<ChunkCandidate> cleanupChunkList(List<ChunkCandidate> chunks) {
        if (chunks == null || chunks.isEmpty()) {
            return Collections.emptyList();
        }

        Set<String> seen = new LinkedHashSet<>();
        List<ChunkCandidate> result = new ArrayList<>();

        for (ChunkCandidate chunk : chunks) {
            if (chunk.getText() == null || chunk.getText().isBlank()) {
                continue;
            }
            String key = (chunk.getSectionPath() != null ? chunk.getSectionPath() : "")
                    + "::" + chunk.getText();
            if (seen.add(key)) {
                result.add(chunk);
            }
        }
        return result;
    }

    /**
     * ParentBlockCandidate 去重：基于 sectionPath 合并同路径的 parent block。
     */
    List<ParentBlockCandidate> cleanupParentBlockList(List<ParentBlockCandidate> blocks) {
        if (blocks == null || blocks.isEmpty()) {
            return Collections.emptyList();
        }

        Map<String, ParentBlockCandidate> merged = new LinkedHashMap<>();
        for (ParentBlockCandidate block : blocks) {
            String key = block.getSectionPath() != null ? block.getSectionPath() : block.getText();
            if (key == null || key.isBlank()) {
                continue;
            }

            if (merged.containsKey(key)) {
                ParentBlockCandidate existing = merged.get(key);
                // 合并 child chunks
                List<ChunkCandidate> combined = new ArrayList<>(existing.getChildChunks());
                if (block.getChildChunks() != null) {
                    combined.addAll(block.getChildChunks());
                }
                existing.setChildChunks(cleanupChunkList(combined));
            } else {
                merged.put(key, block);
            }
        }

        return new ArrayList<>(merged.values());
    }

    /**
     * 从结构节点向上回溯构造面包屑 sectionPath。
     */
    private String resolveSectionPath(DocumentStructureNode node,
                                       Map<Long, DocumentStructureNode> nodeMap) {
        if (node.getSectionPath() != null && !node.getSectionPath().isBlank()) {
            return node.getSectionPath();
        }

        // 向上回溯构建路径
        List<String> parts = new ArrayList<>();
        DocumentStructureNode current = node;
        while (current != null) {
            String label = current.getTitle() != null ? current.getTitle() : current.getNodeCode();
            if (label != null && !label.isBlank()) {
                parts.add(label);
            }
            current = current.getParentNodeId() != null
                    ? nodeMap.get(current.getParentNodeId()) : null;
        }
        Collections.reverse(parts);
        return String.join(" > ", parts);
    }

    /**
     * 路径片段归一化：去 # 标记、去多余空格、小写。
     */
    static String resolveCanonicalPath(String path) {
        if (path == null) {
            return "";
        }
        return path.replace("#", "")
                .replaceAll("\\s+", " ")
                .trim()
                .toLowerCase();
    }

    // ======================== 文本工具方法 ========================

    /**
     * 将文本按句子边界拆分为句子列表。
     */
    static List<String> splitSentences(String text) {
        if (text == null || text.isBlank()) {
            return Collections.emptyList();
        }
        String[] parts = SENTENCE_SPLIT.split(text);
        return Arrays.stream(parts)
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toList());
    }

    /**
     * 计算两个文本的词袋 Jaccard 相似度。
     *
     * @return 0.0 ~ 1.0，值越高表示越相似
     */
    static double jaccardSimilarity(String a, String b) {
        if (a == null || b == null || a.isBlank() || b.isBlank()) {
            return 0.0;
        }
        Set<String> setA = tokenize(a);
        Set<String> setB = tokenize(b);
        if (setA.isEmpty() && setB.isEmpty()) {
            return 1.0;
        }
        Set<String> intersection = new LinkedHashSet<>(setA);
        intersection.retainAll(setB);
        Set<String> union = new LinkedHashSet<>(setA);
        union.addAll(setB);
        return union.isEmpty() ? 0.0 : (double) intersection.size() / union.size();
    }

    /**
     * 中文分词 tokenize：按 1-2 gram 切词。
     */
    static Set<String> tokenize(String text) {
        Set<String> tokens = new LinkedHashSet<>();
        String cleaned = text.replaceAll("[\\p{Punct}\\p{Space}]+", "").toLowerCase();
        if (cleaned.isEmpty()) {
            return tokens;
        }
        // unigram
        for (int i = 0; i < cleaned.length(); i++) {
            tokens.add(String.valueOf(cleaned.charAt(i)));
        }
        // bigram
        for (int i = 0; i < cleaned.length() - 1; i++) {
            tokens.add(cleaned.substring(i, i + 2));
        }
        return tokens;
    }

    /**
     * 判断是否为噪声行（页码、页眉、分隔线等）。
     */
    static boolean isNoiseLine(String line) {
        if (line == null || line.isBlank()) {
            return true;
        }
        String trimmed = line.trim();
        // 纯数字（页码）
        if (trimmed.matches("^\\d{1,4}$")) {
            return true;
        }
        // 分隔线模式
        if (trimmed.matches("^[-=_*]{3,}$")) {
            return true;
        }
        // 仅包含特殊字符
        if (trimmed.matches("^[\\p{Punct}\\s]+$")) {
            return true;
        }
        return false;
    }

    // ======================== 推荐辅助方法（已有） ========================

    private boolean shouldUseStructure(DocumentFileTypeEnum fileType, DocumentParseResult parseResult) {
        if (fileType == null) {
            return false;
        }
        boolean supportedType = switch (fileType) {
            case PDF, DOC, DOCX, TXT, MD, HTML -> true;
            default -> false;
        };
        if (!supportedType) {
            return false;
        }
        int structureLevel = parseResult.getStructureLevel() != null ? parseResult.getStructureLevel() : 0;
        int headingCount = parseResult.getHeadingCount() != null ? parseResult.getHeadingCount() : 0;
        return structureLevel >= 2 || headingCount >= 2;
    }

    private boolean shouldUseRecursive(DocumentParseResult parseResult) {
        int threshold = documentProperties.getStrategy().getRecursiveMaxChars();
        int charCount = parseResult.getCharCount() != null ? parseResult.getCharCount() : 0;
        int maxParagraphLength = parseResult.getMaxParagraphLength() != null ? parseResult.getMaxParagraphLength() : 0;
        return charCount >= threshold || maxParagraphLength >= threshold;
    }

    private boolean shouldUseSemantic(DocumentParseResult parseResult) {
        int minChars = documentProperties.getStrategy().getSemanticMinChars();
        int charCount = parseResult.getCharCount() != null ? parseResult.getCharCount() : 0;
        int paragraphCount = parseResult.getParagraphCount() != null ? parseResult.getParagraphCount() : 0;
        int contentQuality = parseResult.getContentQualityLevel() != null ? parseResult.getContentQualityLevel() : 0;
        return charCount >= minChars && paragraphCount >= 3 && contentQuality >= 3;
    }

    private boolean shouldUseLlm(DocumentParseResult parseResult) {
        if (!Boolean.TRUE.equals(documentProperties.getStrategy().getRecommendLlmWhenLowQuality())) {
            return false;
        }
        int minChars = documentProperties.getStrategy().getSemanticMinChars();
        int charCount = parseResult.getCharCount() != null ? parseResult.getCharCount() : 0;
        int contentQuality = parseResult.getContentQualityLevel() != null ? parseResult.getContentQualityLevel() : 0;
        return contentQuality <= 1 && charCount >= minChars;
    }

    private List<DocumentStrategyStepDraft> buildDraftSteps(
            String pipelineType,
            List<DocumentStrategyTypeEnum> strategyTypes,
            Map<DocumentStrategyTypeEnum, String> reasonMap) {

        List<DocumentStrategyStepDraft> steps = new ArrayList<>();
        for (int i = 0; i < strategyTypes.size(); i++) {
            DocumentStrategyTypeEnum type = strategyTypes.get(i);
            DocumentStrategyRoleEnum role = resolveRole(type, i);
            steps.add(DocumentStrategyStepDraft.builder()
                    .pipelineType(pipelineType)
                    .strategyType(type.getCode())
                    .strategyRole(role.getCode())
                    .sourceType(DocumentStrategySourceTypeEnum.SYSTEM_RECOMMEND.getCode())
                    .recommendReason(reasonMap.getOrDefault(type, ""))
                    .build());
        }
        return steps;
    }

    private DocumentStrategyRoleEnum resolveRole(DocumentStrategyTypeEnum strategyType, int index) {
        if (index == 0) {
            return DocumentStrategyRoleEnum.PRIMARY;
        }
        return switch (strategyType) {
            case RECURSIVE -> DocumentStrategyRoleEnum.FALLBACK;
            case SEMANTIC -> DocumentStrategyRoleEnum.OPTIMIZE;
            case LLM -> DocumentStrategyRoleEnum.ENHANCE;
            default -> DocumentStrategyRoleEnum.FALLBACK;
        };
    }

    private String buildCombinedStrategySnapshot(List<DocumentStrategyStepDraft> parentSteps,
                                                  List<DocumentStrategyStepDraft> childSteps) {
        String parentPart = "PARENT:" + parentSteps.stream()
                .map(s -> s.getStrategyType().toString())
                .collect(Collectors.joining(","));
        String childPart = "CHILD:" + childSteps.stream()
                .map(s -> s.getStrategyType().toString())
                .collect(Collectors.joining(","));
        return parentPart + ";" + childPart;
    }
}
