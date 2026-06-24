package com.reubenagent.common.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 通用分页响应包装。
 *
 * @param <T> 列表元素类型
 * @author reuben
 * @since 2026-06-24
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PageVo<T> {

    /** 总记录数 */
    private Long total;

    /** 当前页码（1 起） */
    private Integer pageNo;

    /** 每页条数 */
    private Integer pageSize;

    /** 当前页数据 */
    private List<T> records;

    public static <T> PageVo<T> of(long total, int pageNo, int pageSize, List<T> records) {
        return PageVo.<T>builder()
                .total(total)
                .pageNo(pageNo)
                .pageSize(pageSize)
                .records(records)
                .build();
    }
}
