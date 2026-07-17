package com.example.transcript.repository;

import java.util.UUID;

/** Tenant-scoped, transcript-free identity needed to order segment mutation locks. */
public record TranscriptSegmentMutationScope(UUID meetingId, UUID sessionId) {
}
