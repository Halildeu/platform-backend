package com.example.meeting.service;

import com.example.meeting.dto.v1.internal.MeetingAnalysisResultIngestRequest;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.stereotype.Component;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Computes the canonical, deterministic idempotency payload hash for an
 * analysis-result ingestion — Faz 24 (platform-ai#244 BE-1c).
 *
 * <p><b>Contract.</b> {@code payload_hash = lowercaseHex(SHA-256(canonicalUtf8Json))}
 * where {@code canonicalJson} is a fixed, field-named serialization of the
 * <em>normalised semantic request</em> (NOT the raw HTTP bytes). "Same
 * Idempotency-Key + same payload hash" is a retry (200 replay); "same key +
 * different hash" is a conflict (409). See {@code V3__meeting_analysis_runs.sql}.
 *
 * <p><b>Why it is deterministic.</b> The hash is taken over a value object, so
 * transport JSON key order / whitespace / escaping never affect it. The mapper
 * is frozen and independent of the application-global Jackson config:
 * <ul>
 *   <li>{@code ORDER_MAP_ENTRIES_BY_KEYS} + {@code SORT_PROPERTIES_ALPHABETICALLY}
 *       — any map (there are none today) and every nested record field serialise
 *       in a stable order;</li>
 *   <li>{@code JavaTimeModule} + {@code WRITE_DATES_AS_TIMESTAMPS=false} — the
 *       nested action {@code due} {@link java.time.Instant} is canonical ISO-8601;</li>
 *   <li>{@code JsonInclude.ALWAYS} — nulls are serialised, so an omitted field
 *       and an explicit JSON {@code null} hash identically;</li>
 *   <li>no default typing;</li>
 *   <li>Jackson writes UTF-8 bytes.</li>
 * </ul>
 *
 * <p><b>Normalise-then-hash.</b> The hash is computed from the accepted, about-to-be-
 * persisted values: the path {@code meetingId} + the meeting-derived {@code tenantId}
 * (never the body), {@code UUID}/{@code Instant} as canonical strings, {@code null}
 * int → 0, {@code null} bool → false, and {@code null} lists already normalised to
 * empty by the request's compact constructor. It deliberately does NOT apply Unicode
 * NFC/NFD folding — the hash is the identity of the canonical UTF-8 JSON of the
 * normalised request, not of visually-equivalent Unicode forms.
 *
 * <p>Lists (decisions, actions, citations) keep their order: position is meaningful
 * (it becomes the child {@code ordinal}), so reordering them is a different payload.
 */
@Component
public class MeetingAnalysisPayloadHasher {

    private final ObjectMapper canonicalMapper = JsonMapper.builder()
            .addModule(new JavaTimeModule())
            .configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false)
            .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true)
            .configure(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY, true)
            .serializationInclusion(JsonInclude.Include.ALWAYS)
            .build();

    /**
     * @param meetingId     the canonical (path) meeting id
     * @param tenantId      the meeting-derived tenant (authoritative scope)
     * @param analysisRunId the caller's Idempotency-Key
     * @param request       the validated, null-normalised ingestion payload
     * @return 64-char lowercase SHA-256 hex
     */
    public String hash(UUID meetingId,
                       UUID tenantId,
                       UUID analysisRunId,
                       MeetingAnalysisResultIngestRequest request) {
        Map<String, Object> canonical = new LinkedHashMap<>();
        canonical.put("analysisRunId", analysisRunId.toString());
        canonical.put("meetingId", meetingId.toString());
        canonical.put("tenantId", tenantId.toString());
        canonical.put("transcriptSessionId", request.transcriptSessionId());
        canonical.put("transcriptSha256", request.transcriptSha256());
        canonical.put("finalizationVersion", request.finalizationVersion());
        canonical.put("finalizedAt", request.finalizedAt().toString());
        canonical.put("analysisSpecVersion", request.analysisSpecVersion());
        canonical.put("analyzerContractVersion", request.analyzerContractVersion());
        canonical.put("model", request.model());
        canonical.put("backend", request.backend());
        canonical.put("promptVersion", request.promptVersion());
        canonical.put("summary", request.summary());
        canonical.put("summaryGroundingStatus", request.summaryGroundingStatus());
        canonical.put("ungroundedCount", request.ungroundedCount() == null ? 0 : request.ungroundedCount());
        canonical.put("redacted", request.redacted() != null && request.redacted());
        canonical.put("redactionCount", request.redactionCount() == null ? 0 : request.redactionCount());
        canonical.put("generatedAt", request.generatedAt().toString());
        canonical.put("supersedesAnalysisRunId",
                request.supersedesAnalysisRunId() == null ? null : request.supersedesAnalysisRunId().toString());
        canonical.put("summaryCitations", request.summaryCitations());
        canonical.put("citations", request.citations());
        canonical.put("rejectedClaims", request.rejectedClaims());
        canonical.put("decisions", request.decisions());
        canonical.put("actions", request.actions());

        try {
            byte[] json = canonicalMapper.writeValueAsBytes(canonical);
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(json);
            return HexFormat.of().formatHex(digest);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialise analysis payload for hashing.", e);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable.", e);
        }
    }
}
