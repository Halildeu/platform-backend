package com.example.permission.service;

import com.example.permission.dto.AuditEventPageResponse;
import com.example.permission.dto.AuditEventResponse;
import com.example.permission.model.PermissionAuditEvent;
import com.example.permission.model.UserAuditEventMirror;
import com.example.permission.repository.PermissionAuditEventRepository;
import com.example.permission.repository.UserAuditEventMirrorRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.UncheckedIOException;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class AuditEventService {

    private static final Logger log = LoggerFactory.getLogger(AuditEventService.class);
    private static final int MAX_PAGE_SIZE = 500;
    private static final int DEFAULT_EXPORT_LIMIT = 2000;
    private static final int MAX_EXPORT_LIMIT = 10000;

    private final PermissionAuditEventRepository repository;
    private final UserAuditEventMirrorRepository userAuditEventMirrorRepository;
    private final ObjectMapper objectMapper;
    private final AuditEventStream auditEventStream;
    private final boolean liveStreamEnabled;

    public AuditEventService(PermissionAuditEventRepository repository,
                             UserAuditEventMirrorRepository userAuditEventMirrorRepository,
                             ObjectMapper objectMapper,
                             AuditEventStream auditEventStream,
                             @Value("${audit.live-stream.enabled:true}") boolean liveStreamEnabled) {
        this.repository = repository;
        this.userAuditEventMirrorRepository = userAuditEventMirrorRepository;
        this.objectMapper = objectMapper;
        this.auditEventStream = auditEventStream;
        this.liveStreamEnabled = liveStreamEnabled;
    }

    public AuditEventPageResponse listEvents(int page,
                                             int size,
                                             String sort,
                                             Map<String, String> filters) {
        int safePage = Math.max(page, 0);
        int safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        List<AuditEventResponse> events = filterAndSortEvents(filters, sort);
        int startIndex = Math.min(safePage * safeSize, events.size());
        int endIndex = Math.min(startIndex + safeSize, events.size());
        return new AuditEventPageResponse(events.subList(startIndex, endIndex), safePage, events.size());
    }

    public List<AuditEventResponse> exportEvents(String sort,
                                                 Map<String, String> filters,
                                                 Integer limit) {
        int pageSize = limit != null
                ? Math.min(Math.max(limit, 1), MAX_EXPORT_LIMIT)
                : DEFAULT_EXPORT_LIMIT;
        List<AuditEventResponse> events = filterAndSortEvents(filters, sort);
        return events.subList(0, Math.min(pageSize, events.size()));
    }

    public AuditEventPageResponse findByIdPage(String idString) {
        if (idString != null && idString.startsWith("user-")) {
            Long userAuditId = parseAuditId(idString.substring("user-".length()));
            return userAuditEventMirrorRepository.findById(userAuditId)
                    .map(this::mapUserAuditToResponse)
                    .map(resp -> new AuditEventPageResponse(java.util.List.of(resp), 0, 1))
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Audit event not found"));
        }
        try {
            Long id = parseAuditId(idString);
            return repository.findById(id)
                    .map(this::mapToResponse)
                    .map(resp -> new AuditEventPageResponse(java.util.List.of(resp), 0, 1))
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Audit event not found"));
        } catch (NumberFormatException nfe) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid id format");
        }
    }

    public byte[] buildExportPayload(List<AuditEventResponse> events, String format) {
        if ("csv".equalsIgnoreCase(format)) {
            return buildCsvPayload(events).getBytes(java.nio.charset.StandardCharsets.UTF_8);
        }
        if ("json".equalsIgnoreCase(format)) {
            try {
                return objectMapper.writeValueAsBytes(events);
            } catch (JsonProcessingException e) {
                throw new UncheckedIOException(e);
            }
        }
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unsupported export format: " + format);
    }

    public SseEmitter openLiveStream() {
        if (!liveStreamEnabled) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Audit live stream disabled");
        }
        return auditEventStream.registerEmitter();
    }

    public PermissionAuditEvent recordEvent(PermissionAuditEvent event) {
        PermissionAuditEvent saved = repository.save(event);
        dispatchLiveEvent(saved);
        return saved;
    }

    public PermissionAuditEvent buildEvent(String eventType,
                                           Long performedBy,
                                           String details,
                                           Long targetUserId,
                                           String level,
                                           String action,
                                           Map<String, Object> metadata,
                                           Object beforeState,
                                           Object afterState) {
        PermissionAuditEvent event = new PermissionAuditEvent();
        event.setEventType(eventType);
        event.setPerformedBy(performedBy);
        event.setDetails(details);
        event.setUserEmail(targetUserId == null ? null : ("user:" + targetUserId));
        event.setService("permission-service");
        event.setLevel(level);
        event.setAction(action);
        event.setCorrelationId(java.util.UUID.randomUUID().toString());
        event.setMetadata(writeJsonSafe(metadata));
        event.setBeforeState(writeJsonSafe(beforeState));
        event.setAfterState(writeJsonSafe(afterState));
        event.setOccurredAt(java.time.Instant.now());
        return event;
    }

    private void dispatchLiveEvent(PermissionAuditEvent event) {
        if (!liveStreamEnabled) {
            return;
        }
        AuditEventResponse response = mapToResponse(event);
        auditEventStream.publish(response);
    }

    private String buildCsvPayload(List<AuditEventResponse> events) {
        String header = String.join(",",
                "id", "timestamp", "userEmail", "service", "level", "action",
                "details", "correlationId", "metadata", "beforeState", "afterState");
        return events.stream()
                .map(this::toCsvRow)
                .collect(Collectors.joining("\n", header + "\n", ""));
    }

    private String toCsvRow(AuditEventResponse event) {
        return String.join(",",
                escapeCsv(event.id()),
                escapeCsv(event.timestamp() != null ? event.timestamp().toString() : ""),
                escapeCsv(event.userEmail()),
                escapeCsv(event.service()),
                escapeCsv(event.level()),
                escapeCsv(event.action()),
                escapeCsv(event.details()),
                escapeCsv(event.correlationId()),
                escapeCsv(writeJsonSafe(event.metadata())),
                escapeCsv(writeJsonSafe(event.before())),
                escapeCsv(writeJsonSafe(event.after()))
        );
    }

    private String escapeCsv(String value) {
        if (value == null) {
            return "\"\"";
        }
        String escaped = value.replace("\"", "\"\"");
        return "\"%s\"".formatted(escaped);
    }

    private String writeJsonSafe(Object value) {
        if (value == null) {
            return "";
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            log.debug("Failed to serialise audit payload", e);
            return value.toString();
        }
    }

    private List<AuditEventResponse> filterAndSortEvents(Map<String, String> filters, String sort) {
        return collectMergedEvents().stream()
                .filter(event -> matchesFilters(event, filters))
                .sorted(resolveResponseComparator(sort))
                .toList();
    }

    private List<AuditEventResponse> collectMergedEvents() {
        List<AuditEventResponse> events = new ArrayList<>();
        repository.findAll().stream()
                .map(this::mapToResponse)
                .forEach(events::add);
        userAuditEventMirrorRepository.findAll().stream()
                .map(this::mapUserAuditToResponse)
                .forEach(events::add);
        return events;
    }

    private Comparator<AuditEventResponse> resolveResponseComparator(String sort) {
        Comparator<AuditEventResponse> comparator;
        if (sort == null || sort.isBlank()) {
            comparator = Comparator.comparing(AuditEventResponse::timestamp, Comparator.nullsLast(Comparator.naturalOrder()));
            return comparator.reversed();
        }
        String[] parts = sort.split(",");
        String field = parts[0];
        boolean ascending = parts.length > 1 && "asc".equalsIgnoreCase(parts[1]);
        comparator = switch (field) {
            case "userEmail" -> Comparator.comparing(AuditEventResponse::userEmail, Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER));
            case "service" -> Comparator.comparing(AuditEventResponse::service, Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER));
            case "level" -> Comparator.comparing(AuditEventResponse::level, Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER));
            case "action" -> Comparator.comparing(AuditEventResponse::action, Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER));
            case "correlationId" -> Comparator.comparing(AuditEventResponse::correlationId, Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER));
            default -> Comparator.comparing(AuditEventResponse::timestamp, Comparator.nullsLast(Comparator.naturalOrder()));
        };
        return ascending ? comparator : comparator.reversed();
    }

    private boolean matchesFilters(AuditEventResponse event, Map<String, String> filters) {
        if (filters == null || filters.isEmpty()) {
            return true;
        }
        if (isNotBlank(filters.get("userEmail")) && !containsIgnoreCase(event.userEmail(), filters.get("userEmail"))) {
            return false;
        }
        if (isNotBlank(filters.get("service")) && !containsIgnoreCase(event.service(), filters.get("service"))) {
            return false;
        }
        if (isNotBlank(filters.get("level"))
                && (event.level() == null || !event.level().equalsIgnoreCase(filters.get("level")))) {
            return false;
        }
        if (isNotBlank(filters.get("action")) && !containsIgnoreCase(event.action(), filters.get("action"))) {
            return false;
        }
        if (isNotBlank(filters.get("correlationId"))
                && (event.correlationId() == null || !event.correlationId().equals(filters.get("correlationId")))) {
            return false;
        }
        Instant dateFrom = parseInstant(filters.get("dateFrom"));
        if (dateFrom != null && (event.timestamp() == null || event.timestamp().isBefore(dateFrom))) {
            return false;
        }
        Instant dateTo = parseInstant(filters.get("dateTo"));
        if (dateTo != null && (event.timestamp() == null || event.timestamp().isAfter(dateTo))) {
            return false;
        }
        String search = filters.get("search");
        if (isNotBlank(search)) {
            return containsIgnoreCase(event.details(), search)
                    || containsIgnoreCase(event.action(), search)
                    || containsIgnoreCase(event.userEmail(), search)
                    || containsIgnoreCase(event.service(), search)
                    || containsIgnoreCase(event.correlationId(), search);
        }
        return true;
    }

    private Instant parseInstant(String value) {
        if (!isNotBlank(value)) {
            return null;
        }
        try {
            return Instant.parse(value);
        } catch (Exception error) {
            log.debug("Failed to parse audit date filter: {}", value, error);
            return null;
        }
    }

    private boolean containsIgnoreCase(String source, String needle) {
        if (!isNotBlank(source) || !isNotBlank(needle)) {
            return false;
        }
        return source.toLowerCase(Locale.ROOT).contains(needle.toLowerCase(Locale.ROOT));
    }

    private Long parseAuditId(String idString) {
        return Long.valueOf(idString);
    }

    private boolean isNotBlank(String value) {
        return value != null && !value.isBlank();
    }

    private AuditEventResponse mapToResponse(PermissionAuditEvent event) {
        Map<String, Object> metadata = redactPayload(parseJson(event.getMetadata()));
        Map<String, Object> before = redactPayload(parseJson(event.getBeforeState()));
        Map<String, Object> after = redactPayload(parseJson(event.getAfterState()));

        return new AuditEventResponse(
                event.getId() != null ? event.getId().toString() : null,
                event.getOccurredAt(),
                maskUserIdentifier(event.getUserEmail()),
                event.getService(),
                event.getLevel(),
                event.getAction(),
                event.getDetails(),
                event.getCorrelationId(),
                metadata,
                before,
                after
        );
    }

    private AuditEventResponse mapUserAuditToResponse(UserAuditEventMirror event) {
        Instant occurredAt = event.getOccurredAt() == null
                ? Instant.now()
                : event.getOccurredAt().atZone(ZoneId.systemDefault()).toInstant();
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("eventType", event.getEventType());
        metadata.put("performedBy", event.getPerformedBy());
        metadata.put("targetUserId", event.getTargetUserId());
        return new AuditEventResponse(
                "user-" + event.getId(),
                occurredAt,
                null,
                "user-service",
                resolveUserAuditLevel(event.getEventType()),
                resolveUserAuditAction(event.getEventType()),
                event.getDetails(),
                "user-audit-" + event.getId(),
                redactPayload(metadata),
                Map.of(),
                Map.of()
        );
    }

    private String resolveUserAuditLevel(String eventType) {
        if (eventType == null || eventType.isBlank()) {
            return "INFO";
        }
        return eventType.endsWith("FAILED") ? "ERROR" : "INFO";
    }

    private String resolveUserAuditAction(String eventType) {
        if (eventType == null || eventType.isBlank()) {
            return "USER_EVENT";
        }
        return eventType.toUpperCase(Locale.ROOT);
    }

    private Map<String, Object> parseJson(String value) {
        if (value == null || value.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(value, new TypeReference<>() { });
        } catch (Exception e) {
            log.debug("Failed to parse audit JSON payload", e);
            return Map.of("raw", value);
        }
    }

    private Map<String, Object> redactPayload(Map<String, Object> payload) {
        if (payload == null || payload.isEmpty()) {
            return payload == null ? Map.of() : payload;
        }
        Map<String, Object> sanitized = new LinkedHashMap<>();
        payload.forEach((key, value) -> sanitized.put(key, redactValue(key, value)));
        return sanitized;
    }

    private Object redactValue(String key, Object value) {
        if (value instanceof Map<?, ?> mapValue) {
            Map<String, Object> nested = new LinkedHashMap<>();
            mapValue.forEach((nestedKey, nestedValue) ->
                    nested.put(String.valueOf(nestedKey), redactValue(String.valueOf(nestedKey), nestedValue)));
            return nested;
        }
        if (value instanceof Collection<?> collection) {
            List<Object> sanitized = new ArrayList<>(collection.size());
            for (Object element : collection) {
                sanitized.add(redactValue(key, element));
            }
            return sanitized;
        }
        if (value != null && value.getClass().isArray()) {
            int length = java.lang.reflect.Array.getLength(value);
            List<Object> sanitized = new ArrayList<>(length);
            for (int i = 0; i < length; i++) {
                sanitized.add(redactValue(key, java.lang.reflect.Array.get(value, i)));
            }
            return sanitized;
        }
        if (value instanceof String stringValue && shouldMask(key, stringValue)) {
            return maskString(key, stringValue);
        }
        return value;
    }

    private String maskUserIdentifier(String value) {
        if (value == null || value.isBlank()) {
            return value;
        }
        return maskEmail(value);
    }

    private boolean shouldMask(String key, String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        String normalizedKey = key == null ? "" : key.toLowerCase(Locale.ROOT);
        if (EXACT_SENSITIVE_KEYS.contains(normalizedKey)) {
            return true;
        }
        for (String part : PARTIAL_SENSITIVE_KEYS) {
            if (!part.isBlank() && normalizedKey.contains(part)) {
                return true;
            }
        }
        return value.contains("@");
    }

    private String maskString(String key, String value) {
        String normalizedKey = key == null ? "" : key.toLowerCase(Locale.ROOT);
        if (value.contains("@") || normalizedKey.contains("email")) {
            return maskEmail(value);
        }
        if (value.length() <= 2) {
            return "*".repeat(value.length());
        }
        return value.charAt(0) + "***";
    }

    private String maskEmail(String email) {
        if (email == null || email.isBlank()) {
            return email;
        }
        int atIndex = email.indexOf('@');
        if (atIndex <= 0) {
            return maskGeneric(email);
        }
        String localPart = email.substring(0, atIndex);
        String domain = email.substring(atIndex);
        if (localPart.length() <= 1) {
            return "*" + domain;
        }
        return localPart.charAt(0) + "***" + domain;
    }

    private String maskGeneric(String value) {
        if (value == null || value.isBlank()) {
            return value;
        }
        if (value.length() <= 2) {
            return "*".repeat(value.length());
        }
        return value.charAt(0) + "***";
    }

    private static final Set<String> EXACT_SENSITIVE_KEYS = Set.of(
            "name",
            "fullname",
            "firstName",
            "firstname",
            "lastName",
            "lastname",
            "displayname",
            "contactname",
            "phone",
            "phonenumber",
            "mobile",
            "address",
            "ip",
            "identifier",
            "nationalid",
            "citizenid",
            "ssn"
    );

    private static final Set<String> PARTIAL_SENSITIVE_KEYS = Set.of(
            "email",
            "phone",
            "address",
            "token",
            "secret",
            "identifier",
            "ip"
    );
}
