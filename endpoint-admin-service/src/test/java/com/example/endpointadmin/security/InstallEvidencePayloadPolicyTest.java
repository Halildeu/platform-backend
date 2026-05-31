package com.example.endpointadmin.security;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * BE-021 — unit tests for the DOUBLE-REDACT install payload policy
 * (Codex 019e6dfb iter-3 P0-2 / C). Coverage:
 *
 * <ul>
 *   <li>{@code validate} fails closed on a forbidden key / value</li>
 *   <li>{@code redact} drops forbidden keys + masks forbidden value
 *       patterns + truncates oversized summaries</li>
 *   <li>{@code redact} idempotency: {@code redact(redact(x))} structurally
 *       equals {@code redact(x)}</li>
 *   <li>top-level allowlist drops unknown keys</li>
 * </ul>
 */
class InstallEvidencePayloadPolicyTest {

    private final InstallEvidencePayloadPolicy policy = new InstallEvidencePayloadPolicy(
            InstallEvidencePayloadPolicy.MAX_REDACTED_BYTES_DEFAULT,
            InstallEvidencePayloadPolicy.MAX_SUMMARY_BYTES_DEFAULT);

    @Test
    void validatePassesOnCleanInstallPayload() {
        Map<String, Object> details = baseInstallDetails();
        assertThatCode(() -> policy.validate(details)).doesNotThrowAnyException();
    }

    @Test
    void validateRejectsForbiddenLicenseKey() {
        Map<String, Object> details = baseInstallDetails();
        details.put("licenseKey", "test-fake-license-marker-no-real-secret");
        assertThatThrownBy(() -> policy.validate(details))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("licenseKey");
    }

    @Test
    void validateRejectsWindowsSidLiteralAnywhere() {
        Map<String, Object> details = baseInstallDetails();
        details.put("stdoutSummary", "user=John SID=S-1-5-21-1234567890-987654321-111222333-1001 done");
        assertThatThrownBy(() -> policy.validate(details))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("SID");
    }

    @Test
    void validateRejectsJwtTokenAnywhere() {
        Map<String, Object> details = baseInstallDetails();
        // Synthetic JWT shape — header/payload/signature segments are
        // intentionally meaningless. Composed at runtime so gitleaks does
        // not flag the source line (PR #310 / #317 CI pattern).
        String jwtFixture = "eyJ" + "headerPlaceholder.eyJ" + "payloadPlaceholder.sig" + "Placeholder";
        details.put("stderrSummary", "auth header " + jwtFixture);
        assertThatThrownBy(() -> policy.validate(details))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("JWT");
    }

    @Test
    void redactDropsForbiddenKeysAtAnyDepth() {
        Map<String, Object> details = baseInstallDetails();
        Map<String, Object> detection = (Map<String, Object>) details.get("detection");
        detection.put("token", "should-be-dropped");
        details.put("password", "outer-drop");

        Map<String, Object> redacted = policy.redact(details);
        assertThat(redacted).doesNotContainKey("password");
        Map<String, Object> redactedDetection = (Map<String, Object>) redacted.get("detection");
        assertThat(redactedDetection).doesNotContainKey("token");
    }

    @Test
    void redactDropsTopLevelKeysNotInAllowlist() {
        Map<String, Object> details = baseInstallDetails();
        details.put("processList", List.of("System", "AcmeApp.exe"));   // not on allowlist
        Map<String, Object> redacted = policy.redact(details);
        assertThat(redacted).doesNotContainKey("processList");
        assertThat(redacted).containsKey("stage");
        assertThat(redacted).containsKey("postVerification");
    }

    @Test
    void redactPreservesInstallWrapperEvidenceAndDropsUnknownInstallKeys() {
        // BE-028: the agent ships the AG-027 InstallResult under
        // `details.install` (COMMAND-CONTRACT §11.2). The redaction must keep
        // the wrapper + its authoritative postVerification while dropping
        // unknown / forbidden install sub-keys.
        Map<String, Object> details = baseInstallDetails();
        Map<String, Object> install = new java.util.LinkedHashMap<>();
        install.put("finalStatus", "SUCCEEDED_NOOP");
        Map<String, Object> pv = new java.util.LinkedHashMap<>();
        pv.put("status", "SATISFIED");
        pv.put("authority", "AUTHORITATIVE");
        pv.put("matchedPackageId", "7zip.7zip");
        install.put("postVerification", pv);
        install.put("processEnvironment", "SECRET=abc");   // forbidden key
        install.put("rawSpelunk", "drop me");              // not on install allow-list
        // BE-028 (Codex 019e7f93): raw installer output + the egress sub-tree
        // are deliberately EXCLUDED from the install allow-list (weak backend
        // redaction of raw tails / un-validated egress surface).
        install.put("stdoutTail", "C:\\Users\\bob secret LICENSEKEY=xyz");
        install.put("stderrTail", "err");
        install.put("egress", new java.util.LinkedHashMap<>(java.util.Map.of(
                "supported", Boolean.TRUE, "sources", "https://cdn.example")));
        details.put("install", install);

        Map<String, Object> redacted = policy.redact(details);

        @SuppressWarnings("unchecked")
        Map<String, Object> redactedInstall =
                (Map<String, Object>) redacted.get("install");
        assertThat(redactedInstall).isNotNull();
        assertThat(redactedInstall)
                .containsKey("postVerification")
                .containsEntry("finalStatus", "SUCCEEDED_NOOP")
                .doesNotContainKeys("processEnvironment", "rawSpelunk",
                        "stdoutTail", "stderrTail", "egress");
        @SuppressWarnings("unchecked")
        Map<String, Object> redactedPv =
                (Map<String, Object>) redactedInstall.get("postVerification");
        assertThat(redactedPv)
                .containsEntry("status", "SATISFIED")
                .containsEntry("authority", "AUTHORITATIVE")
                .containsEntry("matchedPackageId", "7zip.7zip");
    }

    @Test
    void redactMasksWindowsUsersPathInValues() {
        Map<String, Object> details = baseInstallDetails();
        details.put("stdoutSummary", "extracted to C:\\Users\\Bob\\AppData\\Local done");
        Map<String, Object> redacted = policy.redact(details);
        assertThat((String) redacted.get("stdoutSummary"))
                .doesNotContainIgnoringCase("C:\\Users\\Bob")
                .contains(InstallEvidencePayloadPolicy.REDACTED_LITERAL);
    }

    @Test
    void redactTruncatesOversizedSummaryAndAppendsStickySuffix() {
        Map<String, Object> details = baseInstallDetails();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 4 * 1024; i++) {
            sb.append('x');
        }
        details.put("stdoutSummary", sb.toString());

        Map<String, Object> redacted = policy.redact(details);
        String truncated = (String) redacted.get("stdoutSummary");
        assertThat(truncated)
                .endsWith(InstallEvidencePayloadPolicy.TRUNCATE_SUFFIX);
        assertThat(truncated.length())
                .isLessThanOrEqualTo(InstallEvidencePayloadPolicy.MAX_SUMMARY_BYTES_DEFAULT
                        + InstallEvidencePayloadPolicy.TRUNCATE_SUFFIX.length());
    }

    @Test
    void redactIsIdempotent() {
        Map<String, Object> details = baseInstallDetails();
        details.put("password", "drop-me");
        details.put("stdoutSummary", "user path C:\\Users\\Bob\\app.log finished");
        details.put("processList", List.of("not", "allowed"));   // outside allowlist

        Map<String, Object> once = policy.redact(details);
        Map<String, Object> twice = policy.redact(once);
        assertThat(twice).isEqualTo(once);
    }

    private static Map<String, Object> baseInstallDetails() {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("stage", "post_install");
        details.put("exitCode", 0);
        details.put("durationMs", 12000);
        details.put("installerVersion", "1.2.3");
        details.put("catalogItemId", "7zip-7zip-stable");
        details.put("catalogPackageId", "7zip.7zip");
        Map<String, Object> detection = new LinkedHashMap<>();
        detection.put("packageId", "7zip.7zip");
        detection.put("version", "24.07");
        details.put("detection", detection);
        Map<String, Object> postVerification = new LinkedHashMap<>();
        postVerification.put("status", "SATISFIED");
        postVerification.put("ruleType", "WINGET_PACKAGE");
        details.put("postVerification", postVerification);
        details.put("stdoutSummary", "installed OK");
        details.put("stderrSummary", "");
        return details;
    }
}
