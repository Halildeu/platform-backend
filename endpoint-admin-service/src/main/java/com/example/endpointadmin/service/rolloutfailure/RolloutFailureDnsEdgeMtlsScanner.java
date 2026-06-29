package com.example.endpointadmin.service.rolloutfailure;

import com.example.endpointadmin.config.ConditionalOnPrimaryEndpointPlane;
import com.example.endpointadmin.model.DeviceStatus;
import com.example.endpointadmin.model.EndpointDiagnosticsSnapshot;
import com.example.endpointadmin.model.RolloutClassificationConfidence;
import com.example.endpointadmin.model.RolloutFailureClass;
import com.example.endpointadmin.repository.EndpointDiagnosticsSnapshotRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * #527 DNS_EDGE_MTLS autonomous ingest from AG-038 agent diagnostics.
 *
 * <p>The scanner only consumes the strict-allowlisted diagnostics snapshot
 * fields the backend already persists: backend DNS reachability, backend TLS
 * validity, a one-way config hash, and a bounded diagnostics error code. It
 * deliberately does not infer EDR/network root cause from generic timeouts and
 * does not claim a peer mTLS certificate fingerprint when diagnostics did not
 * observe one.
 */
@Component
@ConditionalOnPrimaryEndpointPlane
@ConditionalOnProperty(
        prefix = "endpoint-admin.rollout-failure.dns-edge-mtls",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true)
public class RolloutFailureDnsEdgeMtlsScanner {

    static final String CLASSIFIER_VERSION = "auto:dns-edge-mtls-diagnostics:v1";
    static final String ROLLOUT_ID = "diagnostics-auto:dns-edge-mtls";
    static final String WAVE_ID = RolloutFailureClass.DNS_EDGE_MTLS.name();
    static final String EDGE_TARGET = "endpoint-admin-backend";

    private static final Pattern HASH_HEX = Pattern.compile("^[0-9a-f]{8,64}$");
    private static final Pattern SAFE_CODE = Pattern.compile("^[A-Z][A-Z0-9_:-]{2,127}$");

    private final EndpointDiagnosticsSnapshotRepository diagnosticsRepository;
    private final RolloutFailureAutoIngestService autoIngestService;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final int batchSize;

    public RolloutFailureDnsEdgeMtlsScanner(
            EndpointDiagnosticsSnapshotRepository diagnosticsRepository,
            RolloutFailureAutoIngestService autoIngestService,
            ObjectMapper objectMapper,
            Clock clock,
            @Value("${endpoint-admin.rollout-failure.dns-edge-mtls.batch-size:100}")
            int batchSize) {
        this.diagnosticsRepository = diagnosticsRepository;
        this.autoIngestService = autoIngestService;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.batchSize = Math.max(1, Math.min(batchSize, 500));
    }

    @Scheduled(
            fixedDelayString = "${endpoint-admin.rollout-failure.dns-edge-mtls.scan-interval-ms:900000}",
            initialDelayString = "${endpoint-admin.rollout-failure.dns-edge-mtls.initial-delay-ms:420000}")
    public int scanDnsEdgeMtlsFailures() {
        Instant now = Instant.now(clock);
        int ingested = 0;
        for (EndpointDiagnosticsSnapshot snapshot : diagnosticsRepository
                .findLatestDnsTlsFailuresExcludingDeviceStatus(
                        DeviceStatus.DECOMMISSIONED, PageRequest.of(0, batchSize))) {
            if (ingest(snapshot, now)) {
                ingested++;
            }
        }
        return ingested;
    }

    boolean ingest(EndpointDiagnosticsSnapshot snapshot, Instant now) {
        if (snapshot == null || snapshot.getId() == null || snapshot.getTenantId() == null
                || snapshot.getDeviceId() == null) {
            return false;
        }
        boolean dnsFailed = Boolean.FALSE.equals(snapshot.getBackendDnsReachable());
        boolean tlsFailed = Boolean.FALSE.equals(snapshot.getBackendTlsValid());
        if (!dnsFailed && !tlsFailed) {
            return false;
        }
        String endpointHostHash = endpointHostHash(snapshot.getConfigHash());
        if (endpointHostHash == null) {
            return false;
        }
        Instant observedAt = observedAt(snapshot);
        if (observedAt == null) {
            return false;
        }
        String errorCode = safeCodeOrNull(snapshot.getLastErrorCode());

        ObjectNode evidence = objectMapper.createObjectNode();
        evidence.put("class", RolloutFailureClass.DNS_EDGE_MTLS.name());
        evidence.put("endpoint_host_hash", endpointHostHash);
        evidence.put("edge_target", EDGE_TARGET);
        putNullable(evidence, "dns_error_code", dnsErrorCode(dnsFailed, tlsFailed, errorCode));
        putNullable(evidence, "tls_alert", tlsAlert(dnsFailed, tlsFailed, errorCode));
        evidence.putNull("mtls_peer_cert_fingerprint_prefix");
        evidence.put("observed_at", observedAt.toString());
        evidence.put("source", "agent-diagnostics:" + snapshot.getId());

        RolloutFailureClassifier.Classified classified = new RolloutFailureClassifier.Classified(
                RolloutFailureClass.DNS_EDGE_MTLS,
                RolloutClassificationConfidence.MEDIUM,
                evidence);
        return autoIngestService.ingestClassified(
                snapshot.getTenantId(),
                snapshot.getDeviceId(),
                ROLLOUT_ID,
                WAVE_ID,
                classified,
                CLASSIFIER_VERSION,
                "dns_edge_mtls:diagnostics:" + snapshot.getId(),
                now);
    }

    private static Instant observedAt(EndpointDiagnosticsSnapshot snapshot) {
        if (snapshot.getLastErrorOccurredAt() != null) {
            return snapshot.getLastErrorOccurredAt();
        }
        if (snapshot.getCollectedAt() != null) {
            return snapshot.getCollectedAt();
        }
        return null;
    }

    private static String endpointHostHash(String configHash) {
        if (configHash == null) {
            return null;
        }
        String normalized = configHash.trim().toLowerCase(Locale.ROOT);
        if (HASH_HEX.matcher(normalized).matches()) {
            return normalized;
        }
        return null;
    }

    private static String safeCodeOrNull(String code) {
        if (code == null) {
            return null;
        }
        String trimmed = code.trim();
        return SAFE_CODE.matcher(trimmed).matches() ? trimmed : null;
    }

    private static String dnsErrorCode(boolean dnsFailed, boolean tlsFailed, String errorCode) {
        if (!dnsFailed || errorCode == null) {
            return null;
        }
        if (tlsFailed && !errorCode.startsWith("DNS_")) {
            return null;
        }
        return errorCode;
    }

    private static String tlsAlert(boolean dnsFailed, boolean tlsFailed, String errorCode) {
        if (!tlsFailed || errorCode == null) {
            return null;
        }
        if (dnsFailed) {
            return null;
        }
        return errorCode;
    }

    private static void putNullable(ObjectNode node, String field, String value) {
        if (value == null || value.isBlank()) {
            node.putNull(field);
        } else {
            node.put(field, value);
        }
    }
}
