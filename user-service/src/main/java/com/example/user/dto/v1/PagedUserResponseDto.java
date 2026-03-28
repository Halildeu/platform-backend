package com.example.user.dto.v1;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class PagedUserResponseDto {
    private List<UserSummaryDto> items;
    private long total;
    private int page;
    private int pageSize;

    /** Aggregate values for group-level responses (SSRM aggregation). */
    private Map<String, Object> aggData;

    /** Dynamic column names returned by pivot queries. */
    private List<String> secondaryColumns;

    public PagedUserResponseDto() {
    }

    public PagedUserResponseDto(List<UserSummaryDto> items, long total, int page, int pageSize) {
        this.items = items;
        this.total = total;
        this.page = page;
        this.pageSize = pageSize;
    }

    public List<UserSummaryDto> getItems() {
        return items;
    }

    public void setItems(List<UserSummaryDto> items) {
        this.items = items;
    }

    public long getTotal() {
        return total;
    }

    public void setTotal(long total) {
        this.total = total;
    }

    public int getPage() {
        return page;
    }

    public void setPage(int page) {
        this.page = page;
    }

    public int getPageSize() {
        return pageSize;
    }

    public void setPageSize(int pageSize) {
        this.pageSize = pageSize;
    }

    public Map<String, Object> getAggData() {
        return aggData;
    }

    public void setAggData(Map<String, Object> aggData) {
        this.aggData = aggData;
    }

    public List<String> getSecondaryColumns() {
        return secondaryColumns;
    }

    public void setSecondaryColumns(List<String> secondaryColumns) {
        this.secondaryColumns = secondaryColumns;
    }
}
