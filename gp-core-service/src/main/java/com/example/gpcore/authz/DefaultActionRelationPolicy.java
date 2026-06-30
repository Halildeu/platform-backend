package com.example.gpcore.authz;

import com.example.gpcore.domain.Action;

/**
 * Wave-1 action→relation mapping over the EXISTING v2 OpenFGA model relations
 * (no {@code can_export}/{@code can_download} relation is added to the locked
 * model yet — Codex 019f1913 AGREE).
 *
 * <ul>
 *   <li>{@code VIEW}      → {@code viewer}</li>
 *   <li>{@code RAG_READ}  → {@code viewer} (no separate grant, but a NARROWER
 *       ABAC layer applies than VIEW — see {@code DenyOverridesPolicyEvaluator})</li>
 *   <li>{@code EXPORT}    → {@code editor} (a stronger relation than viewer)</li>
 *   <li>{@code DOWNLOAD}  → {@code editor}</li>
 * </ul>
 *
 * Dedicated export/download relations (and evidence-specific custodian /
 * records_manager) are deferred to the evidence-ledger wave.
 */
public class DefaultActionRelationPolicy implements ActionRelationPolicy {

    public static final String VIEWER = "viewer";
    public static final String EDITOR = "editor";

    @Override
    public String relationFor(Action action) {
        return switch (action) {
            case VIEW, RAG_READ -> VIEWER;
            case EXPORT, DOWNLOAD -> EDITOR;
        };
    }
}
