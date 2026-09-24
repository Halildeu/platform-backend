package com.example.meeting.dto.v1.admin;

import java.util.List;

/** People-picker answer; a wrapper so it can grow without breaking callers. */
public record AssigneeCandidateSearchResponse(List<AssigneeCandidateResponse> items) {
}
