package com.reubenagent.common.exception;

import com.reubenagent.common.dto.ApiResponse;
import com.reubenagent.common.enums.BaseCode;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.List;

/**
 * 全局异常处理器 —— 按异常类型分流，日志级别区分。
 *
 * <h3>处理顺序（Spring 从上到下匹配第一个）</h3>
 * <ol>
 *   <li>{@link DocumentException}  → code + message，日志 warn</li>
 *   <li>{@link ValidationException} → 字段级错误列表，日志 warn</li>
 *   <li>{@link BusinessException}   → code + message（兜底业务异常），日志 warn</li>
 *   <li>{@link MethodArgumentNotValidException} → Spring @Valid 校验 → 转 ValidationError 列表</li>
 *   <li>{@link Exception}           → 未知异常，日志 error，返回系统错误</li>
 * </ol>
 *
 * <h3>设计要点</h3>
 * <ul>
 *   <li>兜底用 {@link Exception} 而非 {@link Throwable} —— OOM、StackOverflow 等 Error 不应被吞。</li>
 *   <li>业务异常用 warn 级别 —— 属于预期内的失败，不需要告警。</li>
 *   <li>未知异常用 error 级别 —— 需要排查的 bug，打印完整堆栈。</li>
 * </ul>
 *
 * @author reuben
 * @since 2026-06-14
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    // ==================== 文档异常 ====================

    @ExceptionHandler(DocumentException.class)
    public ApiResponse<String> handleDocument(HttpServletRequest request, DocumentException e) {
        log.warn("文档异常 → code={} docCode={} msg={} {} {}",
                e.getCode(),
                e.getDocumentCode() != null ? e.getDocumentCode().name() : "-",
                e.getMessage(),
                request.getMethod(),
                request.getRequestURL());
        return ApiResponse.error(e.getCode(), e.getMessage());
    }

    // ==================== 参数校验异常 ====================

    @ExceptionHandler(ValidationException.class)
    public ApiResponse<List<ValidationError>> handleValidation(HttpServletRequest request, ValidationException e) {
        log.warn("校验异常 → fields={} msg={} {} {}",
                e.getErrors().size(), e.getMessage(),
                request.getMethod(), request.getRequestURL());
        return ApiResponse.error(BaseCode.PARAMETER_ERROR.getCode(), e.getErrors());
    }

    // ==================== 通用业务异常（兜底） ====================

    @ExceptionHandler(BusinessException.class)
    public ApiResponse<String> handleBusiness(HttpServletRequest request, BusinessException e) {
        log.warn("业务异常 → code={} msg={} {} {}",
                e.getCode(), e.getMessage(),
                request.getMethod(), request.getRequestURL());
        return ApiResponse.error(e.getCode(), e.getMessage());
    }

    // ==================== Spring @Valid 校验异常 ====================

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ApiResponse<List<ValidationError>> handleSpringValid(
            HttpServletRequest request, MethodArgumentNotValidException e) {
        log.warn("Spring校验 → fields={} {} {}",
                e.getBindingResult().getFieldErrorCount(),
                request.getMethod(), request.getRequestURL());

        List<ValidationError> errors = e.getBindingResult().getFieldErrors().stream()
                .map(fe -> new ValidationError(fe.getField(), fe.getDefaultMessage()))
                .toList();
        return ApiResponse.error(BaseCode.PARAMETER_ERROR.getCode(), errors);
    }

    // ==================== 未知异常（只抓 Exception，不抓 Error） ====================

    /**
     * 兜底 —— 只捕获 {@link Exception}，不捕获 {@link Error}。
     * OOM、StackOverflow 等严重错误会自然向上抛出，由容器统一处理。
     */
    @ExceptionHandler(Exception.class)
    public ApiResponse<String> handleUnknown(HttpServletRequest request, Exception e) {
        log.error("未知异常 → type={} msg={} {} {}",
                e.getClass().getName(), e.getMessage(),
                request.getMethod(), request.getRequestURL(), e);
        return ApiResponse.error();
    }
}
