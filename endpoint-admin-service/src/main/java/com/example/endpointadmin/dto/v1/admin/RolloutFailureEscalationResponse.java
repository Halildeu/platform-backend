package com.example.endpointadmin.dto.v1.admin;

import java.util.List;
import java.util.UUID;

/**
 * A GENERATED GitHub escalation issue projection for a queue item (Faz 22.5 #527
 * §9.4). The body embeds only the already-redacted evidence; the canonical state
 * remains the backend queue item. This is a preview — the live issue creation is
 * an operator-configured GitHub integration, not performed by the backend.
 */
public record RolloutFailureEscalationResponse(
        String issueTitle,
        String issueBody,
        List<String> labels,
        UUID failureId) {
}
