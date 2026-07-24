package com.example.ethics.config;

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
}
