package com.reubenagent.framework.uid.exception;

/**
 * UID 生成异常。
 *
 * <p>当 ID 生成器无法分配 UID 时抛出此异常，常见原因包括：</p>
 * <ul>
 *   <li>时钟回拨（Clock moved backwards）</li>
 *   <li>时间戳位耗尽（Timestamp bits exhausted）</li>
 *   <li>RingBuffer 缓冲区异常</li>
 * </ul>
 *
 * @author reuben
 * @since 2026-06-14
 */
public class UidGenerateException extends RuntimeException {

    public UidGenerateException(String message) {
        super(message);
    }

    public UidGenerateException(String message, Throwable cause) {
        super(message, cause);
    }

    public UidGenerateException(String format, Object... args) {
        super(String.format(format, args));
    }

    public UidGenerateException(Throwable cause) {
        super(cause);
    }
}
