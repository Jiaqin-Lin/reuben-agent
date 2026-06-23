package com.reubenagent.chat.enums;

import com.reubenagent.common.enums.BaseEnum;
import com.reubenagent.common.enums.EnumUtils;
import lombok.Getter;

/**
 * 对话模块错误码 —— 区间 30001 ~ 30099。
 *
 * <p>配合 {@link com.reubenagent.chat.exception.ChatException} 使用。</p>
 *
 * @author reuben
 * @since 2026-06-23
 */
public enum ChatErrorCode implements BaseEnum {

    CONVERSATION_NOT_FOUND(30001, "会话不存在"),
    TURN_NOT_FOUND(30002, "轮次不存在"),
    SESSION_RUNNING(30003, "会话当前正在执行中"),
    LEASE_ACQUIRE_FAILED(30004, "获取会话租约失败"),
    GENERATION_FAILED(30005, "回答生成失败"),
    REWRITE_FAILED(30006, "查询改写失败"),
    RETRIEVE_FAILED(30007, "检索失败"),
    MEMORY_COMPRESS_FAILED(30008, "记忆压缩失败"),
    RECOMMENDATION_FAILED(30009, "追问生成失败"),
    TOOL_CALL_FAILED(30010, "工具调用失败"),
    PARAM_INVALID(30011, "参数校验失败"),
    MODEL_CALL_FAILED(30012, "模型调用失败"),
    PROMPT_LOAD_FAILED(30013, "Prompt 模板加载失败"),
    EXECUTOR_NOT_FOUND(30014, "执行器不存在"),
    SESSION_STOPPED(30015, "会话已被停止"),
    AGENT_LIMIT_EXCEEDED(30016, "Agent 调用次数超限"),
    ;

    @Getter
    private final Integer code;
    private final String msg;

    ChatErrorCode(Integer code, String msg) {
        this.code = code;
        this.msg = msg;
    }

    @Override
    public String getMsg() {
        return msg;
    }

    public static ChatErrorCode getFromCode(Integer code) {
        return EnumUtils.getFromCode(ChatErrorCode.class, code);
    }
}
