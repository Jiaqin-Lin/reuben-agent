package com.reubenagent.common.exception;

import com.reubenagent.common.enums.BaseCode;

/**
 * 业务异常基类 —— 所有需要返回给前端的业务异常统一继承此类。
 *
 * <h3>设计原则</h3>
 * <ol>
 *   <li><b>code 必存</b> —— 每个构造器都确保 code 进入 {@code final} 字段。</li>
 *   <li><b>不 shadow message</b> —— 完全依赖 {@link RuntimeException#getMessage()}，子类不重复声明。</li>
 *   <li><b>枚举优先</b> —— 接受 {@link BaseCode} 等枚举，保留语义上下文。</li>
 *   <li><b>cause 链完整</b> —— 每个构造器都可传入根因。</li>
 * </ol>
 *
 * <h3>子类分层（按领域扩展）</h3>
 * <pre>
 * RuntimeException
 *  └── BusinessException          ← 本类
 *       ├── DocumentException     ← 文档管理 (20001~20099)
 *       ├── ValidationException   ← 参数校验 (携带字段级错误)
 *       └── AuthException         ← 认证授权 (后续扩展)
 * </pre>
 *
 * <h3>使用示例</h3>
 * <pre>{@code
 * // 通用
 * throw new BusinessException(BaseCode.NOT_FOUND, "用户不存在");
 *
 * // 自定义 code
 * throw new BusinessException(50001, "自定义错误");
 *
 * // 带根因
 * throw new BusinessException(BaseCode.SYSTEM_ERROR, "Redis 连接失败", e);
 * }</pre>
 *
 * @author reuben
 * @since 2026-06-14
 */
public class BusinessException extends RuntimeException {

    /** 业务错误码（final，构造后不可变） */
    private final Integer code;

    // ============ 仅 message ============

    public BusinessException(String message) {
        super(message);
        this.code = null;
    }

    public BusinessException(String message, Throwable cause) {
        super(message, cause);
        this.code = null;
    }

    // ============ BaseCode 枚举 ============

    public BusinessException(BaseCode baseCode) {
        super(baseCode.getMsg());
        this.code = baseCode.getCode();
    }

    /** message = "{baseCode.msg} —— {detail}" */
    public BusinessException(BaseCode baseCode, String detail) {
        super(baseCode.getMsg() + " —— " + detail);
        this.code = baseCode.getCode();
    }

    public BusinessException(BaseCode baseCode, Throwable cause) {
        super(baseCode.getMsg(), cause);
        this.code = baseCode.getCode();
    }

    /** message = "{baseCode.msg} —— {detail}" + cause */
    public BusinessException(BaseCode baseCode, String detail, Throwable cause) {
        super(baseCode.getMsg() + " —— " + detail, cause);
        this.code = baseCode.getCode();
    }

    // ============ 自定义 Integer code ============

    public BusinessException(Integer code, String message) {
        super(message);
        this.code = code;
    }

    public BusinessException(Integer code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    // ============ getter ============

    /** 业务错误码，可能为 null（无 code 构造器） */
    public Integer getCode() {
        return code;
    }
}
