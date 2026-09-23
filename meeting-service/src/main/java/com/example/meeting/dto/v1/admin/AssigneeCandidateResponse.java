package com.example.meeting.dto.v1.admin;

/**
 * One picker row. {@code userId} is what the action create/update requests take as
 * {@code assigneeUserId}; name and email are for the person choosing.
 */
public record AssigneeCandidateResponse(long userId, String name, String email) {
}
