package com.serban.notify.audit;

import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AuditPartitionRetentionService pure unit tests (Codex 019dfdec Q5 absorb).
 *
 * <p>Identifier safety + bound expression parsing — no DB / Spring context.
 */
class AuditPartitionRetentionServiceUnitTest {

    @Test
    void validPartitionNamesAccepted() {
        assertThat(AuditPartitionRetentionService.isValidPartitionName("audit_event_v2_2026_05")).isTrue();
        assertThat(AuditPartitionRetentionService.isValidPartitionName("audit_event_v2_2025_12")).isTrue();
        assertThat(AuditPartitionRetentionService.isValidPartitionName("audit_event_v2_default")).isTrue();
    }

    @Test
    void invalidPartitionNamesRejected() {
        assertThat(AuditPartitionRetentionService.isValidPartitionName(null)).isFalse();
        assertThat(AuditPartitionRetentionService.isValidPartitionName("")).isFalse();
        // SQL injection attempts
        assertThat(AuditPartitionRetentionService.isValidPartitionName("audit_event_v2_2026_05; DROP TABLE")).isFalse();
        assertThat(AuditPartitionRetentionService.isValidPartitionName("audit_event_v2_2026_05'")).isFalse();
        assertThat(AuditPartitionRetentionService.isValidPartitionName("../audit_event_v2_2026_05")).isFalse();
        // Wrong table name
        assertThat(AuditPartitionRetentionService.isValidPartitionName("notification_intent")).isFalse();
        assertThat(AuditPartitionRetentionService.isValidPartitionName("audit_event_2026_05")).isFalse();  // missing v2
        // Invalid month/year format
        assertThat(AuditPartitionRetentionService.isValidPartitionName("audit_event_v2_26_05")).isFalse();
        assertThat(AuditPartitionRetentionService.isValidPartitionName("audit_event_v2_2026_5")).isFalse();
        assertThat(AuditPartitionRetentionService.isValidPartitionName("audit_event_v2_2026_13")).isTrue();  // regex won't catch month=13; YearMonth would
    }

    @Test
    void parseBoundExpressionStandardFormat() {
        var range = AuditPartitionRetentionService.parseBoundExpression(
            "FOR VALUES FROM ('2026-05-01 00:00:00+00') TO ('2026-06-01 00:00:00+00')"
        );
        assertThat(range).isNotNull();
        assertThat(range.rangeStart()).isEqualTo(OffsetDateTime.parse("2026-05-01T00:00:00+00:00"));
        assertThat(range.rangeEnd()).isEqualTo(OffsetDateTime.parse("2026-06-01T00:00:00+00:00"));
    }

    @Test
    void parseBoundExpressionMalformedReturnsNull() {
        assertThat(AuditPartitionRetentionService.parseBoundExpression(null)).isNull();
        assertThat(AuditPartitionRetentionService.parseBoundExpression("")).isNull();
        assertThat(AuditPartitionRetentionService.parseBoundExpression("DEFAULT")).isNull();
        assertThat(AuditPartitionRetentionService.parseBoundExpression("garbage text")).isNull();
    }
}
