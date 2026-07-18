package com.example.meeting.config;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class MeetingSessionErasurePropertiesTest {

    @Test
    void enabledErasureRequiresScheduledWorkerAndCredentials() {
        MeetingSessionErasureProperties properties = new MeetingSessionErasureProperties();
        properties.setEnabled(true);
        properties.setSchedulingEnabled(false);

        assertThatThrownBy(properties::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("scheduling");

        properties.setSchedulingEnabled(true);
        assertThatThrownBy(properties::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("credentials");
    }
}
