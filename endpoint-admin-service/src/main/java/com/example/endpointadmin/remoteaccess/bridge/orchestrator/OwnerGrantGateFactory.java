package com.example.endpointadmin.remoteaccess.bridge.orchestrator;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Faz 22.6 D10 Workstream-0 slice-2 (Codex 019ebe06) — selects the {@link OwnerTokenGate} with a blocking matrix
 * at construction (= bean creation = STARTUP fail-fast), mirroring the operator-authenticator factory pattern:
 * <ul>
 *   <li><b>DENY_ALL</b> — the fail-closed default ({@link OwnerTokenGate#DENY_ALL}): no grant mechanism wired,
 *       every operation denied. The current behaviour until the live pilot opts in.</li>
 *   <li><b>APPROVAL_BACKED_IN_MEMORY</b> — the {@link ApprovalBackedOwnerTokenGate} over an in-memory
 *       {@link ApprovalGrantStore}. A PLACEHOLDER (process-local) grant store → FORBIDDEN in a production-like
 *       profile; the durable/distributed (DB / OpenFGA-backed) grant store is the owner-gated live slice.</li>
 * </ul>
 */
public final class OwnerGrantGateFactory {

    private static final Logger log = LoggerFactory.getLogger(OwnerGrantGateFactory.class);

    public enum GateType { DENY_ALL, APPROVAL_BACKED_IN_MEMORY }

    private OwnerGrantGateFactory() {
    }

    private static IllegalStateException reject(String message) {
        log.error("remote-access owner-grant gate config REJECTED (fail-fast): {}", message);
        return new IllegalStateException(message);
    }

    /**
     * @param store                 the grant store (only consulted for APPROVAL_BACKED_IN_MEMORY)
     * @param productionLikeProfile when true, the placeholder in-memory approval-backed gate is REFUSED
     * @throws IllegalStateException on any forbidden combination (fail-fast startup)
     */
    public static OwnerTokenGate create(GateType type, ApprovalGrantStore store, boolean productionLikeProfile) {
        GateType t = type == null ? GateType.DENY_ALL : type; // fail-closed default
        switch (t) {
            case DENY_ALL -> {
                return OwnerTokenGate.DENY_ALL;
            }
            case APPROVAL_BACKED_IN_MEMORY -> {
                if (productionLikeProfile) {
                    throw reject("owner-grant gate APPROVAL_BACKED_IN_MEMORY uses a PLACEHOLDER in-memory grant "
                            + "store (process-local, lost on restart) and is forbidden in a production-like "
                            + "profile — configure a durable/OpenFGA-backed grant store");
                }
                if (store == null) {
                    throw reject("owner-grant gate APPROVAL_BACKED_IN_MEMORY requires an ApprovalGrantStore");
                }
                return new ApprovalBackedOwnerTokenGate(store);
            }
            default -> throw reject("unreachable owner-grant gate type " + t);
        }
    }
}
