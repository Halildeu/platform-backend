package com.serban.notify.adapter;

import com.serban.notify.delivery.DeliveryTarget;
import com.serban.notify.template.RenderedMessage;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link TeamsAdaptiveCardPayloadBuilder} (Faz 23.6 M7
 * T4.1.2 — Codex {@code 019e496d} AGREE PARTIAL).
 */
class TeamsAdaptiveCardPayloadBuilderTest {

    @Test
    void buildsAdaptiveCardV14WithHeaderBodyFactSetCritical() {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("severity", "critical");
        meta.put("org_id", "default");
        meta.put("topic_key", "ops.drift-alarm");
        meta.put("correlation_id", "corr-abc-123");
        meta.put("occurred_at", "2026-05-21T10:00:00Z");

        DeliveryTarget target = new DeliveryTarget(
            "teams", "channel", null, "rh-1",
            "https://flow.example.com/webhook", "teams-default", meta
        );
        RenderedMessage message = new RenderedMessage(
            "Vault is unreachable",
            null,
            "Backend cannot reach Vault — quarantine triggered.",
            null
        );

        Map<String, Object> payload =
            TeamsAdaptiveCardPayloadBuilder.build(target, message, "Backend cannot reach Vault");

        // Adaptive Card root metadata
        assertThat(payload.get("$schema")).isEqualTo("http://adaptivecards.io/schemas/adaptive-card.json");
        assertThat(payload.get("type")).isEqualTo("AdaptiveCard");
        assertThat(payload.get("version")).isEqualTo("1.4");
        assertThat(payload.get("fallbackText")).asString().contains("Backend cannot reach Vault");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> body = (List<Map<String, Object>>) payload.get("body");
        assertThat(body).hasSize(3);

        // Header TextBlock
        Map<String, Object> header = body.get(0);
        assertThat(header.get("type")).isEqualTo("TextBlock");
        assertThat(header.get("size")).isEqualTo("Large");
        assertThat(header.get("weight")).isEqualTo("Bolder");
        assertThat(header.get("wrap")).isEqualTo(true);
        assertThat(header.get("text")).asString()
            .contains("[KRİTİK]", "Vault is unreachable");

        // Body TextBlock
        Map<String, Object> bodyBlock = body.get(1);
        assertThat(bodyBlock.get("type")).isEqualTo("TextBlock");
        assertThat(bodyBlock.get("wrap")).isEqualTo(true);
        assertThat(bodyBlock.get("text")).asString().contains("Backend cannot reach Vault");

        // FactSet
        Map<String, Object> factSet = body.get(2);
        assertThat(factSet.get("type")).isEqualTo("FactSet");
        @SuppressWarnings("unchecked")
        List<Map<String, String>> facts = (List<Map<String, String>>) factSet.get("facts");
        // Facts order: org_id → topic_key → correlation_id → occurred_at
        String joined = String.join(" ", facts.stream()
            .map(f -> f.get("title") + "=" + f.get("value")).toList());
        assertThat(joined).contains("org_id=default");
        assertThat(joined).contains("topic_key=ops.drift-alarm");
        assertThat(joined).contains("correlation_id=corr-abc-123");
        assertThat(joined).contains("occurred_at=2026-05-21T10:00:00Z");
    }

    @Test
    void severityBadgeMapping() {
        assertThat(TeamsAdaptiveCardPayloadBuilder.severityBadge("critical")).isEqualTo("[KRİTİK]");
        assertThat(TeamsAdaptiveCardPayloadBuilder.severityBadge("warning")).isEqualTo("[UYARI]");
        assertThat(TeamsAdaptiveCardPayloadBuilder.severityBadge("info")).isEqualTo("[BİLGİ]");
        assertThat(TeamsAdaptiveCardPayloadBuilder.severityBadge(null)).isEqualTo("[BİLGİ]");
        assertThat(TeamsAdaptiveCardPayloadBuilder.severityBadge("INFO")).isEqualTo("[BİLGİ]");
        assertThat(TeamsAdaptiveCardPayloadBuilder.severityBadge("debug")).isEqualTo("[DEBUG]");
    }

    @Test
    void emptyRoutingMetadataOmitsFactSet() {
        DeliveryTarget target = new DeliveryTarget(
            "teams", "channel", null, "rh-1",
            "https://flow.example.com/webhook", "teams-default"
            // 6-arg constructor: routingMetadata = Map.of()
        );
        RenderedMessage message = new RenderedMessage("Subject", null, "Body", null);

        Map<String, Object> payload =
            TeamsAdaptiveCardPayloadBuilder.build(target, message, "Body");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> body = (List<Map<String, Object>>) payload.get("body");
        // No FactSet block when routing metadata is empty
        assertThat(body).hasSize(2);
        assertThat(body.get(0).get("type")).isEqualTo("TextBlock");
        assertThat(body.get(1).get("type")).isEqualTo("TextBlock");
    }

    @Test
    void nullSubjectFallsBackToBildirim() {
        DeliveryTarget target = new DeliveryTarget(
            "teams", "channel", null, "rh-1",
            "https://flow.example.com/webhook", "teams-default", Map.of("severity", "info")
        );
        RenderedMessage message = new RenderedMessage(null, null, "Body", null);

        Map<String, Object> payload =
            TeamsAdaptiveCardPayloadBuilder.build(target, message, "Body");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> body = (List<Map<String, Object>>) payload.get("body");
        Map<String, Object> header = body.get(0);
        assertThat(header.get("text")).asString().contains("Bildirim");
    }

    @Test
    void fallbackTextAlwaysPresent() {
        Map<String, Object> meta = Map.of("severity", "info");
        DeliveryTarget target = new DeliveryTarget(
            "teams", "channel", null, "rh-1",
            "https://flow.example.com/webhook", "teams-default", meta
        );
        RenderedMessage message = new RenderedMessage("Hi", null, "Hi body", null);

        Map<String, Object> payload =
            TeamsAdaptiveCardPayloadBuilder.build(target, message, "Hi body");

        assertThat(payload).containsKey("fallbackText");
        assertThat(payload.get("fallbackText")).isEqualTo("Hi body");
    }

    @Test
    void factSetOmitsBlankFields() {
        Map<String, Object> meta = Map.of("severity", "warning");
        DeliveryTarget target = new DeliveryTarget(
            "teams", "channel", null, "rh-1",
            "https://flow.example.com/webhook", "teams-default", meta
        );
        RenderedMessage message = new RenderedMessage("Subject", null, "Body", null);

        Map<String, Object> payload =
            TeamsAdaptiveCardPayloadBuilder.build(target, message, "Body");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> body = (List<Map<String, Object>>) payload.get("body");
        // Severity drives header; FactSet omitted because no org/topic/correlation/time
        assertThat(body).hasSize(2);
    }

    @Test
    void truncateRespectsLimit() {
        String input = "a".repeat(500);
        String result = TeamsAdaptiveCardPayloadBuilder.truncate(input, 200);
        assertThat(result).hasSize(200);
        assertThat(result.charAt(199)).isEqualTo('…');
    }

    @Test
    void truncatePreservesShortInput() {
        assertThat(TeamsAdaptiveCardPayloadBuilder.truncate("ok", 100)).isEqualTo("ok");
    }

    @Test
    void malformedTimestampPassesThroughWithoutCrashing() {
        Map<String, Object> meta = Map.of(
            "severity", "info",
            "org_id", "default",
            "occurred_at", "not-a-valid-iso-8601"
        );
        DeliveryTarget target = new DeliveryTarget(
            "teams", "channel", null, "rh-1",
            "https://flow.example.com/webhook", "teams-default", meta
        );
        RenderedMessage message = new RenderedMessage("Subject", null, "Body", null);

        Map<String, Object> payload =
            TeamsAdaptiveCardPayloadBuilder.build(target, message, "Body");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> body = (List<Map<String, Object>>) payload.get("body");
        // Doesn't crash; FactSet still rendered with the raw timestamp value.
        assertThat(body).hasSize(3);
    }

    @Test
    void piiGuardSubscriberIdNotInFactSet() {
        // routingMetadata contains subscriber_id + email + phone; FactSet
        // must only include whitelist (org_id / topic_key / correlation_id / occurred_at).
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("severity", "info");
        meta.put("org_id", "default");
        meta.put("topic_key", "audit.security");
        meta.put("subscriber_id", "user-123-PII");
        meta.put("email", "test@example.com");
        meta.put("phone", "+905551234567");

        DeliveryTarget target = new DeliveryTarget(
            "teams", "channel", null, "rh-1",
            "https://flow.example.com/webhook", "teams-default", meta
        );
        RenderedMessage message = new RenderedMessage("Sec", null, "Body", null);

        Map<String, Object> payload =
            TeamsAdaptiveCardPayloadBuilder.build(target, message, "Body");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> body = (List<Map<String, Object>>) payload.get("body");
        Map<String, Object> factSet = body.get(2);
        @SuppressWarnings("unchecked")
        List<Map<String, String>> facts = (List<Map<String, String>>) factSet.get("facts");
        String joined = String.join("|", facts.stream()
            .map(f -> f.get("title") + "=" + f.get("value")).toList());

        // org_id + topic_key should be present
        assertThat(joined).contains("org_id=default");
        assertThat(joined).contains("topic_key=audit.security");
        // PII should NOT appear
        assertThat(joined).doesNotContain("user-123-PII");
        assertThat(joined).doesNotContain("test@example.com");
        assertThat(joined).doesNotContain("+905551234567");
    }
}
