package com.example.endpointadmin.remoteaccess.bridge.contract;

import com.example.endpointadmin.remoteaccess.RemoteSessionCapability;

import java.util.Set;
import java.util.regex.Pattern;

/**
 * Faz 22.6 T-1a — validation rules for the remote-bridge wire contract (Codex 019eb9fb). Pure, total,
 * fail-closed. These guard the boundary between the (untrusted/semi-trusted) wire and the broker domain logic:
 * a capability the pilot does not enable, or a malformed identifier, is refused here before it can reach the
 * policy engine.
 *
 * <p><b>Default-deny on capability:</b> the pilot enables ONLY {@link RemoteSessionCapability#PILOT_ALLOWED}
 * ({@code VIEW_ONLY}, {@code CONSTRAINED_PTY}). Anything else — a non-pilot capability OR a capability value
 * added to the enum in the future — is rejected (a future enum addition defaults to denied, never silently
 * admitted).
 */
public final class WireContract {

    private WireContract() {
    }

    /** A single capability the pilot enables. {@code null} and every non-pilot value are false (default-deny). */
    public static boolean isPilotCapability(RemoteSessionCapability capability) {
        return capability != null && RemoteSessionCapability.PILOT_ALLOWED.contains(capability);
    }

    /**
     * A non-empty capability set in which EVERY element is pilot-enabled. An empty set is rejected (a session
     * must request at least one capability); a single non-pilot / future capability rejects the whole set.
     */
    public static boolean allPilotCapabilities(Set<RemoteSessionCapability> capabilities) {
        return capabilities != null && !capabilities.isEmpty()
                && capabilities.stream().allMatch(WireContract::isPilotCapability);
    }

    /**
     * A required wire identifier on an explicit character allowlist (Codex 019eb9fb): UUIDs, Keycloak
     * subjects/emails, device ids — {@code [A-Za-z0-9._:@+=-]}, 1–256 chars. This bars control characters /
     * newlines / NUL (an id flows into the audit recorder, logs, policy detail, and the future proto adapters —
     * an unbounded id is an audit/log-injection surface). Mirrored in {@code docs/remote-bridge-wire-contract.md}.
     */
    private static final Pattern WIRE_ID = Pattern.compile("[A-Za-z0-9._:@+=-]{1,256}");

    public static boolean isValidId(String id) {
        return id != null && WIRE_ID.matcher(id).matches();
    }

    /**
     * Validate a {@link RemoteBridgeMessages.SessionRequest}: valid session/device/operator ids and a
     * non-empty, all-pilot capability set. Total, fail-closed.
     */
    public static boolean isValid(RemoteBridgeMessages.SessionRequest request) {
        return request != null
                && isValidId(request.sessionId())
                && isValidId(request.deviceId())
                && isValidId(request.operatorSubject())
                && allPilotCapabilities(request.requestedCapabilities());
    }
}
