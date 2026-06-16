package com.example.meeting.dto.v1.admin;

import com.example.meeting.model.MeetingStatus;

import java.time.Instant;
import java.util.UUID;

/**
 * Meeting read projection — Faz 24 (#410). {@code orgId} is the resolved
 * effective-org (canonical or tenant fallback).
 */
public record MeetingResponse(
        UUID id,
        UUID orgId,
        String title,
        String description,
        MeetingStatus status,
        Instant scheduledStart,
        Instant scheduledEnd,
        String organizerSubject,
        String createdBySubject,
        Instant createdAt,
        String lastUpdatedBySubject,
        Instant updatedAt,
        Long version) {
}
