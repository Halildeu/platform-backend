package com.example.permission.dto.v1;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;

import java.util.HashMap;
import java.util.Map;

public class AuditExportJobCreateRequestDto {

    @Pattern(regexp = "^(?i)(csv|json)$", message = "format must be csv or json")
    private String format = "json";

    @Min(value = 1, message = "limit must be >= 1")
    @Max(value = 10000, message = "limit must be <= 10000")
    private Integer limit;

    private String sort;

    private Map<String, String> filters = new HashMap<>();

    public String getFormat() {
        return format;
    }

    public void setFormat(String format) {
        this.format = format;
    }

    public Integer getLimit() {
        return limit;
    }

    public void setLimit(Integer limit) {
        this.limit = limit;
    }

    public String getSort() {
        return sort;
    }

    public void setSort(String sort) {
        this.sort = sort;
    }

    public Map<String, String> getFilters() {
        return filters;
    }

    public void setFilters(Map<String, String> filters) {
        this.filters = filters == null ? new HashMap<>() : new HashMap<>(filters);
    }
}
