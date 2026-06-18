package com.example.endpointadmin.domainops;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

public final class DomainOpsResultSubmitPolicy {

    private static final int MAX_EVIDENCE_TYPES = 20;
    private static final Pattern SHA256 = Pattern.compile("^[a-fA-F0-9]{64}$");
    private static final Pattern SAFE_CODE = Pattern.compile("^[A-Za-z0-9._:-]{1,128}$");
    private static final Pattern SAFE_EVIDENCE_TYPE = Pattern.compile("^[A-Z0-9_:-]{1,64}$");
    private static final Set<String> ALLOWED_FIELDS = Set.of(
            "durationMs",
            "evidenceBundleSha256",
            "evidenceCount",
            "evidenceTypes",
            "executorIdHash",
            "exitCode",
            "failureCode",
            "failureReasonCode",
            "packageSha256",
            "redactionProfile",
            "resultSha256",
            "summaryCode");

    private DomainOpsResultSubmitPolicy() {
    }

    public static Map<String, Object> normalize(Map<String, Object> rawResult) {
        if (rawResult == null || rawResult.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> normalized = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : rawResult.entrySet()) {
            String field = entry.getKey();
            if (!ALLOWED_FIELDS.contains(field)) {
                throw invalid();
            }
            Object value = entry.getValue();
            switch (field) {
                case "durationMs" -> normalized.put(field, boundedLong(value, 0, 900_000));
                case "evidenceBundleSha256", "executorIdHash", "packageSha256", "resultSha256" ->
                        normalized.put(field, sha256(value));
                case "evidenceCount" -> normalized.put(field, boundedLong(value, 0, 100));
                case "evidenceTypes" -> normalized.put(field, evidenceTypes(value));
                case "exitCode" -> normalized.put(field, boundedLong(value, -1, 999_999));
                case "failureCode", "failureReasonCode", "redactionProfile", "summaryCode" ->
                        normalized.put(field, safeCode(value));
                default -> throw invalid();
            }
        }
        return Map.copyOf(normalized);
    }

    public static String normalizeSha256(String raw, String field) {
        String value = raw == null ? "" : raw.trim();
        if (!SHA256.matcher(value).matches()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    field + " must be a SHA-256 hex digest.");
        }
        return value.toLowerCase(Locale.ROOT);
    }

    private static String sha256(Object value) {
        if (!(value instanceof String raw)) {
            throw invalid();
        }
        return normalizeSha256(raw, "Domain ops result digest");
    }

    private static String safeCode(Object value) {
        if (!(value instanceof String raw)) {
            throw invalid();
        }
        String trimmed = raw.trim();
        if (!SAFE_CODE.matcher(trimmed).matches()) {
            throw invalid();
        }
        return trimmed;
    }

    private static long boundedLong(Object value, long min, long max) {
        long number;
        if (value instanceof Number numeric) {
            number = numeric.longValue();
        } else {
            throw invalid();
        }
        if (number < min || number > max) {
            throw invalid();
        }
        return number;
    }

    private static List<String> evidenceTypes(Object value) {
        if (!(value instanceof List<?> rawTypes)
                || rawTypes.isEmpty()
                || rawTypes.size() > MAX_EVIDENCE_TYPES) {
            throw invalid();
        }
        List<String> normalized = new ArrayList<>();
        for (Object item : rawTypes) {
            if (!(item instanceof String raw)) {
                throw invalid();
            }
            String type = raw.trim().toUpperCase(Locale.ROOT);
            if (!SAFE_EVIDENCE_TYPE.matcher(type).matches()) {
                throw invalid();
            }
            normalized.add(type);
        }
        return List.copyOf(normalized);
    }

    private static ResponseStatusException invalid() {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Domain ops result contains unsupported or unsafe evidence fields.");
    }
}
