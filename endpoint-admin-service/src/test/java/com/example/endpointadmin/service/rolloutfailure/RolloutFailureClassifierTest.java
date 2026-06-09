package com.example.endpointadmin.service.rolloutfailure;

import com.example.endpointadmin.model.CommandType;
import com.example.endpointadmin.model.RolloutClassificationConfidence;
import com.example.endpointadmin.model.RolloutFailureClass;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Evidence-first classification matrix for §9.2 slice-2a. Every produced
 * Classified.evidence is cross-checked against the real RolloutFailureEvidenceValidator,
 * so a class is emitted ONLY when schema-valid evidence is buildable.
 */
class RolloutFailureClassifierTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final RolloutFailureClassifier classifier = new RolloutFailureClassifier(mapper);
    private final RolloutFailureEvidenceValidator validator = new RolloutFailureEvidenceValidator(mapper);

    private RolloutFailureClassifier.Signal sig(CommandType type, String errorCode, Integer exitCode,
                                                boolean errMsg, Map<String, Object> payload) {
        return new RolloutFailureClassifier.Signal(UUID.randomUUID(), UUID.randomUUID(), type,
                errorCode, exitCode, errMsg, payload);
    }

    private void assertEvidenceValidates(RolloutFailureClassifier.Classified c) {
        assertThatCode(() -> validator.validate(c.failureClass(), c.evidence()))
                .as("classifier evidence must pass the validator for %s", c.failureClass())
                .doesNotThrowAnyException();
    }

    @Test
    void installerMsiFromExitCodeIsHighConfidence() {
        Optional<RolloutFailureClassifier.Classified> r = classifier.classify(
                sig(CommandType.INSTALL_SOFTWARE, "INSTALL_FAILED_MSI", 1627, true, Map.of()));
        assertThat(r).isPresent();
        assertThat(r.get().failureClass()).isEqualTo(RolloutFailureClass.INSTALLER_MSI);
        assertThat(r.get().confidence()).isEqualTo(RolloutClassificationConfidence.HIGH);
        assertThat(r.get().evidence().get("msi_exit_code").asInt()).isEqualTo(1627);
        assertThat(r.get().evidence().get("log_excerpt_redacted").asText()).startsWith("[redacted");
        assertEvidenceValidates(r.get());
    }

    @Test
    void nonMsiInstallExitIsSkipped() {
        // INSTALL with a non-zero exit but NO MSI signal in the error code is NOT
        // an MSI failure (could be WINGET/EXE) — skip rather than mislabel.
        assertThat(classifier.classify(
                sig(CommandType.INSTALL_SOFTWARE, "WINGET_INSTALL_FAILED", 1, true, Map.of())))
                .isEmpty();
    }

    @Test
    void installerMsiParsedFromErrorCodeIsMediumConfidence() {
        Optional<RolloutFailureClassifier.Classified> r = classifier.classify(
                sig(CommandType.INSTALL_SOFTWARE, "INSTALL_FAILED_MSI_1603", null, false, Map.of()));
        assertThat(r).isPresent();
        assertThat(r.get().failureClass()).isEqualTo(RolloutFailureClass.INSTALLER_MSI);
        assertThat(r.get().confidence()).isEqualTo(RolloutClassificationConfidence.MEDIUM);
        assertThat(r.get().evidence().get("msi_exit_code").asInt()).isEqualTo(1603);
        assertThat(r.get().evidence().get("log_excerpt_redacted").isNull()).isTrue();
        assertEvidenceValidates(r.get());
    }

    @Test
    void installerMsiWithoutAnyExitCodeSkips() {
        assertThat(classifier.classify(
                sig(CommandType.INSTALL_SOFTWARE, "GENERIC_INSTALL_FAIL", null, true, Map.of())))
                .isEmpty();
    }

    @Test
    void serviceHmacModeOnlyWithTruthfulServiceStateAndMode() {
        // missing payload fields → skip
        assertThat(classifier.classify(
                sig(CommandType.COLLECT_INVENTORY, "HMAC_VALIDATION_FAILED", null, false, Map.of())))
                .isEmpty();
        // truthful service_state + agent_mode (nested under result_payload.details
        // as submitResult writes it) → match
        Optional<RolloutFailureClassifier.Classified> r = classifier.classify(
                sig(CommandType.COLLECT_INVENTORY, "HMAC_VALIDATION_FAILED", null, false,
                        Map.of("details", Map.of("service_state", "STOPPED", "agent_mode", "hmac"))));
        assertThat(r).isPresent();
        assertThat(r.get().failureClass()).isEqualTo(RolloutFailureClass.SERVICE_HMAC_MODE);
        assertThat(r.get().evidence().get("service_state").asText()).isEqualTo("STOPPED");
        assertThat(r.get().evidence().get("hmac_error_code").asText()).isEqualTo("HMAC_VALIDATION_FAILED");
        assertEvidenceValidates(r.get());
    }

    @Test
    void serviceDetailsWithoutHmacMarkerDoNotProduceHmac() {
        // agent details present but NO HMAC|MODE signal in the error code → not HMAC
        // (and nothing else matches) → skip, never mislabel.
        assertThat(classifier.classify(
                sig(CommandType.COLLECT_INVENTORY, "GENERIC_FAILED", null, false,
                        Map.of("details", Map.of("service_state", "STOPPED", "agent_mode", "hmac")))))
                .isEmpty();
    }

    @Test
    void backendResultSubmitIsNotPreemptedByServiceDetails() {
        // a BACKEND_RESULT_SUBMIT result that happens to carry agent details must NOT
        // be mislabeled SERVICE_HMAC_MODE (no HMAC marker → HMAC skipped → BACKEND wins)
        Optional<RolloutFailureClassifier.Classified> r = classifier.classify(
                sig(CommandType.COLLECT_INVENTORY, "RESULT_SUBMIT_409", null, false,
                        Map.of("details", Map.of("service_state", "RUNNING", "agent_mode", "hmac"))));
        assertThat(r).isPresent();
        assertThat(r.get().failureClass()).isEqualTo(RolloutFailureClass.BACKEND_RESULT_SUBMIT);
    }

    @Test
    void backendResultSubmitParsesHttpStatus() {
        Optional<RolloutFailureClassifier.Classified> r = classifier.classify(
                sig(CommandType.UPDATE_AGENT, "RESULT_SUBMIT_409", null, false, Map.of()));
        assertThat(r).isPresent();
        assertThat(r.get().failureClass()).isEqualTo(RolloutFailureClass.BACKEND_RESULT_SUBMIT);
        assertThat(r.get().confidence()).isEqualTo(RolloutClassificationConfidence.MEDIUM);
        assertThat(r.get().evidence().get("result_submit_http_status").asInt()).isEqualTo(409);
        assertThat(r.get().evidence().get("backend_error_code").asText()).isEqualTo("RESULT_SUBMIT_409");
        assertEvidenceValidates(r.get());
    }

    @Test
    void unclassifiableResultSkips() {
        assertThat(classifier.classify(
                sig(CommandType.COLLECT_INVENTORY, "WEIRD_UNKNOWN", null, false, Map.of())))
                .isEmpty();
    }

    @Test
    void rawPiiInServiceStateIsRejectedByValidatorCrossCheck() {
        // a raw IP smuggled into service_state must fail the validator (the classifier
        // builds it, the validator is the final fail-closed gate)
        Optional<RolloutFailureClassifier.Classified> r = classifier.classify(
                sig(CommandType.COLLECT_INVENTORY, "HMAC_FAIL", null, false,
                        Map.of("details", Map.of("service_state", "down at 10.0.0.5", "agent_mode", "hmac"))));
        assertThat(r).isPresent();
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> validator.validate(r.get().failureClass(), r.get().evidence()))
                .isInstanceOf(RolloutFailureEvidenceValidator.InvalidEvidence.class);
    }
}
