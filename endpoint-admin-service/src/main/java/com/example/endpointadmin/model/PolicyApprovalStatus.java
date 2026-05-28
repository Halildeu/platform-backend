package com.example.endpointadmin.model;

/**
 * Wave-12 PR-5 — lifecycle status of a policy-change approval request.
 *
 * <ul>
 *   <li>{@code PENDING} — proposed, no approver has interacted yet;</li>
 *   <li>{@code IN_REVIEW} — at least one reviewer requested changes; the
 *       request stays open for revision;</li>
 *   <li>{@code APPROVED} — terminal positive decision;</li>
 *   <li>{@code REJECTED} — terminal negative decision;</li>
 *   <li>{@code EXPIRED} — deadline elapsed without a terminal decision.</li>
 * </ul>
 *
 * Only {@code PENDING} and {@code IN_REVIEW} accept further decisions.
 */
public enum PolicyApprovalStatus {
    PENDING,
    IN_REVIEW,
    APPROVED,
    REJECTED,
    EXPIRED
}
