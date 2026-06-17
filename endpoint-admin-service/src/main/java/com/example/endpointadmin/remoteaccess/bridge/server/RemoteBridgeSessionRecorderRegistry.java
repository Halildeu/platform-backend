package com.example.endpointadmin.remoteaccess.bridge.server;

import com.example.endpointadmin.remoteaccess.DbRecordingSink;
import com.example.endpointadmin.remoteaccess.RecordingAnchorSigner;
import com.example.endpointadmin.remoteaccess.SessionRecorder;
import com.example.endpointadmin.remoteaccess.SessionRecordingChain;
import org.springframework.jdbc.core.JdbcTemplate;

import java.security.PrivateKey;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Shared per-session recorder registry for remote-bridge audit and DATA recording.
 *
 * <p>The WORM table key is {@code (chain_id, seq)}. All writers for a given remote session therefore MUST share
 * the same {@link SessionRecorder}; separate recorders would each start at {@code seq=0} and create a false
 * recording failure. This registry is the single in-process ownership point for that sequence.
 */
public final class RemoteBridgeSessionRecorderRegistry {

    private final ConcurrentMap<String, SessionRecorder> recorders = new ConcurrentHashMap<>();
    private final JdbcTemplate jdbcTemplate;
    private final String schema;
    private final PrivateKey anchorKey;
    private final String anchorAlgorithm;

    public RemoteBridgeSessionRecorderRegistry(JdbcTemplate jdbcTemplate, String schema,
                                               PrivateKey anchorKey, String anchorAlgorithm) {
        if (jdbcTemplate == null || anchorKey == null) {
            throw new IllegalArgumentException("jdbcTemplate and anchorKey are required");
        }
        if (schema == null || !schema.matches("[a-z_][a-z0-9_]*")) {
            throw new IllegalArgumentException("invalid schema identifier: " + schema);
        }
        if (anchorAlgorithm == null || anchorAlgorithm.isBlank()) {
            throw new IllegalArgumentException("anchorAlgorithm is required");
        }
        this.jdbcTemplate = jdbcTemplate;
        this.schema = schema;
        this.anchorKey = anchorKey;
        this.anchorAlgorithm = anchorAlgorithm;

        new DbRecordingSink("__startup_probe__", jdbcTemplate, schema);
        new RecordingAnchorSigner("__startup_probe__", anchorKey, anchorAlgorithm)
                .anchor(new SessionRecordingChain(), 0L);
    }

    public SessionRecorder recorderFor(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("sessionId is required for remote-bridge recording");
        }
        return recorders.computeIfAbsent(sessionId, id -> new SessionRecorder(
                new DbRecordingSink(id, jdbcTemplate, schema),
                new RecordingAnchorSigner(id, anchorKey, anchorAlgorithm)));
    }
}
