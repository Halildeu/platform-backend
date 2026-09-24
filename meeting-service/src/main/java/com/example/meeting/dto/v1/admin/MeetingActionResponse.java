package com.example.meeting.dto.v1.admin;

import com.example.meeting.model.MeetingActionStatus;

import java.time.Instant;
import java.util.UUID;

/**
 * Action read projection — Faz 24 (#410).
 *
 * <p>{@code assigneeDisplayName} (gitops#3834) is the assignee's directory name for people reading
 * the task; {@code null} when unassigned, unknown/erased in the directory, or when the directory
 * could not be asked. {@code assigneeSubject} stays the identity of record.
 */
public record MeetingActionResponse(
        UUID id,
        UUID meetingId,
        UUID orgId,
        String description,
        String assigneeSubject,
        String assigneeDisplayName,
        MeetingActionStatus status,
        Instant dueAt,
        String createdBySubject,
        Instant createdAt,
        String lastUpdatedBySubject,
        Instant updatedAt,
        Long version) {

    /** Same row with the assignee's display name filled in (or cleared with {@code null}). */
    public MeetingActionResponse withAssigneeDisplayName(String displayName) {
        return new MeetingActionResponse(id, meetingId, orgId, description, assigneeSubject, displayName,
                status, dueAt, createdBySubject, createdAt, lastUpdatedBySubject, updatedAt, version);
    }
}
