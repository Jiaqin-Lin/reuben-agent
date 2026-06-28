package com.reubenagent.document.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 知识范围删除请求。
 *
 * @author reuben
 * @since 2026-06-28
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class KnowledgeScopeDeleteDto {

    @NotBlank(message = "scopeCode 不能为空")
    private String scopeCode;
}
