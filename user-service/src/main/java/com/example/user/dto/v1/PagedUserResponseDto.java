package com.example.user.dto.v1;

import java.util.List;

public class PagedUserResponseDto {
    private List<UserSummaryDto> items;
    private long total;
    private int page;
    private int pageSize;

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
}
