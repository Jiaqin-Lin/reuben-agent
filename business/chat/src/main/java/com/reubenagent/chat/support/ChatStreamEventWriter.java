package com.reubenagent.chat.support;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.serializer.SerializerFeature;
import com.reubenagent.chat.model.ChatStreamEvent;
import com.reubenagent.chat.model.ChatStreamEventType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * SSE 事件序列化器 —— 把 {@link ChatStreamEvent} 序列化为 SSE 行的 JSON 字符串。
 *
 * <p>修正 super-agent 问题：序列化失败<b>降级为 status error 事件</b>而非抛
 * {@code IllegalStateException} 中断整个会话。前端按 {@code type} 字段渲染。</p>
 *
 * <p>协议字段：{@code type}(lowercase name) / {@code content} / {@code conversationId} /
 * {@code turnId} / {@code timestamp} / {@code count}(仅 reference/recommend)。</p>
 *
 * @author reuben
 * @since 2026-06-24
 */
@Slf4j
@Component
public class ChatStreamEventWriter {

    /** 文本块事件。 */
    public String text(String content, String conversationId, Long turnId) {
        return write(ChatStreamEvent.of(ChatStreamEventType.TEXT, content, conversationId, turnId));
    }

    /** 思考状态提示（如"正在检索…"）。 */
    public String thinking(String content, String conversationId, Long turnId) {
        return write(ChatStreamEvent.of(ChatStreamEventType.THINKING, content, conversationId, turnId));
    }

    /** 执行状态变更。 */
    public String status(String content, String conversationId, Long turnId) {
        return write(ChatStreamEvent.of(ChatStreamEventType.STATUS, content, conversationId, turnId));
    }

    /** 错误事件。 */
    public String error(String content, String conversationId, Long turnId) {
        return write(ChatStreamEvent.of(ChatStreamEventType.ERROR, content, conversationId, turnId));
    }

    /** 引文来源列表。 */
    public String references(List<?> references, String conversationId, Long turnId) {
        return write(ChatStreamEvent.ofCounted(ChatStreamEventType.REFERENCE, references, conversationId, turnId));
    }

    /** 推荐追问列表。 */
    public String recommendations(List<String> recommendations, String conversationId, Long turnId) {
        return write(ChatStreamEvent.ofCounted(ChatStreamEventType.RECOMMEND, recommendations, conversationId, turnId));
    }

    /** 流结束标记。 */
    public String done(String conversationId, Long turnId) {
        return write(ChatStreamEvent.done(conversationId, turnId));
    }

    private String write(ChatStreamEvent event) {
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("type", event.type().protocolName());
            payload.put("content", event.content());
            payload.put("timestamp", event.timestamp());
            if (event.conversationId() != null && !event.conversationId().isBlank()) {
                payload.put("conversationId", event.conversationId());
            }
            if (event.turnId() != null && event.turnId() > 0) {
                payload.put("turnId", event.turnId());
            }
            if (event.count() != null) {
                payload.put("count", event.count());
            }
            return JSON.toJSONString(payload, SerializerFeature.WriteMapNullValue);
        } catch (Exception e) {
            // 阶段：序列化失败降级为 status error 事件，不中断会话
            log.warn("SSE 事件序列化失败，降级为错误事件 → type={} err={}",
                    event.type(), e.getMessage());
            return "{\"type\":\"error\",\"content\":\"流式事件序列化失败\",\"timestamp\":"
                    + event.timestamp() + "}";
        }
    }
}
