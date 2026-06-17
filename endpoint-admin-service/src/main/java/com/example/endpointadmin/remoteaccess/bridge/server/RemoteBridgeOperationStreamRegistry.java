package com.example.endpointadmin.remoteaccess.bridge.server;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Correlates operation DATA stream ids back to remote session ids.
 *
 * <p>New agents set {@code Envelope.session_id} on DATA frames. Older pilot agents only set
 * {@code DataFrame.stream_id == operationId}. The broker already knows the operation's session when it pushes
 * the signed dispatch, so this registry preserves backward-compatible recording until all agents carry the
 * richer DATA routing headers.
 */
public final class RemoteBridgeOperationStreamRegistry {

    private final ConcurrentMap<String, String> sessionByOperationId = new ConcurrentHashMap<>();

    public void bind(String operationId, String sessionId) {
        if (operationId == null || operationId.isBlank() || sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("operationId and sessionId are required");
        }
        sessionByOperationId.putIfAbsent(operationId, sessionId);
    }

    public Optional<String> sessionFor(String operationId) {
        if (operationId == null || operationId.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(sessionByOperationId.get(operationId));
    }
}
