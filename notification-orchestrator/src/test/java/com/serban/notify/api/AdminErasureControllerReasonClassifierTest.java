package com.serban.notify.api;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link AdminErasureController#classifyErasureReason}
 * (Faz 23.2 M3 R2 PR-K4 — Codex {@code 019e4950} P1 absorb).
 *
 * <p>Free-form `reason` field must NEVER leak to INFO logs. The
 * classifier maps any operator narrative to a coarse enum label
 * (SELF_SERVICE / LEGAL_REQUEST / COMPLIANCE_AUDIT / ADMIN_INITIATED /
 * OTHER / UNKNOWN). Lossy by design.
 */
class AdminErasureControllerReasonClassifierTest {

    @Test
    void selfServiceSentinelClassified() {
        assertThat(AdminErasureController.classifyErasureReason("self-service-kvkk-art-11"))
            .isEqualTo("SELF_SERVICE");
        assertThat(AdminErasureController.classifyErasureReason("self_service"))
            .isEqualTo("SELF_SERVICE");
        assertThat(AdminErasureController.classifyErasureReason("KVKK-Art-11 right to erasure"))
            .isEqualTo("SELF_SERVICE");
    }

    @Test
    void legalKeywordsClassified() {
        assertThat(AdminErasureController.classifyErasureReason("Legal ticket LK-2026-451"))
            .isEqualTo("LEGAL_REQUEST");
        assertThat(AdminErasureController.classifyErasureReason("court order received"))
            .isEqualTo("LEGAL_REQUEST");
        assertThat(AdminErasureController.classifyErasureReason("ticket from legal team"))
            .isEqualTo("LEGAL_REQUEST");
    }

    @Test
    void complianceKeywordsClassified() {
        assertThat(AdminErasureController.classifyErasureReason("Compliance audit Q2"))
            .isEqualTo("COMPLIANCE_AUDIT");
        assertThat(AdminErasureController.classifyErasureReason("DPO request 2026-05-21"))
            .isEqualTo("COMPLIANCE_AUDIT");
        assertThat(AdminErasureController.classifyErasureReason("KVKK compliance review"))
            .isEqualTo("COMPLIANCE_AUDIT");
    }

    @Test
    void adminInitiatedKeywordsClassified() {
        assertThat(AdminErasureController.classifyErasureReason("Admin initiated test"))
            .isEqualTo("ADMIN_INITIATED");
        assertThat(AdminErasureController.classifyErasureReason("operator manual cleanup"))
            .isEqualTo("ADMIN_INITIATED");
    }

    @Test
    void unrecognizedFreeFormFallsBackToOther() {
        assertThat(AdminErasureController.classifyErasureReason("User contacted support via Twitter DM"))
            .isEqualTo("OTHER");
        assertThat(AdminErasureController.classifyErasureReason("Bug remediation"))
            .isEqualTo("OTHER");
    }

    @Test
    void nullOrBlankReturnsUnknown() {
        assertThat(AdminErasureController.classifyErasureReason(null)).isEqualTo("UNKNOWN");
        assertThat(AdminErasureController.classifyErasureReason("")).isEqualTo("UNKNOWN");
        assertThat(AdminErasureController.classifyErasureReason("   ")).isEqualTo("UNKNOWN");
    }

    @Test
    void classifierDoesNotLeakInputContent() {
        // Even reasons that contain PII-like strings (user email, ticket
        // IDs) get reduced to the coarse label — the input is consumed
        // for classification but the output stays bounded.
        String result = AdminErasureController.classifyErasureReason(
            "Legal ticket: user contacted at alice@example.com via SOC-1234 ticket-id");
        assertThat(result).isEqualTo("LEGAL_REQUEST");
        // The result MUST NOT include the original PII fragments.
        assertThat(result).doesNotContain("alice@example.com");
        assertThat(result).doesNotContain("SOC-1234");
    }

    @Test
    void classifierIsLocaleRootSafe() {
        // Codex Locale.ROOT defensive: Turkish dotless-I corruption guard.
        // "LEGAL" lowercased with Turkish locale would still match "legal"
        // because L doesn't have the I/i edge case; verify defensive
        // behavior anyway.
        assertThat(AdminErasureController.classifyErasureReason("LEGAL TICKET"))
            .isEqualTo("LEGAL_REQUEST");
        assertThat(AdminErasureController.classifyErasureReason("Legal Ticket"))
            .isEqualTo("LEGAL_REQUEST");
    }
}
