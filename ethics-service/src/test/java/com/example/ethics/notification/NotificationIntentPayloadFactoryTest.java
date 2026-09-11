package com.example.ethics.notification;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.ethics.config.NotificationDeliveryProperties;
import com.example.ethics.model.NotificationOutbox;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
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

    // ── ES-301b (#1153): level routing ─────────────────────────────────────────────────

    private NotificationIntentPayloadFactory factory() {
        var properties = new NotificationDeliveryProperties();
        properties.setRecipientSubscriberId("11");
        properties.setEscalationRecipientSubscriberId("42");
        properties.setLocale("tr-TR");
        properties.setChannel("in-app");
        return new NotificationIntentPayloadFactory(properties);
    }

    private static NotificationOutbox row(String event) {
        return new NotificationOutbox(UUID.randomUUID(), UUID.randomUUID(), event,
                Instant.parse("2026-09-11T00:00:00Z"));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> recipient(Map<String, Object> intent) {
        return ((java.util.List<Map<String, Object>>) intent.get("recipients")).get(0);
    }

    @Test
    @DisplayName("seviye 1 birinci kademeye, yeni şablonla, level=1 ve info olarak gider")
    void levelOneStaysWithTheFirstTier() {
        Map<String, Object> intent = factory().build(row("CASE_ESCALATED_L1"));
        assertThat(intent.get("topicKey")).isEqualTo("ethics.case.escalation");
        assertThat(((Map<?, ?>) intent.get("template")).get("templateId")).isEqualTo("ethics.case.escalated");
        assertThat(((Map<?, ?>) intent.get("template")).get("version")).isEqualTo(1);
        assertThat(recipient(intent).get("subscriberId")).isEqualTo("11");
        assertThat(intent.get("severity")).isEqualTo("info");
        assertThat(intent.get("payload")).isEqualTo(Map.of("level", 1));
    }

    @Test
    @DisplayName("seviye 2 ve üstü ikinci kademeye warning olarak gider; payload yalnız tamsayı level")
    void levelTwoAndAboveGoToTheSecondTier() {
        for (int level = 2; level <= 5; level++) {
            Map<String, Object> intent = factory().build(row("CASE_ESCALATED_L" + level));
            assertThat(recipient(intent).get("subscriberId")).as("L" + level).isEqualTo("42");
            assertThat(intent.get("severity")).isEqualTo("warning");
            assertThat(intent.get("payload")).isEqualTo(Map.of("level", level));
            assertThat(intent.get("channels")).isEqualTo(java.util.List.of("in-app"));
        }
    }

    @Test
    @DisplayName("eskalasyon intent'i de vaka kimliği taşımaz")
    void escalationIntentCarriesNoCaseIdentity() throws Exception {
        String serialized = new ObjectMapper().writeValueAsString(factory().build(row("CASE_ESCALATED_L2")));
        assertThat(serialized)
                .doesNotContain("caseId").doesNotContain("reportId").doesNotContain("receipt")
                .doesNotContain("subject").doesNotContain("narrative").doesNotContain("description")
                .doesNotContain("category").doesNotContain("reporter");
        assertThat(serialized).contains("\"payload\":{\"level\":2}");
    }

    @Test
    @DisplayName("dört eski olay birebir aynı etkinlik intent'ini üretir (kimlik dışında)")
    void legacyEventsKeepTheirActivityIntent() {
        for (String event : java.util.List.of("NEW_REPORT", "REPORTER_MESSAGE", "SLA_BREACH", "SLA_APPROACHING")) {
            Map<String, Object> intent = factory().build(row(event));
            assertThat(intent.get("topicKey")).as(event).isEqualTo("ethics.case.activity");
            assertThat(((Map<?, ?>) intent.get("template")).get("templateId")).isEqualTo("ethics.case.activity");
            assertThat(recipient(intent).get("subscriberId")).isEqualTo("11");
            assertThat(intent.get("severity")).isEqualTo("info");
            assertThat(intent.get("payload")).isEqualTo(Map.of());
            assertThat(intent.keySet()).containsExactly("intentId", "idempotencyKey", "orgId", "topicKey",
                    "severity", "dataClassification", "recipients", "template", "channels", "payload");
        }
    }
}
