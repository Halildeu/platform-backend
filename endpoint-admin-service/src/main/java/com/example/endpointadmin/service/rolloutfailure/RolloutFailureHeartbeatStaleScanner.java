package com.example.endpointadmin.service.rolloutfailure;

import com.example.endpointadmin.config.ConditionalOnPrimaryEndpointPlane;
import com.example.endpointadmin.model.DeviceStatus;
import com.example.endpointadmin.model.EndpointDevice;
import com.example.endpointadmin.model.EndpointHeartbeat;
import com.example.endpointadmin.model.RolloutClassificationConfidence;
import com.example.endpointadmin.model.RolloutFailureClass;
import com.example.endpointadmin.repository.EndpointHeartbeatRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * #527 slice-2b — autonomous heartbeat-staleness signal ingest.
 *
 * <p>The scanner reads the latest {@code endpoint_heartbeats} row per device,
 * not {@code endpoint_devices.last_seen_at}; the latter is also touched by
 * enrollment/cert lifecycle paths and is too broad for heartbeat evidence.
 * It creates a SERVICE_HMAC_MODE queue item only when the latest heartbeat row
 * contains enough truthful, schema-valid evidence. Missing historical
 * {@code agentMode} rows are skipped rather than backfilled with a guess.
 */
@Component
@ConditionalOnPrimaryEndpointPlane
@ConditionalOnProperty(
        prefix = "endpoint-admin.rollout-failure.heartbeat-stale",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true)
public class RolloutFailureHeartbeatStaleScanner {

    static final String CLASSIFIER_VERSION = "auto:heartbeat-stale:v1";
    static final String ROLLOUT_ID = "heartbeat-auto:stale";
    static final String WAVE_ID = "SERVICE_HMAC_MODE";

    private static final Pattern SAFE_TOKEN = Pattern.compile("^[A-Za-z][A-Za-z0-9_.:-]{1,63}$");
    private static final Duration DEFAULT_STALE_AFTER = Duration.ofHours(24);

    private final EndpointHeartbeatRepository heartbeatRepository;
    private final RolloutFailureAutoIngestService autoIngestService;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final Duration staleAfter;
    private final int batchSize;

    public RolloutFailureHeartbeatStaleScanner(
            EndpointHeartbeatRepository heartbeatRepository,
            RolloutFailureAutoIngestService autoIngestService,
            ObjectMapper objectMapper,
            Clock clock,
            @Value("${endpoint-admin.rollout-failure.heartbeat-stale.stale-after:PT24H}")
            Duration staleAfter,
            @Value("${endpoint-admin.rollout-failure.heartbeat-stale.batch-size:100}")
            int batchSize) {
        this.heartbeatRepository = heartbeatRepository;
        this.autoIngestService = autoIngestService;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.staleAfter = staleAfter == null || staleAfter.compareTo(Duration.ofMinutes(1)) < 0
                ? DEFAULT_STALE_AFTER : staleAfter;
        this.batchSize = Math.max(1, Math.min(batchSize, 500));
    }

    @Scheduled(
            fixedDelayString = "${endpoint-admin.rollout-failure.heartbeat-stale.scan-interval-ms:900000}",
            initialDelayString = "${endpoint-admin.rollout-failure.heartbeat-stale.initial-delay-ms:300000}")
    public int scanStaleHeartbeats() {
        Instant now = Instant.now(clock);
        Instant cutoff = now.minus(staleAfter);
        int ingested = 0;
        for (EndpointHeartbeat heartbeat : heartbeatRepository.findLatestStaleHeartbeats(
                cutoff, DeviceStatus.DECOMMISSIONED, PageRequest.of(0, batchSize))) {
            if (ingest(heartbeat, now)) {
                ingested++;
            }
        }
        return ingested;
    }

    boolean ingest(EndpointHeartbeat heartbeat, Instant now) {
        if (heartbeat == null || heartbeat.getId() == null
                || heartbeat.getDevice() == null || heartbeat.getReceivedAt() == null) {
            return false;
        }
        EndpointDevice device = heartbeat.getDevice();
        if (device.getStatus() == DeviceStatus.DECOMMISSIONED) {
            return false;
        }

        String serviceState = payloadToken(heartbeat.getPayload(), "state");
        String agentMode = firstPayloadToken(heartbeat.getPayload(), "agentMode", "agent_mode");
        if (serviceState == null || agentMode == null) {
            return false;
        }

        UUID tenantId = device.getEffectiveOrgId() != null ? device.getEffectiveOrgId() : heartbeat.getTenantId();
        if (tenantId == null || device.getId() == null) {
            return false;
        }

        ObjectNode evidence = objectMapper.createObjectNode();
        evidence.put("class", RolloutFailureClass.SERVICE_HMAC_MODE.name());
        evidence.put("device_id", device.getId().toString());
        evidence.put("service_state", serviceState);
        evidence.put("agent_mode", agentMode);
        evidence.putNull("hmac_error_code");
        evidence.put("last_heartbeat_at", heartbeat.getReceivedAt().toString());
        evidence.putNull("command_id");
        if (heartbeat.getAgentVersion() == null || heartbeat.getAgentVersion().isBlank()) {
            evidence.putNull("agent_version");
        } else {
            evidence.put("agent_version", heartbeat.getAgentVersion().trim());
        }

        RolloutFailureClassifier.Classified classified = new RolloutFailureClassifier.Classified(
                RolloutFailureClass.SERVICE_HMAC_MODE,
                RolloutClassificationConfidence.MEDIUM,
                evidence);
        return autoIngestService.ingestClassified(
                tenantId,
                device.getId(),
                ROLLOUT_ID,
                WAVE_ID,
                classified,
                CLASSIFIER_VERSION,
                "heartbeat_stale:" + heartbeat.getId(),
                now);
    }

    private static String firstPayloadToken(Map<String, Object> payload, String... keys) {
        for (String key : keys) {
            String value = payloadToken(payload, key);
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private static String payloadToken(Map<String, Object> payload, String key) {
        if (payload == null || key == null) {
            return null;
        }
        Object raw = payload.get(key);
        if (!(raw instanceof String value)) {
            return null;
        }
        String trimmed = value.trim();
        if (!SAFE_TOKEN.matcher(trimmed).matches()) {
            return null;
        }
        return trimmed.toLowerCase(Locale.ROOT);
    }
}
