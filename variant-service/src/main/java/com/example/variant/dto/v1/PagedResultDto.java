package com.example.variant.dto.v1;

import java.util.List;

public class PagedResultDto<T> {
    private List<T> items;
    private long total;
    private Integer page;
    private Integer pageSize;

    public PagedResultDto() {
    }

    public PagedResultDto(List<T> items, long total, Integer page, Integer pageSize) {
        this.items = items;
        this.total = total;
        this.page = page;
        this.pageSize = pageSize;
    }

    public List<T> getItems() {
        return items;
    }

    public void setItems(List<T> items) {
        this.items = items;
    }

    public long getTotal() {
        return total;
    }

    public void setTotal(long total) {
        this.total = total;
    }

    public Integer getPage() {
        return page;
    }

    public void setPage(Integer page) {
        this.page = page;
    }

    public Integer getPageSize() {
        return pageSize;
    }

    public void setPageSize(Integer pageSize) {
        this.pageSize = pageSize;
    }
}
