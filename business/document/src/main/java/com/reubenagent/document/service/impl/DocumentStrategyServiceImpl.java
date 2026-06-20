package com.reubenagent.document.service.impl;

import com.reubenagent.document.config.DocumentProperties;
import com.reubenagent.document.entity.Document;
import com.reubenagent.document.enums.DocumentFileTypeEnum;
import com.reubenagent.document.enums.DocumentStrategyPipelineTypeEnum;
import com.reubenagent.document.enums.DocumentStrategyRoleEnum;
import com.reubenagent.document.enums.DocumentStrategySourceTypeEnum;
import com.reubenagent.document.enums.DocumentStrategyTypeEnum;
import com.reubenagent.document.model.DocumentParseResult;
import com.reubenagent.document.model.DocumentStrategyPlanDraft;
import com.reubenagent.document.model.DocumentStrategyStepDraft;
import com.reubenagent.document.service.IDocumentStrategyService;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 文档策略推荐 —— 基于解析统计指标，规则驱动推荐最优分块方案。
 *
 * @author reuben
 * @since 2026-06-20
 */
@Slf4j
@AllArgsConstructor
@Service
public class DocumentStrategyServiceImpl implements IDocumentStrategyService {

    private final DocumentProperties documentProperties;

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

    // ============ 规则判断 ============

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

    // ============ 辅助方法 ============

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
