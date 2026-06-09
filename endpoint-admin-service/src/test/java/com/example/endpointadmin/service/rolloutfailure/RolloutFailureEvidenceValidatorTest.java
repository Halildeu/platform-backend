package com.example.endpointadmin.service.rolloutfailure;

import com.example.endpointadmin.model.RolloutFailureClass;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit coverage for the security-critical per-class evidence allowlist +
 * redaction validator (Faz 22.5 #527 slice-1a, contract §3/§7; Codex 019eaaf0).
 * Pure JUnit — no Spring context.
 */
class RolloutFailureEvidenceValidatorTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final RolloutFailureEvidenceValidator validator =
            new RolloutFailureEvidenceValidator(mapper);

    private ObjectNode validDnsEvidence() {
        ObjectNode e = mapper.createObjectNode();
        e.put("class", "DNS_EDGE_MTLS");
        e.put("endpoint_host_hash", "deadbeef");
        e.put("edge_target", "edge.acik.com:8443");
        e.put("dns_error_code", "NXDOMAIN");
        e.putNull("tls_alert");
        e.putNull("mtls_peer_cert_fingerprint_prefix");
        e.put("observed_at", "2026-06-09T05:30:00Z");
        e.putNull("source");
        return e;
    }

    @Test
    void acceptsValidPerClassEvidenceAndReturnsCanonicalOrder() {
        JsonNode out = validator.validate(RolloutFailureClass.DNS_EDGE_MTLS, validDnsEvidence());
        assertThat(out.isObject()).isTrue();
        // canonical = registry key order, class first
        assertThat(out.fieldNames()).toIterable().first().isEqualTo("class");
    }

    @Test
    void rejectsUnknownEvidenceFieldAdditionalPropertiesFalse() {
        ObjectNode e = validDnsEvidence();
        e.put("raw_last_error", "boom");
        assertThatThrownBy(() -> validator.validate(RolloutFailureClass.DNS_EDGE_MTLS, e))
                .isInstanceOf(RolloutFailureEvidenceValidator.InvalidEvidence.class);
    }

    @Test
    void rejectsMissingRequiredField() {
        ObjectNode e = validDnsEvidence();
        e.remove("observed_at");
        assertThatThrownBy(() -> validator.validate(RolloutFailureClass.DNS_EDGE_MTLS, e))
                .isInstanceOf(RolloutFailureEvidenceValidator.InvalidEvidence.class);
    }

    @Test
    void rejectsClassDiscriminatorMismatch() {
        ObjectNode e = validDnsEvidence();
        e.put("class", "CERT_IDENTITY");
        assertThatThrownBy(() -> validator.validate(RolloutFailureClass.DNS_EDGE_MTLS, e))
                .isInstanceOf(RolloutFailureEvidenceValidator.InvalidEvidence.class);
    }

    @Test
    void rejectsNonHexHash() {
        ObjectNode e = validDnsEvidence();
        e.put("endpoint_host_hash", "NOT-HEX!");
        assertThatThrownBy(() -> validator.validate(RolloutFailureClass.DNS_EDGE_MTLS, e))
                .isInstanceOf(RolloutFailureEvidenceValidator.InvalidEvidence.class);
    }

    @Test
    void requiresRedactionMarkerOnRedactedField() {
        ObjectNode e = mapper.createObjectNode();
        e.put("class", "INSTALLER_MSI");
        e.put("device_id", "11111111-1111-1111-1111-111111111111");
        e.putNull("msi_product_code");
        e.put("msi_exit_code", 1603);
        e.putNull("agent_version");
        e.putNull("installer_phase");
        e.put("log_excerpt_redacted", "C:\\Users\\halil\\secret.log line 42"); // NOT a redaction marker
        e.putNull("gpo_assignment_id");
        assertThatThrownBy(() -> validator.validate(RolloutFailureClass.INSTALLER_MSI, e))
                .isInstanceOf(RolloutFailureEvidenceValidator.InvalidEvidence.class);
        // accepted when it IS a redaction marker
        e.put("log_excerpt_redacted", "[redacted 3 lines]");
        assertThatCode(() -> validator.validate(RolloutFailureClass.INSTALLER_MSI, e))
                .doesNotThrowAnyException();
    }

    @Test
    void scansForbiddenRawSecretMarkersInFreeStrings() {
        // JWT smuggled into a free string
        ObjectNode jwt = validDnsEvidence();
        jwt.put("edge_target", "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.payload.sig");
        assertThatThrownBy(() -> validator.validate(RolloutFailureClass.DNS_EDGE_MTLS, jwt))
                .isInstanceOf(RolloutFailureEvidenceValidator.InvalidEvidence.class);
        // full Windows SID smuggled into a free string
        ObjectNode sid = validDnsEvidence();
        sid.put("dns_error_code", "S-1-5-21-3623811015-3361044348-30300820-1013");
        assertThatThrownBy(() -> validator.validate(RolloutFailureClass.DNS_EDGE_MTLS, sid))
                .isInstanceOf(RolloutFailureEvidenceValidator.InvalidEvidence.class);
        // PEM block
        ObjectNode pem = validDnsEvidence();
        pem.put("source", "-----BEGIN CERTIFICATE-----");
        assertThatThrownBy(() -> validator.validate(RolloutFailureClass.DNS_EDGE_MTLS, pem))
                .isInstanceOf(RolloutFailureEvidenceValidator.InvalidEvidence.class);
        // raw email / UPN
        ObjectNode upn = validDnsEvidence();
        upn.put("source", "halil.kocoglu@acik.com");
        assertThatThrownBy(() -> validator.validate(RolloutFailureClass.DNS_EDGE_MTLS, upn))
                .isInstanceOf(RolloutFailureEvidenceValidator.InvalidEvidence.class);
        // raw IPv4
        ObjectNode ip = validDnsEvidence();
        ip.put("dns_error_code", "blocked at 10.9.10.53");
        assertThatThrownBy(() -> validator.validate(RolloutFailureClass.DNS_EDGE_MTLS, ip))
                .isInstanceOf(RolloutFailureEvidenceValidator.InvalidEvidence.class);
    }

    @Test
    void hostnameWithPortIsNotMistakenForAnIp() {
        // edge_target "edge.acik.com:8443" is a legitimate host:port — the IPv6
        // scanner needs ≥2 colon groups, the IPv4 scanner needs 4 dotted octets,
        // so a hostname:port must NOT trip the raw scanner.
        assertThatCode(() -> validator.validate(RolloutFailureClass.DNS_EDGE_MTLS, validDnsEvidence()))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsNonObjectEvidence() {
        assertThatThrownBy(() -> validator.validate(
                RolloutFailureClass.DNS_EDGE_MTLS, mapper.valueToTree("just-a-string")))
                .isInstanceOf(RolloutFailureEvidenceValidator.InvalidEvidence.class);
    }
}
