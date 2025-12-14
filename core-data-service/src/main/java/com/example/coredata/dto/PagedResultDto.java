package com.example.coredata.dto;

import java.util.List;

public record PagedResultDto<T>(
        List<T> items,
        long total,
        Integer page,
        Integer pageSize
) {
}
