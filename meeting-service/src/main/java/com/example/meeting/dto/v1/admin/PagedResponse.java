package com.example.meeting.dto.v1.admin;

import java.util.List;

/**
 * Generic paged list envelope (content + page metadata). Mirrors the
 * shape endpoint-admin returns for its list endpoints.
 */
public record PagedResponse<T>(
        List<T> content,
        int page,
        int size,
        long totalElements,
        int totalPages) {
}
