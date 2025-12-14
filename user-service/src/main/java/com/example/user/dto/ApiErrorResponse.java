package com.example.user.dto;

import java.time.Instant;
import java.util.Map;

public record ApiErrorResponse(String errorCode, String message, Map<String, Object> meta) {

    public static ApiErrorResponse of(String errorCode, String message, String traceId) {
        Map<String, Object> meta = Map.of(
                "traceId", traceId == null ? "" : traceId,
                "timestamp", Instant.now().toString()
        );
        return new ApiErrorResponse(errorCode, message, meta);
    }
}
