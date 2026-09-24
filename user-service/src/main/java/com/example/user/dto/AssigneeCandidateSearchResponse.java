package com.example.user.dto;

import java.util.List;

/** Wrapper so the answer can grow (e.g. a truncation flag) without breaking callers. */
public record AssigneeCandidateSearchResponse(List<AssigneeCandidateEntry> items) {
}
