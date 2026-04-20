package com.example.permission.service;

import com.example.permission.dto.AuditCompareDiff;
import com.example.permission.dto.AuditCompareResponse;
import com.example.permission.dto.AuditEventPageResponse;
import com.example.permission.dto.AuditEventResponse;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Shadow-compare service — calls BOTH permission-service (self) and user-service
 * (over HTTP), produces a drift report. Used by the diagnostic endpoint
 * /api/audit/events/compare (AUDIT.manager gated) to collect parity evidence
 * before the operator flips AUDIT_BACKEND_URI (Faz 1) to permission-service.
 *
 * 2026-04-20 QLTY-PROACTIVE-06 Faz 2.
 */
@Service
public class AuditCompareService {

    private static final Logger log = LoggerFactory.getLogger(AuditCompareService.class);

    // Fields we compare between permission and user-service responses.
    // Note: user-service synthesizes userEmail = "user:" + targetUserId and
    // correlationId = "audit-" + id, while permission-service returns actual
    // values. Those deltas are EXPECTED to surface as fieldDiffs.
    private static final List<String> COMPARED_FIELDS = List.of(
            "userEmail",
            "service",
            "level",
            "action",
            "correlationId"
    );

    private static final int MAX_FIELD_DIFF_SAMPLES = 50;

    private final AuditEventService auditEventService;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final String userServiceUrl;
    private final Duration requestTimeout;

    public AuditCompareService(
            AuditEventService auditEventService,
            ObjectMapper objectMapper,
            @Value("${user.service.base-url:http://user-service:8089}") String userServiceUrl,
            @Value("${audit.compare.http-timeout-ms:5000}") long requestTimeoutMs
    ) {
        this.auditEventService = auditEventService;
        this.objectMapper = objectMapper;
        this.userServiceUrl = stripTrailingSlash(userServiceUrl);
        this.requestTimeout = Duration.ofMillis(Math.max(1_000, requestTimeoutMs));
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(2))
                .build();
    }

    public AuditCompareResponse compare(int page, int pageSize) {
        return compare(page, pageSize, null);
    }

    public AuditCompareResponse compare(int page, int pageSize, String authHeader) {
        int safePage = Math.max(1, page);
        int safeSize = Math.min(Math.max(pageSize, 1), 500);

        AuditEventPageResponse permissionResponse = fetchPermission(safePage, safeSize);
        Map<String, Object> userServiceResponse;
        List<String> userServiceErrors = new ArrayList<>();
        try {
            // user-service is 0-based, permission-service is 1-based — adjust
            userServiceResponse = fetchUserService(safePage - 1, safeSize, authHeader);
        } catch (Exception ex) {
            log.warn("[audit-compare] user-service fetch failed: {}", ex.toString());
            userServiceErrors.add("user-service fetch failed: " + ex.getClass().getSimpleName() + ": " + ex.getMessage());
            userServiceResponse = Collections.emptyMap();
        }

        AuditCompareDiff diff = computeDiff(permissionResponse, userServiceResponse);
        return new AuditCompareResponse(
                safePage,
                safeSize,
                permissionResponse,
                userServiceResponse,
                userServiceErrors,
                diff
        );
    }

    // -----------------------------------------------------------------------
    // Helpers (protected for testability)
    // -----------------------------------------------------------------------

    AuditEventPageResponse fetchPermission(int page, int pageSize) {
        return auditEventService.listEvents(Math.max(0, page - 1), pageSize, null, Map.of());
    }

    Map<String, Object> fetchUserService(int page, int pageSize) throws Exception {
        return fetchUserService(page, pageSize, null);
    }

    Map<String, Object> fetchUserService(int page, int pageSize, String authHeader) throws Exception {
        URI uri = URI.create(String.format("%s/api/audit/events?page=%d&size=%d",
                userServiceUrl, page, pageSize));
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
                .timeout(requestTimeout)
                .header("Accept", "application/json");
        // Forward caller's Authorization so user-service SecurityConfig accepts
        // the request (no @RequireModule guard, but authenticated() required).
        if (authHeader != null && !authHeader.isBlank()) {
            builder.header("Authorization", authHeader);
        }
        HttpRequest request = builder.GET().build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() / 100 != 2) {
            throw new RuntimeException("user-service returned HTTP " + response.statusCode());
        }
        return objectMapper.readValue(response.body(), new TypeReference<Map<String, Object>>() {});
    }

    AuditCompareDiff computeDiff(AuditEventPageResponse permission, Map<String, Object> userService) {
        List<AuditEventResponse> permissionEvents = permission.events() == null
                ? List.of()
                : permission.events();
        long permissionTotal = permission.total();

        long userServiceTotal = parseLong(userService.get("total"), -1);
        List<Map<String, Object>> userServiceEvents = extractUserServiceEvents(userService);

        Set<String> permissionIds = new HashSet<>();
        Map<String, AuditEventResponse> permissionById = new HashMap<>();
        for (AuditEventResponse event : permissionEvents) {
            if (event == null || event.id() == null) continue;
            permissionIds.add(event.id());
            permissionById.put(event.id(), event);
        }

        Set<String> userServiceIds = new HashSet<>();
        Map<String, Map<String, Object>> userServiceById = new HashMap<>();
        for (Map<String, Object> event : userServiceEvents) {
            if (event == null) continue;
            Object idObj = event.get("id");
            String id = idObj == null ? null : idObj.toString();
            if (id == null || id.isBlank()) continue;
            userServiceIds.add(id);
            userServiceById.put(id, event);
        }

        List<String> permissionOnly = new ArrayList<>(permissionIds);
        permissionOnly.removeAll(userServiceIds);
        Collections.sort(permissionOnly);

        List<String> userServiceOnly = new ArrayList<>(userServiceIds);
        userServiceOnly.removeAll(permissionIds);
        Collections.sort(userServiceOnly);

        List<String> common = new ArrayList<>(permissionIds);
        common.retainAll(userServiceIds);
        Collections.sort(common);

        List<AuditCompareDiff.FieldDiff> fieldDiffs = new ArrayList<>();
        for (String id : common) {
            if (fieldDiffs.size() >= MAX_FIELD_DIFF_SAMPLES) break;
            AuditEventResponse pEvent = permissionById.get(id);
            Map<String, Object> uEvent = userServiceById.get(id);
            Map<String, Object> pAsMap = toMap(pEvent);
            for (String field : COMPARED_FIELDS) {
                Object pVal = pAsMap.get(field);
                Object uVal = uEvent.get(field);
                if (!Objects.equals(normalize(pVal), normalize(uVal))) {
                    fieldDiffs.add(new AuditCompareDiff.FieldDiff(id, field, pVal, uVal));
                    if (fieldDiffs.size() >= MAX_FIELD_DIFF_SAMPLES) break;
                }
            }
        }

        String verdict = deriveVerdict(permissionTotal, userServiceTotal, permissionOnly, userServiceOnly, fieldDiffs);

        return new AuditCompareDiff(
                permissionTotal - userServiceTotal,
                permissionTotal,
                userServiceTotal,
                permissionOnly,
                userServiceOnly,
                common,
                fieldDiffs,
                verdict
        );
    }

    private String deriveVerdict(long permTotal, long userTotal,
                                 List<String> permOnly, List<String> userOnly,
                                 List<AuditCompareDiff.FieldDiff> fieldDiffs) {
        if (userTotal < 0) return "user-service-unreachable";
        if (permTotal == userTotal && permOnly.isEmpty() && userOnly.isEmpty() && fieldDiffs.isEmpty()) {
            return "clean";
        }
        if (Math.abs(permTotal - userTotal) == 0 && fieldDiffs.isEmpty()) {
            return "id-drift";
        }
        if (fieldDiffs.isEmpty()) {
            return "count-drift";
        }
        return "field-drift";
    }

    Map<String, Object> toMap(AuditEventResponse event) {
        if (event == null) return Map.of();
        Map<String, Object> m = new HashMap<>();
        m.put("id", event.id());
        m.put("timestamp", event.timestamp() == null ? null : event.timestamp().toString());
        m.put("userEmail", event.userEmail());
        m.put("service", event.service());
        m.put("level", event.level());
        m.put("action", event.action());
        m.put("details", event.details());
        m.put("correlationId", event.correlationId());
        return m;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> extractUserServiceEvents(Map<String, Object> response) {
        Object events = response.get("events");
        if (!(events instanceof List)) return List.of();
        List<?> raw = (List<?>) events;
        List<Map<String, Object>> out = new ArrayList<>(raw.size());
        for (Object item : raw) {
            if (item instanceof Map<?, ?>) {
                out.add((Map<String, Object>) item);
            }
        }
        return out;
    }

    private static long parseLong(Object value, long defaultValue) {
        if (value == null) return defaultValue;
        if (value instanceof Number n) return n.longValue();
        try {
            return Long.parseLong(value.toString());
        } catch (NumberFormatException ex) {
            return defaultValue;
        }
    }

    private static String stripTrailingSlash(String url) {
        if (url == null) return "";
        return url.replaceAll("/+$", "");
    }

    private static Object normalize(Object value) {
        if (value == null) return null;
        if (value instanceof String s) {
            return s.isBlank() ? null : s.trim();
        }
        return value;
    }
}
