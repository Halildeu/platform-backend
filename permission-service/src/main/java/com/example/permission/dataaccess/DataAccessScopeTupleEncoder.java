package com.example.permission.dataaccess;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Faz 21.3 ADR-0008 § "Object id encoding" — pure deterministic mapping from
 * {@link DataAccessScope} (PG row) to an OpenFGA tuple shape.
 *
 * <p>Critical naming bridge: PG {@code scope_kind = 'depot'} maps to OpenFGA
 * object type {@code warehouse} (Faz 21.A decision: depot source = DEPARTMENT).
 *
 * <p>Stateless utility — no Spring deps so it can be unit-tested without
 * a context and reused from any caller (writer, future outbox poller, etc.).
 */
public final class DataAccessScopeTupleEncoder {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private DataAccessScopeTupleEncoder() {}

    public record FgaTuple(
            String objectType,
            String objectId,
            String relation,
            String userType,
            String userId
    ) {}

    public static FgaTuple encode(DataAccessScope scope) {
        if (scope == null) {
            throw new IllegalArgumentException("scope must not be null");
        }
        if (scope.getUserId() == null) {
            throw new IllegalArgumentException("scope.userId must not be null");
        }
        if (scope.getScopeKind() == null) {
            throw new IllegalArgumentException("scope.scopeKind must not be null");
        }
        if (scope.getScopeRef() == null) {
            throw new IllegalArgumentException("scope.scopeRef must not be null");
        }

        String firstRef = parseFirstRef(scope.getScopeRef());

        ObjectMapping mapping = switch (scope.getScopeKind()) {
            case COMPANY -> new ObjectMapping("company", "wc-company-" + firstRef);
            case PROJECT -> new ObjectMapping("project", "wc-project-" + firstRef);
            case BRANCH -> new ObjectMapping("branch", "wc-branch-" + firstRef);
            // ADR-0008 § Naming: PG 'depot' → OpenFGA 'warehouse';
            // entity prefix uses 'department' (Faz 21.A: source table = DEPARTMENT).
            case DEPOT -> new ObjectMapping("warehouse", "wc-department-" + firstRef);
        };

        return new FgaTuple(
                mapping.objectType(),
                mapping.objectId(),
                "viewer",
                "user",
                scope.getUserId().toString()
        );
    }

    private static String parseFirstRef(String scopeRef) {
        JsonNode node;
        try {
            node = MAPPER.readTree(scopeRef);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("scope.scopeRef is not valid JSON: " + scopeRef, e);
        }
        if (node == null || !node.isArray() || node.isEmpty()) {
            throw new IllegalArgumentException(
                    "scope.scopeRef must be a non-empty JSON array, got: " + scopeRef);
        }
        JsonNode first = node.get(0);
        if (first == null || first.isNull()) {
            throw new IllegalArgumentException(
                    "scope.scopeRef first element is null/missing: " + scopeRef);
        }
        // Faz 21.A contract: scope_ref first element must be a scalar
        // (string or number). For nested arrays/objects, JsonNode.asText()
        // silently returns an empty string, which would produce invalid
        // OpenFGA object ids like "wc-company-" with no PK suffix. Reject
        // explicitly so the failure surfaces at the encoder layer rather
        // than as a downstream OpenFGA 400.
        if (!first.isTextual() && !first.isNumber()) {
            throw new IllegalArgumentException(
                    "scope.scopeRef first element must be a scalar (string or number), got: "
                            + first.getNodeType() + " in " + scopeRef);
        }
        String text = first.asText();
        if (text.isBlank()) {
            throw new IllegalArgumentException(
                    "scope.scopeRef first element must be non-blank, got: " + scopeRef);
        }
        return text;
    }

    private record ObjectMapping(String objectType, String objectId) {}
}
