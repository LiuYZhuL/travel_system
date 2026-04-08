package com.travel.travel_system.utils;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.Collections;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.ALWAYS)
public class PageData<T> implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 列表数据
     */
    private List<T> list;

    /**
     * 当前页码
     */
    private Integer pageNo;

    /**
     * 每页大小
     */
    private Integer pageSize;

    /**
     * 总条数
     */
    private Long total;

    /**
     * 是否还有更多
     */
    private Boolean hasMore;

    public static <T> PageData<T> of(List<T> list, Integer pageNo, Integer pageSize, Long total) {
        List<T> safeList = list == null ? Collections.emptyList() : list;
        int safePageNo = pageNo == null || pageNo < 1 ? 1 : pageNo;
        int safePageSize = pageSize == null || pageSize < 1 ? 10 : pageSize;
        long safeTotal = total == null ? 0L : total;
        boolean hasMore = (long) safePageNo * safePageSize < safeTotal;

        return PageData.<T>builder()
                .list(safeList)
                .pageNo(safePageNo)
                .pageSize(safePageSize)
                .total(safeTotal)
                .hasMore(hasMore)
                .build();
    }
}