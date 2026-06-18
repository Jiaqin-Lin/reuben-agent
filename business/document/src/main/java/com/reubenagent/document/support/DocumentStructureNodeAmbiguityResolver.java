package com.reubenagent.document.support;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.reubenagent.document.config.DocumentProperties;
import com.reubenagent.document.enums.DocumentStructureNodeSignalEnum;
import com.reubenagent.document.model.DocumentStructureNodeSignal;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 文档结构信号歧义消解器（Stage 2）。
 *
 * <p>规则引擎（{@link DocumentStructureNodeSignalExtractor}）处理单级数字编号和中文序号时，
 * 无法 100% 确定是标题还是列表项，输出 {@code HEADING_CANDIDATE}（置信度 0.58~0.62）。
 * 本类将模糊信号交给 LLM 做语义+局部上下文的二次判定。</p>
 *
 * <h3>处理流程</h3>
 * <ol>
 *   <li>三道前置检查（总开关 / ChatModel 可用性 / 是否存在模糊信号）</li>
 *   <li>筛选置信度在 [{@code ambiguityConfidenceFloor}, {@code ambiguityConfidenceCeil}] 区间内的 HEADING_CANDIDATE</li>
 *   <li>截断至 {@code maxAmbiguousSignalsPerCall} 条</li>
 *   <li>构建 Prompt（含上下文窗口）并调用 LLM</li>
 *   <li>解析 JSON 响应，映射回信号并更新 kind / confidence / reasons</li>
 *   <li>LLM 调用失败时优雅降级：返回原始信号列表</li>
 * </ol>
 *
 * <h3>与 super-agent 的关键差异</h3>
 * <ul>
 *   <li>信号行号字段：{@code logicalLineNo}（super-agent: {@code lineNo}）</li>
 *   <li>标题编码字段：{@code headingCode}（super-agent: {@code nodeCode}）</li>
 *   <li>信号枚举：{@link DocumentStructureNodeSignalEnum}（super-agent: DocumentStructureSignalKind）</li>
 *   <li>JSON 解析：FastJSON（super-agent: Jackson ObjectMapper）</li>
 *   <li>模板渲染：自实现简单字符串替换（super-agent: StringTemplate + StTemplateRenderer）</li>
 * </ul>
 *
 * @author reuben
 * @since 2026-06-18
 */
@Slf4j
@Component
@AllArgsConstructor
public class DocumentStructureNodeAmbiguityResolver {

    private final DocumentProperties properties;
    private final ObjectProvider<ChatModel> chatModelProvider;
    private final PromptTemplateService promptTemplateService;

    /** LLM 判定后置信度最低值 */
    private static final double DISAMBIGUATED_CONFIDENCE_FLOOR = 0.88;

    /** LLM 消解原因标记 */
    private static final String LLM_DISAMBIGUATED_REASON = "llm-disambiguated";

    // ======================== 公共入口 ========================

    /**
     * LLM 歧义消解入口。
     *
     * <p>对 sourceSignals 中的 HEADING_CANDIDATE 信号进行 LLM 二次判定，
     * 返回更新后的信号列表。前置检查不通过或 LLM 调用失败时透传原始信号。</p>
     *
     * @param documentTitle 文档标题（用于 prompt 上下文）
     * @param allLines      全文各行 trimmed 文本（用于构建 LLM 上下文窗口）
     * @param sourceSignals signalExtractor 产出的信号列表
     * @return 经过 LLM 判定后的信号列表（透传时与 sourceSignals 为同一引用）
     */
    public List<DocumentStructureNodeSignal> resolve(
            String documentTitle,
            List<String> allLines,
            List<DocumentStructureNodeSignal> sourceSignals) {

        // ---- 前置检查 1: 总开关 ----
        DocumentProperties.StructureParsing config = properties.getStructureParsing();
        if (config == null || !Boolean.TRUE.equals(config.getLlmDisambiguationEnabled())) {
            log.debug("LLM 歧义消解已关闭 (llmDisambiguationEnabled=false)，跳过 Stage 2");
            return sourceSignals;
        }

        // ---- 前置检查 2: ChatModel 可用性 ----
        ChatModel chatModel = chatModelProvider.getIfAvailable();
        if (chatModel == null) {
            log.debug("未找到 ChatModel Bean，跳过 LLM 歧义消解");
            return sourceSignals;
        }

        // ---- 前置检查 3: 存在符合条件的模糊信号 ----
        List<DocumentStructureNodeSignal> ambiguousSignals = filterAmbiguousSignals(sourceSignals, config);
        if (ambiguousSignals.isEmpty()) {
            log.debug("无符合条件的 HEADING_CANDIDATE 信号，跳过 LLM 歧义消解");
            return sourceSignals;
        }

        log.info("LLM 歧义消解启动: 筛选出 {} 条模糊信号，开始调用 LLM", ambiguousSignals.size());

        try {
            // 1. 构建 Prompt
            String prompt = buildPrompt(documentTitle, allLines, ambiguousSignals, config.getContextWindowLines());

            // 2. 调用 LLM
            String response = callLlm(chatModel, prompt);
            log.debug("LLM 歧义消解响应: {}", response);

            // 3. 解析响应
            Map<Integer, DisambiguationResult> results = parseResponse(response);

            // 4. 应用判定结果
            applyResults(sourceSignals, results);
            log.info("LLM 歧义消解完成: {} 条模糊信号中 {} 条被重新判定",
                    ambiguousSignals.size(), results.size());

        } catch (Exception e) {
            log.warn("LLM 歧义消解失败，回退到规则引擎结果: {} ({})", e.getMessage(), e.getClass().getSimpleName());
            log.debug("LLM 歧义消解详细错误", e);
            return sourceSignals;
        }

        return sourceSignals;
    }

    // ======================== 筛选 ========================

    /**
     * 从信号列表中筛选符合条件的模糊信号。
     *
     * <p>条件：kind == HEADING_CANDIDATE 且 confidence ∈ [floor, ceil]。
     * 结果截断至 maxAmbiguousSignalsPerCall。</p>
     */
    List<DocumentStructureNodeSignal> filterAmbiguousSignals(
            List<DocumentStructureNodeSignal> signals,
            DocumentProperties.StructureParsing config) {

        double floor = config.getAmbiguityConfidenceFloor();
        double ceil = config.getAmbiguityConfidenceCeil();
        int maxCount = config.getMaxAmbiguousSignalsPerCall();

        return signals.stream()
                .filter(s -> s.getKind() == DocumentStructureNodeSignalEnum.HEADING_CANDIDATE)
                .filter(s -> s.getConfidence() >= floor && s.getConfidence() <= ceil)
                .limit(maxCount)
                .collect(Collectors.toList());
    }

    // ======================== Prompt 构建 ========================

    /**
     * 构建完整的 LLM prompt。
     */
    String buildPrompt(String documentTitle, List<String> allLines,
                       List<DocumentStructureNodeSignal> ambiguousSignals, int contextWindow) {

        // 渲染 candidate blocks
        String candidateBlocks = ambiguousSignals.stream()
                .map(signal -> buildCandidateBlock(signal, allLines, contextWindow))
                .collect(Collectors.joining("\n"));

        // 渲染主模板
        Map<String, String> mainVars = new HashMap<>();
        mainVars.put("documentTitle", documentTitle != null ? documentTitle : "");
        mainVars.put("candidateBlocks", candidateBlocks);

        return promptTemplateService.render(
                PromptTemplateService.DOCUMENT_STRUCTURE_AMBIGUITY, mainVars);
    }

    /**
     * 为单个模糊信号构建候选行上下文。
     *
     * <p>上下文窗口：[lineNo - 1 - window, lineNo - 1 + window]（0-based 索引）。
     * 目标行前缀 {@code >> }，上下文行前缀 3 个空格。</p>
     */
    String buildCandidateBlock(DocumentStructureNodeSignal signal,
                               List<String> allLines, int contextWindow) {
        int lineNo = signal.getLogicalLineNo();       // 1-based
        int targetIdx = lineNo - 1;                   // 0-based
        int from = Math.max(0, targetIdx - contextWindow);
        int to = Math.min(allLines.size() - 1, targetIdx + contextWindow);

        // 构建上下文文本
        StringBuilder ctxBuilder = new StringBuilder();
        for (int i = from; i <= to; i++) {
            String prefix = (i == targetIdx) ? ">> " : "   ";
            ctxBuilder.append(prefix).append(allLines.get(i));
            if (i < to) {
                ctxBuilder.append('\n');
            }
        }
        String contextLines = ctxBuilder.toString();

        // 渲染候选行子模板
        Map<String, String> vars = new HashMap<>();
        vars.put("lineNo", String.valueOf(lineNo));
        vars.put("contextLines", contextLines);
        vars.put("initialKind", signal.getKind().name());
        vars.put("initialTitle", signal.getTitle() != null ? signal.getTitle() : "");
        vars.put("initialCode", signal.getHeadingCode() != null ? signal.getHeadingCode() : "");

        return promptTemplateService.render(
                PromptTemplateService.DOCUMENT_STRUCTURE_AMBIGUITY_CANDIDATE, vars);
    }

    // ======================== LLM 调用 ========================

    /**
     * 调用 LLM 并返回文本响应。
     *
     * <p>直接使用 {@link ChatModel#call(Prompt)}，不经过 {@code ChatClient}，
     * 以避免 ChatClient 层的 {@code UnsupportedOperationException}（DeepSeek 等兼容 API 常见）。</p>
     */
    String callLlm(ChatModel chatModel, String promptText) {
        Prompt prompt = new Prompt(new UserMessage(promptText));
        ChatResponse response = chatModel.call(prompt);
        return response.getResult().getOutput().getText();
    }

    // ======================== 响应解析 ========================

    /**
     * 解析 LLM 响应中的 JSON 数组。
     *
     * <p>LLM 有时会在 JSON 前后附带解释文字，本方法提取第一个 {@code [} 到最后一个 {@code ]} 之间的内容。</p>
     *
     * @param rawResponse LLM 原始响应文本
     * @return lineNo → DisambiguationResult 映射
     * @throws IllegalStateException 如果响应中无有效 JSON 数组
     */
    Map<Integer, DisambiguationResult> parseResponse(String rawResponse) {
        String jsonArray = extractJsonArray(rawResponse);
        JSONArray array;
        try {
            array = JSON.parseArray(jsonArray);
        } catch (Exception e) {
            throw new IllegalStateException("LLM 响应 JSON 解析失败: " + jsonArray, e);
        }
        if (array == null || array.isEmpty()) {
            throw new IllegalStateException("LLM 返回了空的 JSON 数组");
        }

        Map<Integer, DisambiguationResult> resultMap = new HashMap<>();
        for (int i = 0; i < array.size(); i++) {
            JSONObject obj = array.getJSONObject(i);
            if (obj == null) continue;
            Integer lineNo = obj.getInteger("line_no");
            if (lineNo == null) {
                log.debug("LLM 返回结果缺少 line_no 字段: {}", obj);
                continue;
            }
            String resolvedKind = obj.getString("resolved_kind");
            Integer levelHint = obj.getInteger("level_hint");
            resultMap.put(lineNo, new DisambiguationResult(lineNo, resolvedKind, levelHint));
        }
        return resultMap;
    }

    /**
     * 从 LLM 原始响应中提取 JSON 数组字符串。
     */
    String extractJsonArray(String rawResponse) {
        int start = rawResponse.indexOf('[');
        int end = rawResponse.lastIndexOf(']');
        if (start < 0 || end <= start) {
            throw new IllegalStateException("LLM 响应中未找到有效 JSON 数组: " + rawResponse);
        }
        return rawResponse.substring(start, end + 1);
    }

    // ======================== 结果应用 ========================

    /**
     * 将 LLM 判定结果应用到信号列表（原地修改）。
     */
    void applyResults(List<DocumentStructureNodeSignal> signals,
                      Map<Integer, DisambiguationResult> llmResults) {
        for (DocumentStructureNodeSignal signal : signals) {
            DisambiguationResult result = llmResults.get(signal.getLogicalLineNo());
            if (result == null) {
                continue; // 不在 LLM 判定范围内，保持原样
            }
            applySingleResult(signal, result);
        }
    }

    /**
     * 将单条 LLM 判定结果写入信号。
     */
    private void applySingleResult(DocumentStructureNodeSignal signal, DisambiguationResult result) {
        DocumentStructureNodeSignalEnum oldKind = signal.getKind();

        // 映射 kind
        DocumentStructureNodeSignalEnum newKind = mapResolvedKind(result.resolvedKind());
        signal.setKind(newKind);

        // 仅 HEADING 时设置 levelHint
        if (newKind == DocumentStructureNodeSignalEnum.HEADING && result.levelHint() != null) {
            signal.setLevelHint(result.levelHint());
        }

        // 提升置信度
        signal.setConfidence(Math.max(signal.getConfidence(), DISAMBIGUATED_CONFIDENCE_FLOOR));

        // 追加消解原因（reasons 可能为 List.of() 不可变列表，需新建）
        List<String> newReasons = new java.util.ArrayList<>(signal.getReasons());
        newReasons.add(LLM_DISAMBIGUATED_REASON);
        signal.setReasons(newReasons);

        log.debug("LLM 消解: logicalLineNo={}, {} → {}, confidence={}",
                signal.getLogicalLineNo(), oldKind, newKind, signal.getConfidence());
    }

    /**
     * 将 LLM 返回的 kind 字符串映射为信号枚举。
     */
    DocumentStructureNodeSignalEnum mapResolvedKind(String resolvedKind) {
        if (resolvedKind == null) {
            return DocumentStructureNodeSignalEnum.BODY;
        }
        return switch (resolvedKind.toUpperCase()) {
            case "HEADING" -> DocumentStructureNodeSignalEnum.HEADING;
            case "LIST_ITEM" -> DocumentStructureNodeSignalEnum.LIST_ITEM;
            default -> DocumentStructureNodeSignalEnum.BODY;
        };
    }

    // ======================== 内部类型 ========================

    /**
     * LLM 返回的单行歧义判定结果。
     */
    record DisambiguationResult(int lineNo, String resolvedKind, Integer levelHint) {
    }
}
