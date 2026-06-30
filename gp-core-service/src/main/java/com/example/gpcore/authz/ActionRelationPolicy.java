package com.example.gpcore.authz;

import com.example.gpcore.domain.Action;

/**
 * Maps an {@link Action} to the OpenFGA relation that must positively hold for
 * that action (Codex 019f1913 #1). This is the explicit, tested boundary that
 * stops "{@code viewer} implies everything": ABAC is a deny-only layer and can
 * never GRANT export/download — those require their own positive relation.
 */
public interface ActionRelationPolicy {

    /** The OpenFGA relation required for {@code action} (e.g. {@code viewer}, {@code editor}). */
    String relationFor(Action action);
}
