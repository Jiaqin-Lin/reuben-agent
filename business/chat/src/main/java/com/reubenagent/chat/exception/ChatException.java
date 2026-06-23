package com.reubenagent.chat.exception;

import com.reubenagent.chat.enums.ChatErrorCode;
import com.reubenagent.common.exception.BusinessException;
import lombok.Getter;

/**
 * 对话模块领域异常 —— 直接包装 {@link ChatErrorCode} 枚举。
 *
 * <p>message 自动取 {@code msg —— detail}，构造时绑定枚举类型，
 * 方便 {@link com.reubenagent.common.exception.GlobalExceptionHandler} 分类 warn 日志。</p>
 *
 * @author reuben
 * @since 2026-06-23
 */
@Getter
public class ChatException extends BusinessException {

    private final ChatErrorCode chatCode;

    public ChatException(ChatErrorCode chatCode) {
        super(chatCode.getCode(), chatCode.getMsg());
        this.chatCode = chatCode;
    }

    public ChatException(ChatErrorCode chatCode, String detail) {
        super(chatCode.getCode(), chatCode.getMsg() + " —— " + detail);
        this.chatCode = chatCode;
    }

    public ChatException(ChatErrorCode chatCode, String detail, Throwable cause) {
        super(chatCode.getCode(), chatCode.getMsg() + " —— " + detail, cause);
        this.chatCode = chatCode;
    }
}
