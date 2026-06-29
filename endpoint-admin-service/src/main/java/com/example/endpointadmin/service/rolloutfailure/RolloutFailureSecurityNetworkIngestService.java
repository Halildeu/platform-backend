package com.example.endpointadmin.service.rolloutfailure;

import com.example.endpointadmin.model.EndpointCommandResult;
import com.example.endpointadmin.model.RolloutClassificationConfidence;
import com.example.endpointadmin.model.RolloutFailureClass;
import com.example.endpointadmin.repository.EndpointCommandResultRepository;
import com.example.endpointadmin.security.SecurityNetworkPayloadPolicy;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Turns a committed, pre-validated structured security/network inventory block
 * into #527 FDQ {@code EDR_NETWORK} items. This is intentionally evidence-first:
 * only explicit block events from the {@code securityNetwork} block are accepted;
 * generic timeouts/diagnostics are not reclassified as EDR.
 */
@Service
public class RolloutFailureSecurityNetworkIngestService {

    static final String CLASSIFIER_VERSION = "auto:security-network:v1";
    static final String ROLLOUT_ID = "security-network-auto:structured-block";
    static final String WAVE_ID = RolloutFailureClass.EDR_NETWORK.name();

    private final EndpointCommandResultRepository resultRepository;
    private final SecurityNetworkPayloadPolicy policy;
    private final RolloutFailureAutoIngestService autoIngestService;
    private final ObjectMapper objectMapper;

    public RolloutFailureSecurityNetworkIngestService(
            EndpointCommandResultRepository resultRepository,
            SecurityNetworkPayloadPolicy policy,
            RolloutFailureAutoIngestService autoIngestService,
            ObjectMapper objectMapper) {
        this.resultRepository = resultRepository;
        this.policy = policy;
        this.autoIngestService = autoIngestService;
        this.objectMapper = objectMapper;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int ingest(SecurityNetworkBlockSubmittedEvent event) {
        if (event == null || event.commandResultId() == null
                || event.tenantId() == null || event.deviceId() == null) {
            return 0;
        }
        EndpointCommandResult result = resultRepository.findById(event.commandResultId()).orElse(null);
        if (result == null || result.getResultPayload() == null) {
            return 0;
        }
        Map<String, Object> block = extractSecurityNetwork(result.getResultPayload());
        if (block == null) {
            return 0;
        }
        SecurityNetworkPayloadPolicy.Projection projection = policy.projectAndHash(block);
        int ingested = 0;
        Instant now = result.getReportedAt() == null ? Instant.now() : result.getReportedAt();
        for (SecurityNetworkPayloadPolicy.EventProjection e : projection.events()) {
            RolloutFailureClassifier.Classified classified = new RolloutFailureClassifier.Classified(
                    RolloutFailureClass.EDR_NETWORK,
                    RolloutClassificationConfidence.HIGH,
                    evidence(event.deviceId(), e));
            boolean ok = autoIngestService.ingestClassified(
                    event.tenantId(),
                    event.deviceId(),
                    ROLLOUT_ID,
                    WAVE_ID,
                    classified,
                    CLASSIFIER_VERSION,
                    "security_network:" + event.commandResultId() + ":" + e.rowOrdinal(),
                    now);
            if (ok) {
                ingested++;
            }
        }
        return ingested;
    }

    private ObjectNode evidence(UUID deviceId, SecurityNetworkPayloadPolicy.EventProjection e) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("class", RolloutFailureClass.EDR_NETWORK.name());
        node.put("device_id", deviceId.toString());
        putNullable(node, "network_segment_id", e.networkSegmentId());
        node.put("edr_vendor", e.edrVendor());
        putNullable(node, "blocked_process_hash_prefix", e.blockedProcessHashPrefix());
        putNullable(node, "blocked_destination", e.blockedDestination());
        putNullable(node, "firewall_rule_id", e.firewallRuleId());
        putNullable(node, "last_successful_contact_at", e.lastSuccessfulContactAt());
        return node;
    }

    private static void putNullable(ObjectNode node, String field, String value) {
        if (value == null || value.isBlank()) {
            node.putNull(field);
        } else {
            node.put(field, value);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> extractSecurityNetwork(Map<String, Object> payload) {
        Object details = payload.get("details");
        if (!(details instanceof Map<?, ?> detailsMap)) {
            return null;
        }
        Object inventory = detailsMap.get("inventory");
        if (inventory instanceof Map<?, ?> inv && inv.get("securityNetwork") instanceof Map<?, ?> sn) {
            return (Map<String, Object>) sn;
        }
        if (detailsMap.get("securityNetwork") instanceof Map<?, ?> top) {
            return (Map<String, Object>) top;
        }
        return null;
    }
}
