package com.example.endpointadmin.dto.v1.admin;

import java.util.List;

/** #527 slice-1 — a queue item plus its ordered append-only event ledger. */
public record RolloutFailureDetailResponse(
        RolloutFailureItemResponse item,
        List<RolloutFailureEventResponse> events) {
}
