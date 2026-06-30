package com.example.gpcore.authz;

import com.example.gpcore.domain.Action;

/**
 * Structured decision-cache key (Codex 019f1913 #7 — a record, never a
 * colon-joined string, so ids/principals containing separators cannot collide).
 *
 * <p>Every field that can change the answer participates, so a positive decision
 * is automatically invalidated when any of them changes (ADR-0035 §6):
 * <ul>
 *   <li>{@code principalId} + {@code subjectPolicyVersion} — clearance revocation invalidates;</li>
 *   <li>{@code relation} + {@code action} — action→relation binding;</li>
 *   <li>{@code nodeType} + {@code nodeId} — the object;</li>
 *   <li>{@code policyVersion} — ABAC policy set;</li>
 *   <li>{@code tupleRevision} — OpenFGA relationship snapshot (stops stale positives);</li>
 *   <li>{@code storeId} + {@code modelId} — the isolated OpenFGA store/model.</li>
 * </ul>
 */
public record AuthzCacheKey(String principalId, String subjectPolicyVersion, String relation,
                            Action action, String nodeType, String nodeId,
                            String policyVersion, String tupleRevision,
                            String storeId, String modelId) {
}
