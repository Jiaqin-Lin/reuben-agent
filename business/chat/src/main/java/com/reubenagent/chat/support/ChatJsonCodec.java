package com.reubenagent.chat.support;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 对话模块 JSON 编解码 —— 统一封装 FastJSON 读写，容错降级。
 *
 * <p>修正 super-agent 问题 5/14：解析失败 <b>warn + 返回空集合 / 上一版</b>，
 * 不抛 {@code IllegalStateException} 中断列表查询；LLM 输出 JSON 提取用首个平衡花括号，
 * 不用贪婪正则。</p>
 *
 * @author reuben
 * @since 2026-06-24
 */
@Slf4j
@Component
public class ChatJsonCodec {

    /** 序列化为 JSON 字符串，null 入参返回 null。 */
    public String toJson(Object obj) {
        if (obj == null) {
            return null;
        }
        return JSON.toJSONString(obj);
    }

    /** 解析 JSON 数组，失败 warn 返回空集合。 */
    public <T> List<T> parseList(String json, Class<T> clazz) {
        if (json == null || json.isBlank()) {
            return Collections.emptyList();
        }
        try {
            JSONArray array = JSON.parseArray(json);
            List<T> result = new ArrayList<>(array.size());
            for (int i = 0; i < array.size(); i++) {
                result.add(array.getObject(i, clazz));
            }
            return result;
        } catch (Exception e) {
            log.warn("JSON 数组解析失败，返回空集合 → clazz={} jsonHead={} err={}",
                    clazz.getSimpleName(), head(json), e.getMessage());
            return Collections.emptyList();
        }
    }

    /** 解析 JSON 对象，失败 warn 返回 null。 */
    public <T> T parseObject(String json, Class<T> clazz) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return JSON.parseObject(json, clazz);
        } catch (Exception e) {
            log.warn("JSON 对象解析失败，返回 null → clazz={} jsonHead={} err={}",
                    clazz.getSimpleName(), head(json), e.getMessage());
            return null;
        }
    }

    /**
     * 从可能夹带 Markdown/解释文本的 LLM 输出中提取首个平衡 JSON 对象。
     *
     * <p>替代 super-agent 的贪婪正则 {@code \\{.*}}：逐字符匹配括号深度，截取首个闭合块。</p>
     */
    public String extractFirstBalancedObject(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        int start = raw.indexOf('{');
        if (start < 0) {
            return null;
        }
        int depth = 0;
        boolean inString = false;
        boolean escape = false;
        for (int i = start; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (escape) {
                escape = false;
                continue;
            }
            if (c == '\\') {
                escape = true;
                continue;
            }
            if (c == '"') {
                inString = !inString;
                continue;
            }
            if (inString) {
                continue;
            }
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return raw.substring(start, i + 1);
                }
            }
        }
        log.warn("LLM 输出 JSON 未闭合，返回截断片段 → head={}", head(raw));
        return raw.substring(start);
    }

    /** 从可能夹带文本的 LLM 输出中提取首个 JSON 数组（方括号配对）。 */
    public String extractFirstBalancedArray(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        int start = raw.indexOf('[');
        if (start < 0) {
            return null;
        }
        int depth = 0;
        boolean inString = false;
        boolean escape = false;
        for (int i = start; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (escape) {
                escape = false;
                continue;
            }
            if (c == '\\') {
                escape = true;
                continue;
            }
            if (c == '"') {
                inString = !inString;
                continue;
            }
            if (inString) {
                continue;
            }
            if (c == '[') {
                depth++;
            } else if (c == ']') {
                depth--;
                if (depth == 0) {
                    return raw.substring(start, i + 1);
                }
            }
        }
        return raw.substring(start);
    }

    private String head(String json) {
        return json.length() > 120 ? json.substring(0, 120) : json;
    }

    /** 把 List 序列化为 JSON 字符串，null/空返回 null（避免落库空数组噪音）。 */
    public String toListJson(List<?> list) {
        if (list == null || list.isEmpty()) {
            return null;
        }
        return JSON.toJSONString(list);
    }
}
