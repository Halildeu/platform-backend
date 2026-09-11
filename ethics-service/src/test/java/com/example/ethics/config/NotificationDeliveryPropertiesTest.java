package com.example.ethics.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class NotificationDeliveryPropertiesTest {

    @Test
    void disabledWorkerDoesNotRequireCredentialsOrRecipient() {
        var properties = new NotificationDeliveryProperties();
        properties.setEnabled(false);
        assertThatCode(properties::validate).doesNotThrowAnyException();
    }

    @Test
    void enabledWorkerFailsClosedWithoutDedicatedSecretAndRecipient() {
        var properties = new NotificationDeliveryProperties();
        properties.setEnabled(true);
        assertThatThrownBy(properties::validate)
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void enabledWorkerAcceptsBoundedDedicatedConfiguration() {
        var properties = new NotificationDeliveryProperties();
        properties.setEnabled(true);
        properties.setClientSecret("synthetic-test-only-secret");
        properties.setRecipientSubscriberId("ethics-triage");
        assertThatCode(properties::validate).doesNotThrowAnyException();
    }

    // ── ES-301b (#1153) ────────────────────────────────────────────────────────────────

    @Test
    void escalationSignalsRequireADistinctSecondTierRecipient() {
        var properties = new NotificationDeliveryProperties();
        properties.setEnabled(true);
        properties.setClientSecret("synthetic-test-only-secret");
        properties.setRecipientSubscriberId("11");
        properties.setEscalationSignalsEnabled(true);
        assertThatThrownBy(properties::validate)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("escalation-recipient-subscriber-id");

        properties.setEscalationRecipientSubscriberId("11");
        assertThatThrownBy(properties::validate)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must differ");

        properties.setEscalationRecipientSubscriberId("42");
        assertThatCode(properties::validate).doesNotThrowAnyException();
    }

    @Test
    void escalationSignalsOffNeedNoSecondTierRecipient() {
        var properties = new NotificationDeliveryProperties();
        properties.setEnabled(true);
        properties.setClientSecret("synthetic-test-only-secret");
        properties.setRecipientSubscriberId("11");
        assertThat(properties.isEscalationSignalsEnabled()).isFalse();
        assertThatCode(properties::validate).doesNotThrowAnyException();
    }
}
