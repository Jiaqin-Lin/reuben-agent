package com.reubenagent.document.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 知识范围保存请求。
 *
 * @author reuben
 * @since 2026-06-28
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class KnowledgeScopeSaveDto {

    /** ID，更新时传入 */
    private Long id;

    /** 范围编码 */
    @NotBlank(message = "scopeCode 不能为空")
    private String scopeCode;

    /** 范围名称 */
    @NotBlank(message = "scopeName 不能为空")
    private String scopeName;

    /** 父级范围编码 */
    private String parentScopeCode;

    /** 范围描述 */
    private String description;

    /** 别名（逗号分隔） */
    private String aliases;

    /** 示例问题（JSON 数组） */
    private String examples;

    /** 排序值 */
    private Integer sortOrder;
}
