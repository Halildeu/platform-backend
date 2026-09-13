package com.example.common.meeting.events;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Anonymous, transport-scoped attribution; no names, voiceprints or copied speech. */
public record SpeakerAttribution(UUID scope, List<Turn> turns) {
    public static final String SCHEMA_V2 = "audioGateway.directSttTranscriptResult.v2";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Set<String> TURN_FIELDS =
            Set.of("speaker", "textStart", "textEnd", "startMs", "endMs");

    public SpeakerAttribution {
        if (scope == null || turns == null || turns.isEmpty() || turns.size() > 512) {
            throw invalid();
        }
        turns = List.copyOf(turns);
    }

    public record Turn(String speaker, int textStart, int textEnd, long startMs, long endMs) {
        public Turn {
            if (speaker == null || !speaker.matches("S[1-9][0-9]{0,2}|SPEAKER_[0-9]{2,3}|UU")
                    || textStart < 0 || textEnd <= textStart || startMs < 0 || endMs < startMs) {
                throw invalid();
            }
        }
    }

    public static UUID scope(String tenant, String meeting, String session, long epoch) {
        if (tenant == null || meeting == null || session == null || epoch < 0) throw invalid();
        UUID tenantId;
        try {
            tenantId = UUID.fromString(tenant);
        } catch (IllegalArgumentException ex) {
            tenantId = UUID.nameUUIDFromBytes(("company:" + tenant.trim()).getBytes(StandardCharsets.UTF_8));
        }
        String key = "speaker-v1:" + tenantId + ":" + UUID.fromString(meeting)
                + ":" + session.length() + ":" + session + ":" + epoch;
        return UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8));
    }

    public UUID speakerId(String speaker) {
        return "UU".equals(speaker) ? null : UUID.nameUUIDFromBytes(
                (scope + ":" + speaker).getBytes(StandardCharsets.UTF_8));
    }

    /** UTF-16 text offsets (Java/JavaScript), millisecond offsets inside this audio window.
     * Text spans do not overlap; acoustic spans may overlap for simultaneous speech. */
    public static List<Turn> parseTurns(JsonNode node, String text, long durationMs) {
        if (node == null || !node.isArray() || node.isEmpty() || node.size() > 512
                || text == null || durationMs < 0) throw invalid();
        List<Turn> turns = new ArrayList<>();
        int previousEnd = 0;
        for (JsonNode item : node) {
            if (!item.isObject() || item.size() != TURN_FIELDS.size()) throw invalid();
            item.fieldNames().forEachRemaining(field -> {
                if (!TURN_FIELDS.contains(field)) throw invalid();
            });
            if (!item.path("speaker").isTextual()) throw invalid();
            for (String field : List.of("textStart", "textEnd", "startMs", "endMs")) {
                if (!item.path(field).isIntegralNumber() || !item.path(field).canConvertToLong()) throw invalid();
            }
            if (!item.path("textStart").canConvertToInt() || !item.path("textEnd").canConvertToInt()) throw invalid();
            Turn turn = new Turn(item.path("speaker").textValue(), item.path("textStart").intValue(),
                    item.path("textEnd").intValue(), item.path("startMs").longValue(), item.path("endMs").longValue());
            if (turn.textStart() < previousEnd || turn.textEnd() > text.length()
                    || turn.endMs() > durationMs || splitsSurrogate(text, turn.textStart())
                    || splitsSurrogate(text, turn.textEnd())
                    || !text.substring(previousEnd, turn.textStart()).isBlank()) throw invalid();
            previousEnd = turn.textEnd();
            turns.add(turn);
        }
        if (!text.substring(previousEnd).isBlank()) throw invalid();
        return List.copyOf(turns);
    }

    public static SpeakerAttribution parse(String encoded, UUID expectedScope, String text, long durationMs) {
        if (encoded == null || encoded.length() > 131072) throw invalid();
        try {
            JsonNode node = JSON.readTree(encoded);
            if (node == null || !node.isObject() || node.size() != 2
                    || !node.path("scope").isTextual()
                    || !expectedScope.toString().equals(node.path("scope").textValue())) throw invalid();
            return new SpeakerAttribution(expectedScope, parseTurns(node.path("turns"), text, durationMs));
        } catch (Exception ex) {
            throw invalid();
        }
    }

    public String encode() {
        try {
            return JSON.writeValueAsString(this);
        } catch (Exception ex) {
            throw invalid();
        }
    }

    private static boolean splitsSurrogate(String text, int offset) {
        return offset > 0 && offset < text.length()
                && Character.isHighSurrogate(text.charAt(offset - 1))
                && Character.isLowSurrogate(text.charAt(offset));
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("speaker attribution violates the bounded anonymous contract");
    }
}
