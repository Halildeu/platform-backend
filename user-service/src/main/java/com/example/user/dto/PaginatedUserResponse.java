package com.example.user.dto;

import java.util.List;

public class PaginatedUserResponse {

    private List<UserResponse> items;
    private long total;
    private int page;
    private int pageSize;

    public PaginatedUserResponse() {
    }

    public PaginatedUserResponse(List<UserResponse> items, long total, int page, int pageSize) {
        this.items = items;
        this.total = total;
        this.page = page;
        this.pageSize = pageSize;
    }

    public List<UserResponse> getItems() {
        return items;
    }

    public void setItems(List<UserResponse> items) {
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
