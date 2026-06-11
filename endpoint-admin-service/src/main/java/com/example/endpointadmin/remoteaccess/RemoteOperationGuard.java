package com.example.endpointadmin.remoteaccess;

import java.util.Set;
import java.util.stream.Collectors;

/**
 * Faz 22.6 D-1 — the live-session operation gate (ADR-0033 §7, ADR-0034 D8): decides whether an operator's
 * attempted {@link RemoteOperation} is permitted by the session's granted capability set. Pure + total +
 * fail-closed. This is the runtime enforcement of VIEW_ONLY / constrained-PTY at the OPERATION level: a
 * VIEW_ONLY session may only {@link RemoteOperation#SCREEN_VIEW}; every keystroke / clipboard / file /
 * pivot / credential / elevation attempt is refused.
 *
 * <p><b>Pilot strictness:</b> with {@code pilotOnly} (the #1388 narrowest-first pilot), the EFFECTIVE
 * capability set is filtered to {@link RemoteSessionCapability#PILOT_ALLOWED} first — so even if a bug
 * granted a non-pilot capability (e.g. FILE_TRANSFER), an operation that needs it is still refused
 * ({@link Decision#DENIED_NON_PILOT}, defense-in-depth). Fail-closed: a {@code null} operation or capability
 * set is {@link Decision#DENIED_MALFORMED}.
 */
public final class RemoteOperationGuard {

    /** The explicit, auditable outcome of one operation check. */
    public enum Decision {
        /** A granted (and, under pilot strictness, pilot-allowed) capability permits the operation. */
        ALLOWED(true),
        /** No granted capability permits the operation. */
        DENIED_NO_CAPABILITY(false),
        /** The operation is permitted only by a non-pilot capability, refused under pilot strictness. The
         *  caller (C/D runtime) SHOULD meter this — a non-pilot capability reaching the guard is a grant
         *  bug / mis-issuance worth alerting on (Codex 019eb7d6). */
        DENIED_NON_PILOT(false),
        /** A transport-supplied operation name that maps to no known {@link RemoteOperation} → fail-closed
         *  (the "deny-unmapped" contract: an action the transport cannot map to a known operation is refused,
         *  never ignored). */
        DENIED_UNKNOWN_OPERATION(false),
        /** A null operation / capability set → fail-closed. */
        DENIED_MALFORMED(false);

        private final boolean allowed;

        Decision(boolean allowed) {
            this.allowed = allowed;
        }

        public boolean allowed() {
            return allowed;
        }
    }

    private final boolean pilotOnly;

    /** @param pilotOnly when true, the effective capabilities are restricted to {@code PILOT_ALLOWED}. */
    public RemoteOperationGuard(boolean pilotOnly) {
        this.pilotOnly = pilotOnly;
    }

    public Decision decide(Set<RemoteSessionCapability> granted, RemoteOperation operation) {
        if (granted == null || operation == null) {
            return Decision.DENIED_MALFORMED;
        }
        Set<RemoteSessionCapability> effective = pilotOnly
                ? granted.stream().filter(RemoteSessionCapability::isPilotAllowed).collect(Collectors.toSet())
                : granted;
        boolean permitted = operation.permittedBy().stream().anyMatch(effective::contains);
        if (permitted) {
            return Decision.ALLOWED;
        }
        // not permitted by the effective set — was it only blocked by the pilot filter?
        boolean permittedByRawGrant = operation.permittedBy().stream().anyMatch(granted::contains);
        return permittedByRawGrant ? Decision.DENIED_NON_PILOT : Decision.DENIED_NO_CAPABILITY;
    }

    /**
     * Decide by operation NAME — the live transport carries a wire-level action it must map onto a known
     * {@link RemoteOperation}. An unknown/unmappable name → {@link Decision#DENIED_UNKNOWN_OPERATION}
     * (the deny-unmapped contract, Codex 019eb7d6): the transport must never silently pass through an action
     * it cannot map; anything it can't map is refused here, fail-closed.
     */
    public Decision decide(Set<RemoteSessionCapability> granted, String operationName) {
        if (granted == null || operationName == null || operationName.isBlank()) {
            return Decision.DENIED_MALFORMED;
        }
        RemoteOperation operation;
        try {
            operation = RemoteOperation.valueOf(operationName.trim());
        } catch (IllegalArgumentException e) {
            return Decision.DENIED_UNKNOWN_OPERATION; // an unmapped wire action → fail-closed
        }
        return decide(granted, operation);
    }
}
