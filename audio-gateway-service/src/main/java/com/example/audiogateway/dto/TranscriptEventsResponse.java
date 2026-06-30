package com.example.audiogateway.dto;

import java.util.List;

/**
 * Cursor-paged transcript event response. {@code nextCursor} is a Redis stream
 * id and may advance even when no matching events were emitted, so clients can
 * skip unrelated stream entries without re-scanning them.
 */
public record TranscriptEventsResponse(
        String sessionId,
        String correlationId,
        List<TranscriptEventResponse> events,
        String nextCursor,
        boolean hasMore
) {
}
