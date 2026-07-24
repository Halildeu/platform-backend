package com.example.ethics.notification;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.ethics.config.NotificationDeliveryProperties;
import com.example.ethics.model.NotificationOutbox;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class NotificationIntentPayloadFactoryTest {

    @Test
    void transportContractContainsNoCaseIdentityOrNarrativeMaterial() throws Exception {
        var properties = new NotificationDeliveryProperties();
        properties.setRecipientSubscriberId("ethics-triage");
        properties.setLocale("tr-TR");
        properties.setChannel("inapp");
        var factory = new NotificationIntentPayloadFactory(properties);
        UUID orgId = UUID.randomUUID();
        UUID independentDeliveryId = UUID.randomUUID();
        UUID forbiddenCaseId = UUID.randomUUID();
        var row = new NotificationOutbox(
                independentDeliveryId,
                orgId,
                NotificationOutboxPublisher.NEW_REPORT,
                Instant.parse("2026-07-24T00:00:00Z"));

        Map<String, Object> payload = factory.build(row);
        String serialized = new ObjectMapper().writeValueAsString(payload);

        assertThat(payload.get("orgId")).isEqualTo(orgId.toString());
        assertThat(payload.get("topicKey"))
                .isEqualTo(NotificationIntentPayloadFactory.TOPIC_KEY);
        assertThat(payload.get("payload")).isEqualTo(Map.of());
        assertThat(serialized).contains(independentDeliveryId.toString());
        assertThat(serialized)
                .doesNotContain(forbiddenCaseId.toString())
                .doesNotContain("caseId")
                .doesNotContain("reportId")
                .doesNotContain("receipt")
                .doesNotContain("subject")
                .doesNotContain("narrative")
                .doesNotContain("description")
                .doesNotContain("category")
                .doesNotContain("reporter");
    }
}
