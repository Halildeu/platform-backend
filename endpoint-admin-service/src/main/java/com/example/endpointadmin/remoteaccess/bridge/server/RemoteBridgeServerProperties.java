package com.example.endpointadmin.remoteaccess.bridge.server;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;
import java.util.Locale;

/**
 * Faz 22.6 T-2b (Codex 019eb9fb) — remote-bridge grpc server configuration. {@code enabled} defaults to FALSE
 * and the entire server configuration class is conditional on it (ADR-0034 disabled-by-default): with the
 * default, NO remote-bridge bean exists — no Netty server object, no bind, no scheduler.
 *
 * <p>{@code bindHost} defaults to loopback — exposing the broker beyond the host is an explicit pilot
 * decision (TLS-passthrough L4 + gitops), never a code default.
 *
 * <p><b>T-2c (Codex 019ebb6c):</b> an ENABLED server requires mutual-TLS credentials ({@link Tls} — file
 * PATHS, K8s secret mounts at the pilot; never inline PEM, never committed material). Plaintext exists only
 * as an explicit, loopback-only test mode ({@code allowInsecurePlaintext}) — a non-loopback plaintext bind
 * refuses to start, so an operator can never accidentally expose an unencrypted broker.
 *
 * @param enabled              master switch; false (default) = no server runtime at all
 * @param bindHost             listen address; loopback by default (pilot overrides explicitly)
 * @param port                 listen port
 * @param heartbeatIntervalMillis CONTROL-stream server-push heartbeat period; {@code <= 0} disables the push
 * @param maxDataFrameBytes    stream-layer cap on {@code DataFrame.payload} size (T-2a deferred this here)
 * @param shutdownGraceMillis  graceful shutdown window before forced termination
 * @param tls                  mutual-TLS credential file paths (all three required together)
 * @param allowInsecurePlaintext explicit loopback-only plaintext escape (tests/local dev); default false
 * @param viewOnly             Faz 22.6 #1580 VIEW_ONLY screen-observation slice config (recording mode +
 *                             fanout bounds + MIME allowlist + parametric retention); recording OFF by default
 */
@ConfigurationProperties(prefix = "remote-bridge")
public record RemoteBridgeServerProperties(boolean enabled,
                                           String bindHost,
                                           int port,
                                           long heartbeatIntervalMillis,
                                           int maxDataFrameBytes,
                                           long shutdownGraceMillis,
                                           Tls tls,
                                           boolean allowInsecurePlaintext,
                                           Permit permit,
                                           ViewOnly viewOnly) {

    /**
     * Mutual-TLS credential FILE PATHS ({@code -path} suffix — these are never inline PEM bodies). The
     * server's own identity is {@code certChainPemPath}+{@code privateKeyPemPath}; {@code clientCaPemPath}
     * is the device CA that client (agent) certificates must chain to ({@code clientAuth=REQUIRE}).
     * Transport mTLS is IDENTITY only — device TRUST (revocation/EKU/identity decision) stays with the
     * B1.4 {@code CertTrustEvaluator} at the application layer.
     */
    public record Tls(String certChainPemPath, String privateKeyPemPath, String clientCaPemPath) {

        /** All three paths present — the only state in which an enabled server may serve TLS. */
        public boolean isComplete() {
            return notBlank(certChainPemPath) && notBlank(privateKeyPemPath) && notBlank(clientCaPemPath);
        }

        /** Nothing configured at all (as opposed to a partial — and therefore invalid — configuration). */
        public boolean isEmpty() {
            return !notBlank(certChainPemPath) && !notBlank(privateKeyPemPath) && !notBlank(clientCaPemPath);
        }

        private static boolean notBlank(String s) {
            return s != null && !s.isBlank();
        }
    }

    /**
     * Faz 22.6 T-4a-ii (Codex 019ebc7e) — the broker's permit-signing key (file PATH, never inline PEM).
     * An ENABLED bridge MUST have this — there is NO insecure escape (unlike the transport-TLS smoke flag):
     * a broker that cannot sign permits cannot authorize any operation, so it must fail closed at startup.
     * {@code signingKeyPemPath} = PKCS#8 EC P-256 private key; {@code kid} = the key id agents pin.
     */
    public record Permit(String signingKeyPemPath, String kid) {

        public boolean isConfigured() {
            return notBlank(signingKeyPemPath) && notBlank(kid);
        }

        private static boolean notBlank(String s) {
            return s != null && !s.isBlank();
        }
    }

    /**
     * Faz 22.6 #1580 (ADR-0044 D3/D5; Codex 019f078a) — the VIEW_ONLY screen-observation slice config.
     *
     * <p><b>Recording is OFF by default</b> ({@link RecordingMode#DISABLED}): a disabled bridge fans screen
     * frames out to a live operator viewer and persists NO content — no WORM chain, no content hash — only the
     * ephemeral metadata audit + counters. {@link RecordingMode#ENABLED} is the parametric-retention opt-in
     * (record-BEFORE-fanout WORM hash chain + recording-down → fail-closed); it MUST declare retention +
     * an owner-decision reference, and a DISABLED bridge MUST NOT carry any of those enabled-only fields
     * (the untested-privacy-claim guard — ADR-0044 D5, B0 negative matrix). An invalid combination fails the
     * configuration-binding (an enabled bridge then refuses to start), never silently downgrades.
     *
     * @param recordingMode              DISABLED (default, no content persistence) or ENABLED (WORM + retention)
     * @param maxViewersPerSession       fanout bound per session (default 1 — the one-to-one pilot invariant)
     * @param streamAuthorizationTtlMillis optional extra cap on a stream's fanout authorization; {@code 0}
     *                                   (default) = bind to the VIEW_ONLY permit's own expiry only
     * @param allowedFrameContentTypes   image MIME allowlist for fanned-out frames (default png/jpeg/webp)
     * @param recordingRetentionDays     ENABLED-only: content WORM retention; MUST be 0 when DISABLED
     * @param sessionMetadataRetentionDays ENABLED-only: metadata retention; MUST be 0 when DISABLED
     * @param recordingRetentionUnit     retention unit; pinned to {@code days} (ADR-0044, B0)
     * @param ownerDecisionRef           ENABLED-only: the owner decision/issue reference that authorized
     *                                   recording; MUST be blank when DISABLED
     */
    public record ViewOnly(RecordingMode recordingMode,
                           int maxViewersPerSession,
                           long streamAuthorizationTtlMillis,
                           List<String> allowedFrameContentTypes,
                           long recordingRetentionDays,
                           long sessionMetadataRetentionDays,
                           String recordingRetentionUnit,
                           String ownerDecisionRef) {

        /** Content-recording mode. DISABLED (default) = live fanout only, no content persistence. */
        public enum RecordingMode { DISABLED, ENABLED }

        private static final List<String> DEFAULT_ALLOWED_FRAME_CONTENT_TYPES =
                List.of("image/png", "image/jpeg", "image/webp");

        public ViewOnly {
            recordingMode = recordingMode == null ? RecordingMode.DISABLED : recordingMode;
            if (maxViewersPerSession <= 0) {
                maxViewersPerSession = 1;
            }
            if (streamAuthorizationTtlMillis < 0) {
                streamAuthorizationTtlMillis = 0;
            }
            allowedFrameContentTypes = normalizeContentTypes(allowedFrameContentTypes);
            recordingRetentionUnit = recordingRetentionUnit == null || recordingRetentionUnit.isBlank()
                    ? "days" : recordingRetentionUnit.strip().toLowerCase(Locale.ROOT);
            ownerDecisionRef = ownerDecisionRef == null ? "" : ownerDecisionRef.strip();
            if (recordingRetentionDays < 0 || sessionMetadataRetentionDays < 0) {
                throw new IllegalStateException("remote-bridge.view-only retention days cannot be negative");
            }
            // ADR-0044 D5 / B0: a DISABLED (recording-off) bridge MUST NOT carry any enabled-only field —
            // an untested privacy claim is fail-closed, not silently ignored.
            if (recordingMode == RecordingMode.DISABLED) {
                if (recordingRetentionDays != 0 || sessionMetadataRetentionDays != 0 || !ownerDecisionRef.isEmpty()) {
                    throw new IllegalStateException("remote-bridge.view-only.recording-mode=disabled MUST NOT set "
                            + "recording-retention-days / session-metadata-retention-days / owner-decision-ref "
                            + "(enabled-only fields on a recording-off bridge)");
                }
            } else {
                // ENABLED requires the parametric retention + the owner decision reference — fail-closed,
                // a bridge cannot enable content recording without declaring how long + on whose authority.
                if (recordingRetentionDays <= 0 || sessionMetadataRetentionDays <= 0) {
                    throw new IllegalStateException("remote-bridge.view-only.recording-mode=enabled requires "
                            + "positive recording-retention-days and session-metadata-retention-days");
                }
                if (!"days".equals(recordingRetentionUnit)) {
                    throw new IllegalStateException("remote-bridge.view-only.recording-retention-unit must be 'days'");
                }
                if (ownerDecisionRef.isEmpty()) {
                    throw new IllegalStateException("remote-bridge.view-only.recording-mode=enabled requires a "
                            + "non-blank owner-decision-ref");
                }
            }
        }

        public boolean recordingEnabled() {
            return recordingMode == RecordingMode.ENABLED;
        }

        private static List<String> normalizeContentTypes(List<String> raw) {
            if (raw == null || raw.isEmpty()) {
                return DEFAULT_ALLOWED_FRAME_CONTENT_TYPES;
            }
            List<String> normalized = raw.stream()
                    .filter(t -> t != null && !t.isBlank())
                    .map(t -> t.strip().toLowerCase(Locale.ROOT))
                    .distinct()
                    .toList();
            return normalized.isEmpty() ? DEFAULT_ALLOWED_FRAME_CONTENT_TYPES : normalized;
        }
    }

    public RemoteBridgeServerProperties {
        bindHost = bindHost == null || bindHost.isBlank() ? "127.0.0.1" : bindHost;
        if (port <= 0) {
            port = 9444;
        }
        if (heartbeatIntervalMillis < 0) {
            heartbeatIntervalMillis = 0;
        }
        if (maxDataFrameBytes <= 0) {
            maxDataFrameBytes = 256 * 1024;
        }
        if (shutdownGraceMillis <= 0) {
            shutdownGraceMillis = 5_000L;
        }
        tls = tls == null ? new Tls(null, null, null) : tls;
        permit = permit == null ? new Permit(null, null) : permit;
        viewOnly = viewOnly == null
                ? new ViewOnly(ViewOnly.RecordingMode.DISABLED, 0, 0, null, 0, 0, null, null)
                : viewOnly;
    }
}
