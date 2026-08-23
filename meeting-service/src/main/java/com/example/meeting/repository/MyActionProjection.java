package com.example.meeting.repository;

import com.example.meeting.model.MeetingAction;

/**
 * Cross-meeting "my tasks" row — Faz 24 Görevler dilim-1 (gitops#3487).
 * Carries the owning meeting's title alongside the action so the caller can
 * render an assignee-centric list without an N+1 meeting lookup.
 */
public record MyActionProjection(MeetingAction action, String meetingTitle) {
}
