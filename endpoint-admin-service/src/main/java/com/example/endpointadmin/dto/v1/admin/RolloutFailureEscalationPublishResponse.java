package com.example.endpointadmin.dto.v1.admin;

import java.util.List;
import java.util.UUID;

/**
 * Result of publishing a failed-device queue escalation projection to GitHub.
 * The backend queue item remains canonical; the GitHub issue is a human workflow
 * projection whose URL is stored on the aggregate.
 */
public record RolloutFailureEscalationPublishResponse(
        UUID failureId,
        String issueUrl,
        Long issueNumber,
        String issueTitle,
        List<String> labels,
        String currentState,
        boolean alreadyPublished) {
}
