package com.reubenagent.document.service.impl;

import com.alibaba.fastjson.JSON;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.reubenagent.document.entity.Document;
import com.reubenagent.document.entity.DocumentProfile;
import com.reubenagent.document.entity.DocumentStructureNode;
import com.reubenagent.document.enums.DocumentStructureNodeTypeEnum;
import com.reubenagent.document.mapper.IDocumentMapper;
import com.reubenagent.document.mapper.IDocumentProfileMapper;
import com.reubenagent.document.model.DocumentParseResult;
import com.reubenagent.document.service.IDocumentProfileService;
import com.reubenagent.framework.uid.UidGenerator;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 文档画像服务实现 —— 自动推断文档类型、摘要、核心主题、示例问题并持久化。
 *
 * <p>在文档解析完成后调用，综合文档名、章节标题和正文内容，
 * 通过关键词匹配生成结构化画像。画像数据用于知识路由的语义匹配。</p>
 *
 * <h3>画像字段生成逻辑</h3>
 * <table>
 *   <tr><th>字段</th><th>生成方式</th></tr>
 *   <tr><td>documentType</td><td>文档名+文本关键词匹配（faq/troubleshooting/rule/spec/manual/intro）</td></tr>
 *   <tr><td>documentSummary</td><td>文档名 + 章节标题 + 正文前180字摘要</td></tr>
 *   <tr><td>coreTopics</td><td>章节标题去编号前缀 + 文档名去扩展名（上限6个）</td></tr>
 *   <tr><td>exampleQuestions</td><td>根据 documentType 模板生成</td></tr>
 *   <tr><td>graphFriendly</td><td>有步骤/列表项 或 >=2 个章节标题则为 true</td></tr>
 * </table>
 *
 * <h3>回填机制</h3>
 * 生成画像后，若 Document 表对应元数据字段为空则自动回填（knowledgeScopeCode/Name、
 * businessCategory、documentTags）。
 *
 * @author reuben
 * @since 2026-06-20
 */
@Slf4j
@AllArgsConstructor
@Service
public class DocumentProfileServiceImpl implements IDocumentProfileService {

    private static final int PROFILE_STATUS_SUCCESS = 2;
    private static final Pattern SECTION_CODE_PATTERN = Pattern.compile(
            "^(第[一二三四五六七八九十百0-9]+[章节条部分]\\s*)|(\\d+(?:\\.\\d+)+\\s*)");

    private final IDocumentMapper documentMapper;
    private final IDocumentProfileMapper profileMapper;
    private final UidGenerator uidGenerator;

    @Override
    public DocumentProfile generateProfile(Long documentId,
                                            DocumentParseResult parseResult,
                                            List<DocumentStructureNode> structureNodes) {
        Document document = documentMapper.selectById(documentId);
        if (document == null) {
            throw new IllegalArgumentException("文档不存在: " + documentId);
        }

        String parsedText = parseResult == null ? "" :
                Optional.ofNullable(parseResult.getParsedText()).orElse("");
        List<DocumentStructureNode> safeNodes = structureNodes == null ? List.of() : structureNodes;
        ProfileDraft draft = buildDraft(document, parsedText, safeNodes);

        // 查询已有画像 → 存在则更新（版本号递增），不存在则新建
        DocumentProfile existingProfile = profileMapper.selectOne(new LambdaQueryWrapper<DocumentProfile>()
                .eq(DocumentProfile::getDocumentId, documentId)
                .eq(DocumentProfile::getIsDeleted, 0)
                .last("LIMIT 1"));
        boolean creating = existingProfile == null;

        DocumentProfile profile = DocumentProfile.builder()
                .id(creating ? uidGenerator.getUid() : existingProfile.getId())
                .documentId(documentId)
                .profileVersion(creating ? 1
                        : Optional.ofNullable(existingProfile.getProfileVersion()).orElse(0) + 1)
                .documentSummary(draft.documentSummary)
                .documentType(draft.documentType)
                .coreTopics(JSON.toJSONString(draft.coreTopics))
                .exampleQuestions(JSON.toJSONString(draft.exampleQuestions))
                .graphFriendly(draft.graphFriendly ? 1 : 0)
                .supportsGraphOutline(draft.supportsGraphOutline ? 1 : 0)
                .supportsItemLookup(draft.supportsItemLookup ? 1 : 0)
                .supportsGraphAssist(draft.supportsGraphAssist ? 1 : 0)
                .profileSource("auto")
                .profileStatus(PROFILE_STATUS_SUCCESS)
                .errorMsg(null)
                .build();

        if (creating) {
            profileMapper.insert(profile);
        } else {
            profileMapper.updateById(profile);
        }

        backfillDocumentMetadata(document, draft);
        log.info("文档画像生成完成: documentId={}, documentType={}, graphFriendly={}, scopeCode='{}', businessCategory='{}'",
                documentId, draft.documentType, draft.graphFriendly, draft.knowledgeScopeCode, draft.businessCategory);
        return profile;
    }

    @Override
    public Optional<DocumentProfile> getByDocumentId(Long documentId) {
        if (documentId == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(profileMapper.selectOne(new LambdaQueryWrapper<DocumentProfile>()
                .eq(DocumentProfile::getDocumentId, documentId)
                .eq(DocumentProfile::getIsDeleted, 0)
                .last("LIMIT 1")));
    }

    // ========================================================================
    // 画像草稿构建
    // ========================================================================

    private ProfileDraft buildDraft(Document document,
                                    String parsedText,
                                    List<DocumentStructureNode> structureNodes) {
        List<String> sectionTitles = extractSectionTitles(structureNodes);
        boolean supportsItemLookup = structureNodes.stream().anyMatch(node -> node != null
                && (DocumentStructureNodeTypeEnum.STEP.getCode().equals(node.getNodeType())
                || DocumentStructureNodeTypeEnum.LIST_ITEM.getCode().equals(node.getNodeType())));
        boolean supportsGraphOutline = sectionTitles.size() >= 2;
        boolean graphFriendly = supportsItemLookup || supportsGraphOutline;

        String documentType = inferDocumentType(document, parsedText, sectionTitles, supportsItemLookup);
        List<String> coreTopics = buildCoreTopics(document, sectionTitles);
        List<String> exampleQuestions = buildExampleQuestions(documentType, coreTopics);
        String summary = buildSummary(document, sectionTitles, parsedText);
        String knowledgeScopeCode = inferKnowledgeScopeCode(document, sectionTitles, parsedText);
        String knowledgeScopeName = mapScopeName(knowledgeScopeCode);
        String businessCategory = inferBusinessCategory(documentType, parsedText);
        String documentTags = buildDocumentTags(document, knowledgeScopeCode, documentType, coreTopics);

        return new ProfileDraft(summary, documentType, coreTopics, exampleQuestions,
                graphFriendly, supportsGraphOutline, supportsItemLookup, true,
                knowledgeScopeCode, knowledgeScopeName, businessCategory, documentTags);
    }

    // ========================================================================
    // 各字段推断逻辑
    // ========================================================================

    /** 提取章节标题（nodeType = CHAPTER），去重上限 8 条 */
    private List<String> extractSectionTitles(List<DocumentStructureNode> structureNodes) {
        if (structureNodes == null || structureNodes.isEmpty()) {
            return List.of();
        }
        return structureNodes.stream()
                .filter(node -> node != null
                        && DocumentStructureNodeTypeEnum.CHAPTER.getCode().equals(node.getNodeType()))
                .map(DocumentStructureNode::getTitle)
                .filter(title -> title != null && !title.isBlank())
                .map(String::trim)
                .distinct()
                .limit(8)
                .toList();
    }

    /** 关键词匹配推断文档类型 */
    private String inferDocumentType(Document document, String parsedText,
                                     List<String> sectionTitles, boolean supportsItemLookup) {
        String combined = combinedLowerText(document, parsedText, sectionTitles);
        if (combined.contains("faq") || combined.contains("常见问题")) {
            return "faq";
        }
        if (combined.contains("故障") || combined.contains("排查") || combined.contains("检查顺序")) {
            return "troubleshooting";
        }
        if (combined.contains("规则") || combined.contains("制度")) {
            return "rule";
        }
        if (combined.contains("规格") || combined.contains("参数")) {
            return "spec";
        }
        if (supportsItemLookup || combined.contains("手册") || combined.contains("指南") || combined.contains("部署")) {
            return "manual";
        }
        return "intro";
    }

    /** 构建核心主题：章节标题去编号 + 文档名去扩展名，去重上限 6 个 */
    private List<String> buildCoreTopics(Document document, List<String> sectionTitles) {
        LinkedHashSet<String> topics = new LinkedHashSet<>();
        sectionTitles.stream().limit(6).forEach(title -> addTopic(topics, stripSectionCode(title)));
        addTopic(topics, stripFileExtension(document.getDocumentName()));
        return new ArrayList<>(topics).stream().filter(s -> !s.isBlank()).limit(6).toList();
    }

    private void addTopic(Set<String> topics, String topic) {
        if (topic == null || topic.isBlank()) {
            return;
        }
        topics.add(topic.trim());
    }

    /** 根据文档类型生成模板示例问题 */
    private List<String> buildExampleQuestions(String documentType, List<String> coreTopics) {
        List<String> examples = new ArrayList<>();
        for (String topic : coreTopics) {
            String question = switch (documentType) {
                case "troubleshooting" -> topic + "的可能原因有哪些？";
                case "manual" -> topic + "的步骤是什么？";
                case "rule" -> topic + "有哪些规则？";
                default -> topic + "是什么意思？";
            };
            examples.add(question);
        }
        return examples.stream().distinct().limit(6).toList();
    }

    /** 构建文档摘要 */
    private String buildSummary(Document document, List<String> sectionTitles, String parsedText) {
        StringBuilder builder = new StringBuilder();
        String docName = Optional.ofNullable(document.getDocumentName()).orElse("未命名文档");
        builder.append("文档《").append(docName).append("》");
        if (!sectionTitles.isEmpty()) {
            builder.append("主要涵盖：")
                    .append(String.join("、", sectionTitles.stream().limit(4).toList()))
                    .append("。");
        }
        String excerpt = parsedText.replaceAll("\\s+", " ").trim();
        if (excerpt.length() > 180) {
            excerpt = excerpt.substring(0, 180);
        }
        if (!excerpt.isEmpty()) {
            builder.append("摘要：").append(excerpt);
        }
        return builder.toString().trim();
    }

    /** 关键词匹配推断知识范围编码 */
    private String inferKnowledgeScopeCode(Document document, List<String> sectionTitles, String parsedText) {
        String combined = combinedLowerText(document, parsedText, sectionTitles);
        if (containsAny(combined, "上线观察", "值班规则", "观察时长", "运营")) {
            return "operation_rule";
        }
        if (containsAny(combined, "机器人", "知识召回", "意图识别", "策略设计")) {
            return "robot_strategy";
        }
        if (containsAny(combined, "安装", "部署", "默认密码", "访问地址")) {
            return "deployment";
        }
        if (containsAny(combined, "故障", "排查", "异常", "检查顺序")) {
            return "troubleshooting";
        }
        if (containsAny(combined, "产品简介", "核心特性", "技术规格", "产品概述")) {
            return "product";
        }
        return "general_document";
    }

    /** 编码 → 中文名称映射 */
    private String mapScopeName(String scopeCode) {
        return switch (scopeCode) {
            case "operation_rule" -> "运营规则";
            case "robot_strategy" -> "机器人策略";
            case "deployment" -> "安装部署";
            case "troubleshooting" -> "故障排查";
            case "product" -> "产品资料";
            default -> "通用文档";
        };
    }

    /** 推断业务分类 */
    private String inferBusinessCategory(String documentType, String parsedText) {
        return switch (documentType) {
            case "troubleshooting" -> "故障排查";
            case "rule" -> "规则";
            case "spec" -> "规格说明";
            case "manual" -> containsAny(parsedText.toLowerCase(Locale.ROOT), "步骤", "操作", "部署")
                    ? "操作手册" : "手册";
            default -> "介绍";
        };
    }

    /** 构建文档标签：已有标签 + scopeCode + documentType + 核心主题，去重上限 8 个 */
    private String buildDocumentTags(Document document, String knowledgeScopeCode,
                                     String documentType, List<String> coreTopics) {
        LinkedHashSet<String> tags = new LinkedHashSet<>();
        String existingTags = document.getDocumentTags();
        if (existingTags != null && !existingTags.isBlank()) {
            for (String tag : existingTags.split(",")) {
                String trimmed = tag.trim();
                if (!trimmed.isEmpty()) {
                    tags.add(trimmed);
                }
            }
        }
        addTag(tags, knowledgeScopeCode);
        addTag(tags, documentType);
        coreTopics.stream().limit(4).forEach(topic -> addTag(tags, topic));
        return String.join(",", tags.stream().limit(8).toList());
    }

    private void addTag(Set<String> tags, String tag) {
        if (tag == null || tag.isBlank()) {
            return;
        }
        tags.add(tag.trim());
    }

    // ========================================================================
    // 回填 Document 元数据
    // ========================================================================

    /**
     * 仅在 Document 对应字段为空时回填画像推断结果，不覆盖已有的人工编辑值。
     */
    private void backfillDocumentMetadata(Document document, ProfileDraft draft) {
        boolean changed = false;
        if (isBlank(document.getKnowledgeScopeCode()) && !isBlank(draft.knowledgeScopeCode)) {
            document.setKnowledgeScopeCode(draft.knowledgeScopeCode);
            changed = true;
        }
        if (isBlank(document.getKnowledgeScopeName()) && !isBlank(draft.knowledgeScopeName)) {
            document.setKnowledgeScopeName(draft.knowledgeScopeName);
            changed = true;
        }
        if (isBlank(document.getBusinessCategory()) && !isBlank(draft.businessCategory)) {
            document.setBusinessCategory(draft.businessCategory);
            changed = true;
        }
        if (isBlank(document.getDocumentTags()) && !isBlank(draft.documentTags)) {
            document.setDocumentTags(draft.documentTags);
            changed = true;
        }
        if (changed) {
            documentMapper.updateById(document);
        }
    }

    // ========================================================================
    // 工具方法
    // ========================================================================

    /** 合并文档名 + 原始文件名 + 章节标题 + 正文并转小写 */
    private String combinedLowerText(Document document, String parsedText, List<String> sectionTitles) {
        return (Optional.ofNullable(document.getDocumentName()).orElse("") + " "
                + Optional.ofNullable(document.getOriginalFileName()).orElse("") + " "
                + String.join(" ", sectionTitles) + " "
                + Optional.ofNullable(parsedText).orElse(""))
                .toLowerCase(Locale.ROOT);
    }

    /** 检查 text 是否包含任意关键词（忽略大小写） */
    private boolean containsAny(String text, String... keywords) {
        if (text == null || text.isEmpty()) {
            return false;
        }
        for (String keyword : keywords) {
            if (keyword != null && !keyword.isBlank()
                    && text.contains(keyword.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    /** 去掉章节编号前缀（如 "1.1 "、"第一章 "） */
    private String stripSectionCode(String title) {
        if (title == null || title.isBlank()) {
            return "";
        }
        return SECTION_CODE_PATTERN.matcher(title.trim()).replaceFirst("").trim();
    }

    /** 去掉文件扩展名 */
    private String stripFileExtension(String fileName) {
        if (fileName == null || fileName.isBlank()) {
            return "";
        }
        int index = fileName.lastIndexOf('.');
        return index > 0 ? fileName.substring(0, index) : fileName;
    }

    private boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    // ========================================================================
    // 内部中间产物
    // ========================================================================

    private record ProfileDraft(
            /** 文档摘要（文档名 + 章节 + 正文前180字） */
            String documentSummary,
            /** 文档类型 faq / troubleshooting / rule / spec / manual / intro */
            String documentType,
            /** 核心主题（章节标题去编号 + 文档名去扩展名，上限6个） */
            List<String> coreTopics,
            /** 示例问题（按 documentType 模板生成，上限6条） */
            List<String> exampleQuestions,
            /** 是否适合图结构问答 */
            boolean graphFriendly,
            /** 是否支持章节列表展示（≥2个章节） */
            boolean supportsGraphOutline,
            /** 是否支持条目查找（含 STEP / LIST_ITEM 节点） */
            boolean supportsItemLookup,
            /** 是否支持图辅助检索 */
            boolean supportsGraphAssist,
            /** 知识范围编码 operation_rule / robot_strategy / deployment / troubleshooting / product / general_document */
            String knowledgeScopeCode,
            /** 知识范围中文名 */
            String knowledgeScopeName,
            /** 业务分类 故障排查 / 规则 / 规格说明 / 操作手册 / 手册 / 介绍 */
            String businessCategory,
            /** 文档标签（逗号分隔，合并已有 + scopeCode + type + topics，上限8个） */
            String documentTags
    ) {
    }
}
