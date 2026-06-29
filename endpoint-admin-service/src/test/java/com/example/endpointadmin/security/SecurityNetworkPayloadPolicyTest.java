package com.example.endpointadmin.security;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SecurityNetworkPayloadPolicyTest {

    private final SecurityNetworkPayloadPolicy policy = new SecurityNetworkPayloadPolicy();

    @Test
    void hasSecurityNetworkBlockDetectsCanonicalLocationsOnly() {
        assertThat(SecurityNetworkPayloadPolicy.hasSecurityNetworkBlock(null)).isFalse();
        assertThat(SecurityNetworkPayloadPolicy.hasSecurityNetworkBlock(Map.of())).isFalse();
        assertThat(SecurityNetworkPayloadPolicy.hasSecurityNetworkBlock(
                Map.of("inventory", Map.of("securityNetwork", Map.of())))).isTrue();
        assertThat(SecurityNetworkPayloadPolicy.hasSecurityNetworkBlock(
                Map.of("securityNetwork", Map.of()))).isTrue();
        assertThat(SecurityNetworkPayloadPolicy.hasSecurityNetworkBlock(
                Map.of("inventory", Map.of("securityNetwork", "raw")))).isFalse();
    }

    @Test
    void projectAcceptsStructuredRedactedBlockEvent() {
        SecurityNetworkPayloadPolicy.Projection p = policy.projectAndHash(validBlock());

        assertThat(p.schemaVersion()).isEqualTo(1);
        assertThat(p.supported()).isTrue();
        assertThat(p.events()).hasSize(1);
        assertThat(p.events().get(0).edrVendor()).isEqualTo("windows-firewall");
        assertThat(p.events().get(0).blockedProcessHashPrefix()).isEqualTo("0123456789abcdef");
        assertThat(p.events().get(0).blockedDestination()).isEqualTo("dest-sha256-0123456789abcdef");
        assertThat(p.events().get(0).firewallRuleId()).isEqualTo("wfp-filter-5157");
        assertThat(p.payloadHashSha256()).matches("^[0-9a-f]{64}$");
    }

    @Test
    void projectAcceptsProbeErrorsWithAllowedCode() {
        Map<String, Object> block = validBlock();
        block.put("probeErrors", List.of(probeError("ACCESS_DENIED", "bounded-denied")));

        SecurityNetworkPayloadPolicy.Projection p = policy.projectAndHash(block);

        assertThat(p.probeErrors()).hasSize(1);
        assertThat(p.probeErrors().get(0).code()).isEqualTo("ACCESS_DENIED");
        assertThat(p.probeErrors().get(0).summary()).isEqualTo("bounded-denied");
    }

    @Test
    void projectRejectsUnknownProbeErrorCode() {
        Map<String, Object> block = validBlock();
        block.put("probeErrors", List.of(probeError("RAW_VENDOR_ERROR", "bounded")));

        assertThatThrownBy(() -> policy.projectAndHash(block))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("probeErrors[0].code");
    }

    @Test
    void unsupportedProbeMayCarryEmptyEventsOnly() {
        Map<String, Object> block = validBlock();
        block.put("supported", false);
        block.put("events", List.of());
        block.put("probeErrors", List.of(probeError("UNSUPPORTED_PLATFORM", "unsupported-platform")));

        SecurityNetworkPayloadPolicy.Projection p = policy.projectAndHash(block);

        assertThat(p.supported()).isFalse();
        assertThat(p.events()).isEmpty();
        assertThat(p.probeErrors()).hasSize(1);
    }

    @Test
    void projectRejectsEventCapOverflow() {
        Map<String, Object> block = validBlock();
        List<Map<String, Object>> events = new ArrayList<>();
        for (int i = 0; i <= SecurityNetworkPayloadPolicy.EVENTS_MAX; i++) {
            events.add(validEvent());
        }
        block.put("events", events);

        assertThatThrownBy(() -> policy.projectAndHash(block))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("events exceeds cap");
    }

    @Test
    void projectRejectsProbeErrorCapOverflow() {
        Map<String, Object> block = validBlock();
        List<Map<String, Object>> errors = new ArrayList<>();
        for (int i = 0; i <= SecurityNetworkPayloadPolicy.PROBE_ERRORS_MAX; i++) {
            errors.add(probeError("PROBE_TIMEOUT", "bounded-timeout"));
        }
        block.put("probeErrors", errors);

        assertThatThrownBy(() -> policy.projectAndHash(block))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("probeErrors exceeds cap");
    }

    @Test
    void sanitizeRejectsRawIpDestinationBeforeResultPersistence() {
        Map<String, Object> block = validBlock();
        @SuppressWarnings("unchecked")
        Map<String, Object> event = (Map<String, Object>) ((List<?>) block.get("events")).get(0);
        event.put("blockedDestination", "10.44.3.15:443");
        Map<String, Object> details = Map.of("inventory", Map.of("securityNetwork", block));

        assertThatThrownBy(() -> policy.sanitize(details))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("blockedDestination");
    }

    @Test
    void sanitizeRejectsProcessPathLeak() {
        Map<String, Object> block = validBlock();
        @SuppressWarnings("unchecked")
        Map<String, Object> event = (Map<String, Object>) ((List<?>) block.get("events")).get(0);
        event.put("firewallRuleId", "C:\\Users\\alice\\agent.exe");
        Map<String, Object> details = Map.of("inventory", Map.of("securityNetwork", block));

        assertThatThrownBy(() -> policy.sanitize(details))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("firewallRuleId");
    }

    @Test
    void eventMustCarryAConcreteBlockSignal() {
        Map<String, Object> block = validBlock();
        @SuppressWarnings("unchecked")
        Map<String, Object> event = (Map<String, Object>) ((List<?>) block.get("events")).get(0);
        event.put("blockedProcessHashPrefix", null);
        event.put("blockedDestination", null);
        event.put("firewallRuleId", null);

        assertThatThrownBy(() -> policy.projectAndHash(block))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at least one");
    }

    static Map<String, Object> validBlock() {
        Map<String, Object> block = new LinkedHashMap<>();
        block.put("schemaVersion", 1);
        block.put("supported", true);
        block.put("probeComplete", true);
        block.put("events", List.of(validEvent()));
        block.put("probeErrors", List.of());
        block.put("probeDurationMs", 42);
        return block;
    }

    private static Map<String, Object> validEvent() {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("networkSegmentId", "pilot-segment-a");
        event.put("edrVendor", "windows-firewall");
        event.put("blockedProcessHashPrefix", "0123456789abcdef");
        event.put("blockedDestination", "dest-sha256-0123456789abcdef");
        event.put("firewallRuleId", "wfp-filter-5157");
        event.put("lastSuccessfulContactAt", "2026-06-29T11:10:00Z");
        event.put("observedAt", "2026-06-29T11:15:00Z");
        return event;
    }

    private static Map<String, Object> probeError(String code, String summary) {
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("code", code);
        error.put("summary", summary);
        return error;
    }
}
