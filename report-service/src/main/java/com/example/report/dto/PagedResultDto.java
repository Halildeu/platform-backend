package com.example.report.dto;

import java.util.List;

public record PagedResultDto<T>(List<T> items, long total, int page, int pageSize) {}
