package com.example.audiogateway.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Start a recording session — POST /api/v1/audio-gateway/sessions client→Gateway request.
 *
 * <p>Audit, tenant, user fields are NEVER trusted from client; the Gateway derives them
 * from the JWT after validation. Idempotency key is now a HEADER (Codex {@code 019e8c26}
 * iter-2 AGREE) — body field removed to prevent dual-source ambiguity.
 *
 * <p>ADR-0031 §D2 — path canonical {@code /api/v1/audio-gateway}, {@code /api/meeting-audio}
 * removed (pre-prod scope correction, no backward-compat alias).
 */
public record StartSessionRequest(

        @NotBlank
        @Size(max = 64)
        @Pattern(regexp = "^"
                + "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-"
                + "[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"
                + "$",
                message = "meetingId must be a meeting-service UUID")
        String meetingId,

        @NotBlank
        @Size(max = 64)
        @Pattern(regexp = "^[A-Za-z0-9._-]{1,64}$",
                message = "deviceId opaque token, 1-64 chars, [A-Za-z0-9._-]")
        String deviceId,

        @NotBlank
        @Size(min = 2, max = 10)
        @Pattern(regexp = "^[a-z]{2}(-[A-Z]{2})?$",
                message = "language ISO 639-1 (e.g. tr, en, de) optionally with region")
        String language,

        @NotNull
        AudioFormat audioFormat,

        @NotNull
        @org.springframework.format.annotation.NumberFormat
        Integer sampleRateHz,

        @NotNull
        Integer channels,

        @Size(max = 32)
        @Pattern(regexp = "^(internal|speechmatics)$",
                message = "sttProvider must be internal or speechmatics")
        String sttProvider,

        @Size(max = 16)
        @Pattern(regexp = "^(balanced|realtime)$",
                message = "transcriptionMode must be balanced or realtime")
        String transcriptionMode,

        /**
         * Faz 24 (gitops#3435 dilim-3) — kullanıcı sözlüğü (özel adlar). Speechmatics
         * bunları yalnız StartRecognition içinde kabul eder ve o mesaj ilk ses
         * çerçevesinden önce gider; bu yüzden sözlük akış-içi kontrol çerçevesiyle
         * değil, oturum açılışında gelir. Sınırlar masaüstündeki bütçeyle hizalı.
         * Kişi adı içerebildiği için istek GÖVDESİNDE taşınır, asla query string'de.
         */
        @Size(max = 32, message = "contextTerms must not exceed 32 entries")
        java.util.List<String> contextTerms
) {

    /** Backward-compatible constructor for clients that rely on the server default. */
    public StartSessionRequest(
            final String meetingId,
            final String deviceId,
            final String language,
            final AudioFormat audioFormat,
            final Integer sampleRateHz,
            final Integer channels) {
        this(meetingId, deviceId, language, audioFormat, sampleRateHz, channels, null, null, null);
    }

    /** Backward-compatible constructor for callers that select only the provider. */
    public StartSessionRequest(
            final String meetingId,
            final String deviceId,
            final String language,
            final AudioFormat audioFormat,
            final Integer sampleRateHz,
            final Integer channels,
            final String sttProvider) {
        this(meetingId, deviceId, language, audioFormat, sampleRateHz, channels, sttProvider, null,
                null);
    }

    /** Backward-compatible constructor for callers that predate the dictionary. */
    public StartSessionRequest(
            final String meetingId,
            final String deviceId,
            final String language,
            final AudioFormat audioFormat,
            final Integer sampleRateHz,
            final Integer channels,
            final String sttProvider,
            final String transcriptionMode) {
        this(meetingId, deviceId, language, audioFormat, sampleRateHz, channels, sttProvider,
                transcriptionMode, null);
    }

    /** Allowed sample rates (Hz) — Codex {@code 019e879c} fixed enum. */
    public static final java.util.Set<Integer> ALLOWED_SAMPLE_RATES = java.util.Set.of(16_000, 48_000);
}
