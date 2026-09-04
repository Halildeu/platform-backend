package com.example.meeting.service;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Consent-bound meeting speech-context terms (Faz 24, platform-backend#1024).
 *
 * <p>Meeting-scoped language context handed to STT as a vocabulary hint (proper
 * nouns, product names). It is NOT an authorization relation and never derives
 * from ReBAC subject ids. Fail-closed at the API boundary:
 * <ul>
 *   <li>opt-in: {@code null} / empty ⇒ absent (stored as SQL NULL);</li>
 *   <li>Unicode NFKC normalisation, whitespace collapse, exact-duplicate removal;</li>
 *   <li>at most {@value #MAX_TERMS} terms, {@value #MAX_TERM_LENGTH} characters
 *       each, {@value #MAX_TOTAL_LENGTH} characters in total (after normalisation);</li>
 *   <li>control characters and punctuation outside the allowed set are rejected
 *       (letters, digits, marks, space, apostrophes, hyphen, period, ampersand,
 *       slash, parentheses, plus, comma, colon, underscore, at, hash).</li>
 * </ul>
 * Rejections surface as HTTP 400 with a stable {@code SPEECH_CONTEXT_TERMS_*}
 * reason code; the offending value is never echoed back or logged.
 */
public final class SpeechContextTerms {

    public static final int MAX_TERMS = 32;
    public static final int MAX_TERM_LENGTH = 64;
    public static final int MAX_TOTAL_LENGTH = 512;

    private static final Pattern WHITESPACE = Pattern.compile("\\s+");
    private static final Pattern ALLOWED = Pattern.compile(
            "^[\\p{L}\\p{N}\\p{M} '\u2019\\-.&/()+,:_@#]+$");
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final TypeReference<List<String>> LIST = new TypeReference<>() { };

    private SpeechContextTerms() {
    }

    /** Normalise + validate; returns an immutable list or {@code null} when absent. */
    public static List<String> normalize(final List<String> raw) {
        if (raw == null || raw.isEmpty()) {
            return null;
        }
        final Set<String> out = new LinkedHashSet<>();
        int total = 0;
        for (final String value : raw) {
            if (value == null) {
                throw reject("SPEECH_CONTEXT_TERMS_NULL_TERM");
            }
            for (int i = 0; i < value.length(); i++) {
                final char c = value.charAt(i);
                if (Character.isISOControl(c)) {
                    throw reject("SPEECH_CONTEXT_TERMS_CONTROL_CHARACTER");
                }
            }
            final String term = WHITESPACE.matcher(
                    Normalizer.normalize(value, Normalizer.Form.NFKC).trim()).replaceAll(" ");
            if (term.isEmpty()) {
                continue;
            }
            if (term.length() > MAX_TERM_LENGTH) {
                throw reject("SPEECH_CONTEXT_TERMS_TERM_TOO_LONG");
            }
            if (!ALLOWED.matcher(term).matches()) {
                throw reject("SPEECH_CONTEXT_TERMS_UNSUPPORTED_CHARACTER");
            }
            if (out.add(term)) {
                total += term.length();
            }
        }
        if (out.isEmpty()) {
            return null;
        }
        if (out.size() > MAX_TERMS) {
            throw reject("SPEECH_CONTEXT_TERMS_TOO_MANY");
        }
        if (total > MAX_TOTAL_LENGTH) {
            throw reject("SPEECH_CONTEXT_TERMS_TOTAL_TOO_LONG");
        }
        return List.copyOf(out);
    }

    /** JSON array text for the JSONB column; {@code null} when absent. */
    public static String toJson(final List<String> normalized) {
        if (normalized == null || normalized.isEmpty()) {
            return null;
        }
        try {
            return JSON.writeValueAsString(normalized);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("speech context terms could not be serialised", e);
        }
    }

    /** Parse the JSONB column back; {@code null} column ⇒ {@code null}. */
    public static List<String> fromJson(final String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            final List<String> list = JSON.readValue(json, LIST);
            return list.isEmpty() ? null : List.copyOf(list);
        } catch (java.io.IOException e) {
            throw new IllegalStateException("stored speech context terms are not a JSON array", e);
        }
    }

    private static ResponseStatusException reject(final String code) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, code);
    }
}
