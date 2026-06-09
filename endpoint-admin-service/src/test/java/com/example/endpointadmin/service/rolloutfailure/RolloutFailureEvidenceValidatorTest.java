package com.example.endpointadmin.service.rolloutfailure;

import com.example.endpointadmin.model.RolloutFailureClass;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Unit coverage for the typed per-class evidence redaction validator (#527 slice-1b). */
class RolloutFailureEvidenceValidatorTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final RolloutFailureEvidenceValidator validator =
            new RolloutFailureEvidenceValidator(mapper);

    private ObjectNode validDnsEvidence() {
        ObjectNode e = mapper.createObjectNode();
        e.put("class", "DNS_EDGE_MTLS");
        e.put("endpoint_host_hash", "deadbeef");
        e.put("edge_target", "edge.acik.com:8443");
        e.putNull("dns_error_code");
        e.putNull("tls_alert");
        e.putNull("mtls_peer_cert_fingerprint_prefix");
        e.put("observed_at", "2026-06-09T05:30:00Z");
        e.putNull("source");
        return e;
    }

    @Test
    void acceptsValidEvidenceAndReturnsCanonicalObject() {
        JsonNode canonical = validator.validate(RolloutFailureClass.DNS_EDGE_MTLS, validDnsEvidence());
        assertThat(canonical.get("class").asText()).isEqualTo("DNS_EDGE_MTLS");
        assertThat(canonical.get("endpoint_host_hash").asText()).isEqualTo("deadbeef");
    }

    @Test
    void rejectsUnknownField() {
        ObjectNode e = validDnsEvidence();
        e.put("rogue_field", "x");
        assertThatThrownBy(() -> validator.validate(RolloutFailureClass.DNS_EDGE_MTLS, e))
                .isInstanceOf(RolloutFailureEvidenceValidator.InvalidEvidence.class);
    }

    @Test
    void rejectsMissingRequiredField() {
        ObjectNode e = validDnsEvidence();
        e.remove("edge_target");
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
        e.put("endpoint_host_hash", "NOT-HEX!!");
        assertThatThrownBy(() -> validator.validate(RolloutFailureClass.DNS_EDGE_MTLS, e))
                .isInstanceOf(RolloutFailureEvidenceValidator.InvalidEvidence.class);
    }

    @Test
    void requiresRedactionMarkerForRedactedField() {
        ObjectNode e = mapper.createObjectNode();
        e.put("class", "INSTALLER_MSI");
        e.put("device_id", "11111111-1111-1111-1111-111111111111");
        e.putNull("msi_product_code");
        e.put("msi_exit_code", 1603);
        e.putNull("agent_version");
        e.putNull("installer_phase");
        e.put("log_excerpt_redacted", "this is a raw non-redacted log line");
        e.putNull("gpo_assignment_id");
        assertThatThrownBy(() -> validator.validate(RolloutFailureClass.INSTALLER_MSI, e))
                .isInstanceOf(RolloutFailureEvidenceValidator.InvalidEvidence.class);
    }

    @Test
    void scansForbiddenRawSecretAndPiiMarkers() {
        // JWT
        ObjectNode jwt = validDnsEvidence();
        jwt.put("dns_error_code", "token eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9");
        assertThatThrownBy(() -> validator.validate(RolloutFailureClass.DNS_EDGE_MTLS, jwt))
                .isInstanceOf(RolloutFailureEvidenceValidator.InvalidEvidence.class);
        // full Windows SID
        ObjectNode sid = validDnsEvidence();
        sid.put("source", "S-1-5-21-1004336348-1177238915-682003330-512");
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
    void rejectsNonObjectEvidence() {
        assertThatThrownBy(() -> validator.validate(RolloutFailureClass.DNS_EDGE_MTLS,
                mapper.createArrayNode()))
                .isInstanceOf(RolloutFailureEvidenceValidator.InvalidEvidence.class);
    }

    @Test
    void hostnameWithPortIsNotMistakenForAnIp() {
        // edge_target "edge.acik.com:8443" is a legitimate host:port — the IPv6
        // scanner needs ≥2 colon groups, the IPv4 scanner 4 dotted octets, so a
        // hostname:port must NOT trip the raw scanner.
        assertThatCode(() -> validator.validate(RolloutFailureClass.DNS_EDGE_MTLS, validDnsEvidence()))
                .doesNotThrowAnyException();
    }
}
