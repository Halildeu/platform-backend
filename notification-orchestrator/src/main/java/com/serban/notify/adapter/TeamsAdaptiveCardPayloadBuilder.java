package com.serban.notify.adapter;

import com.serban.notify.delivery.DeliveryTarget;
import com.serban.notify.template.RenderedMessage;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Microsoft Teams Adaptive Card payload builder (Faz 23.6 M7 T4.1.2 —
 * Codex {@code 019e496d} AGREE PARTIAL iter-1).
 *
 * <p>Produces a canonical Adaptive Card v1.4 JSON payload for Power
 * Automate flow webhook POSTs:
 * <pre>
 * {
 *   "$schema": "http://adaptivecards.io/schemas/adaptive-card.json",
 *   "type": "AdaptiveCard",
 *   "version": "1.4",
 *   "fallbackText": "&lt;plain text fallback&gt;",
 *   "body": [
 *     {"type":"TextBlock","text":"&lt;severity badge + subject&gt;","size":"Large","weight":"Bolder","wrap":true},
 *     {"type":"TextBlock","text":"&lt;body&gt;","wrap":true},
 *     {"type":"FactSet","facts":[{"title":"org_id","value":"..."}, ...]}
 *   ]
 * }
 * </pre>
 *
 * <p>Codex 019e496d Q2 scope decisions:
 * <ul>
 *   <li>TextBlock (header + body) + FactSet (provenance): yes (v1 closure)</li>
 *   <li>ActionSet: deferred — Power Automate flow webhook callback
 *       contract gerekir; v1 değil</li>
 *   <li>Threading: not supported in connector path; deferred to Graph
 *       API future work</li>
 *   <li>Card version 1.4: Teams güncel destek; conservative</li>
 *   <li>PII guard: subscriber_id, email, phone, raw recipient FactSet'e
 *       girmez (only org_id / topic_key / correlation_id / occurred_at
 *       — Slack Block Kit ile aynı whitelist)</li>
 * </ul>
 *
 * <p>Adaptive Card truncation defensive (Teams card length limits):
 * <ul>
 *   <li>Header TextBlock text: 256 chars</li>
 *   <li>Body TextBlock text: 4000 chars</li>
 *   <li>FactSet title: 64 chars; value: 256 chars</li>
 *   <li>fallbackText: 1000 chars</li>
 * </ul>
 */
public final class TeamsAdaptiveCardPayloadBuilder {

    private static final int HEADER_LIMIT = 256;
    private static final int BODY_LIMIT = 4000;
    private static final int FACT_TITLE_LIMIT = 64;
    private static final int FACT_VALUE_LIMIT = 256;
    private static final int FALLBACK_LIMIT = 1000;
    private static final DateTimeFormatter TS_FMT =
        DateTimeFormatter.ISO_OFFSET_DATE_TIME;

    private TeamsAdaptiveCardPayloadBuilder() {}

    /**
     * Builds the Adaptive Card payload as a Jackson-serializable Map.
     *
     * @param target delivery target (channel + routing metadata)
     * @param message rendered subject/body
     * @param fallbackText resolved plain text (for fallbackText field)
     * @return mutable map suitable for {@code objectMapper.writeValueAsString}
     */
    public static Map<String, Object> build(
        DeliveryTarget target,
        RenderedMessage message,
        String fallbackText
    ) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("$schema", "http://adaptivecards.io/schemas/adaptive-card.json");
        payload.put("type", "AdaptiveCard");
        payload.put("version", "1.4");
        payload.put("fallbackText", truncate(fallbackText, FALLBACK_LIMIT));

        List<Map<String, Object>> body = new ArrayList<>();
        body.add(headerBlock(target, message));
        body.add(bodyBlock(message, fallbackText));
        Map<String, Object> factSet = factSetBlock(target);
        if (factSet != null) {
            body.add(factSet);
        }
        payload.put("body", body);
        return payload;
    }

    private static Map<String, Object> headerBlock(DeliveryTarget target, RenderedMessage message) {
        String severity = severityFromRouting(target.routingMetadata());
        String badge = severityBadge(severity);
        String subject = (message.subject() != null && !message.subject().isBlank())
            ? message.subject()
            : "Bildirim";
        String headerText = badge + " " + subject;

        Map<String, Object> header = new LinkedHashMap<>();
        header.put("type", "TextBlock");
        header.put("text", truncate(headerText, HEADER_LIMIT));
        header.put("size", "Large");
        header.put("weight", "Bolder");
        header.put("wrap", true);
        return header;
    }

    private static Map<String, Object> bodyBlock(RenderedMessage message, String fallbackText) {
        // Prefer plain body_text; subject fallback when body absent
        // (Slack parity). HTML body NOT used — Adaptive Card supports
        // limited markdown, not arbitrary HTML.
        String body = (message.bodyText() != null && !message.bodyText().isBlank())
            ? message.bodyText()
            : fallbackText;

        Map<String, Object> textBlock = new LinkedHashMap<>();
        textBlock.put("type", "TextBlock");
        textBlock.put("text", truncate(body, BODY_LIMIT));
        textBlock.put("wrap", true);
        return textBlock;
    }

    private static Map<String, Object> factSetBlock(DeliveryTarget target) {
        Map<String, Object> meta = target.routingMetadata();
        if (meta == null || meta.isEmpty()) {
            return null;
        }

        List<Map<String, String>> facts = new ArrayList<>();
        addFact(facts, "org_id", stringValue(meta, "org_id"));
        addFact(facts, "topic_key", stringValue(meta, "topic_key"));
        addFact(facts, "correlation_id", stringValue(meta, "correlation_id"));
        String tsRaw = stringValue(meta, "occurred_at");
        String tsFormatted = formatTimestamp(tsRaw);
        if (tsFormatted != null) {
            addFact(facts, "occurred_at", tsFormatted);
        }

        if (facts.isEmpty()) {
            return null;
        }

        Map<String, Object> factSet = new LinkedHashMap<>();
        factSet.put("type", "FactSet");
        factSet.put("facts", facts);
        return factSet;
    }

    private static void addFact(List<Map<String, String>> facts, String title, String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        Map<String, String> fact = new LinkedHashMap<>();
        fact.put("title", truncate(title, FACT_TITLE_LIMIT));
        fact.put("value", truncate(value, FACT_VALUE_LIMIT));
        facts.add(fact);
    }

    /**
     * Severity badge for the header TextBlock. Falls back to a neutral
     * marker when severity is unknown. Locale.ROOT to avoid Turkish
     * dotless-I corruption ("INFO" → "iNFO" instead of "INFO" if
     * Locale.getDefault() is "tr-TR").
     */
    static String severityBadge(String severity) {
        if (severity == null) {
            return "[BİLGİ]";
        }
        return switch (severity.toLowerCase(Locale.ROOT)) {
            case "critical" -> "[KRİTİK]";
            case "warning"  -> "[UYARI]";
            case "info"     -> "[BİLGİ]";
            default         -> "[" + severity.toUpperCase(Locale.ROOT) + "]";
        };
    }

    private static String severityFromRouting(Map<String, Object> meta) {
        if (meta == null) return null;
        Object value = meta.get("severity");
        return value != null ? value.toString() : null;
    }

    private static String stringValue(Map<String, Object> meta, String key) {
        Object value = meta.get(key);
        return value != null ? value.toString() : null;
    }

    private static String formatTimestamp(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            OffsetDateTime parsed = OffsetDateTime.parse(raw);
            return parsed.withOffsetSameInstant(ZoneOffset.UTC).format(TS_FMT);
        } catch (Exception ignored) {
            return raw;
        }
    }

    static String truncate(String input, int limit) {
        if (input == null) return "";
        if (input.length() <= limit) return input;
        return input.substring(0, Math.max(0, limit - 1)) + "…";
    }
}
