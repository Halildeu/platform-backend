package com.example.gpcore.domain;

import java.util.Objects;

/**
 * The outcome of a single authorization decision.
 *
 * <p>The {@code reason} is an INTERNAL audit string (e.g. {@code "no_relation"},
 * {@code "abac:legal_hold"}). It must NEVER be surfaced in a caller-facing
 * gateway response — a denied caller learns nothing about why, nor that a hidden
 * object exists (Codex 019f1913 #13). It is for audit/diagnostics only.
 */
public record AuthorizationDecision(boolean allowed, String reason) {

    public AuthorizationDecision {
        Objects.requireNonNull(reason, "reason");
    }

    private static final AuthorizationDecision ALLOW = new AuthorizationDecision(true, "granted");

    public static AuthorizationDecision allow() {
        return ALLOW;
    }

    public static AuthorizationDecision deny(String reason) {
        return new AuthorizationDecision(false, reason);
    }

    public boolean denied() {
        return !allowed;
    }
}
