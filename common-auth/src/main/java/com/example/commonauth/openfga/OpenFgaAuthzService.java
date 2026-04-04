package com.example.commonauth.openfga;

import dev.openfga.sdk.api.client.OpenFgaClient;
import dev.openfga.sdk.api.client.model.ClientCheckRequest;
import dev.openfga.sdk.api.client.model.ClientExpandRequest;
import dev.openfga.sdk.api.client.model.ClientListObjectsRequest;
import dev.openfga.sdk.api.client.model.ClientWriteRequest;
import dev.openfga.sdk.api.client.model.ClientTupleKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Wrapper around OpenFGA Java SDK.
 * When disabled (dev/permitAll mode), all checks return true and
 * listObjects returns the configured dev scope IDs.
 */
public class OpenFgaAuthzService {

    private static final Logger log = LoggerFactory.getLogger(OpenFgaAuthzService.class);

    private final OpenFgaClient client;
    private final OpenFgaProperties properties;
    private final boolean enabled;

    public OpenFgaAuthzService(OpenFgaClient client, OpenFgaProperties properties) {
        this.client = client;
        this.properties = properties;
        this.enabled = properties.isEnabled() && client != null;
        if (!enabled) {
            log.warn("OpenFGA is DISABLED — all checks return true, scopes from dev config");
        }
    }

    /**
     * Check if a user has a relation on an object.
     * Example: check("1", "viewer", "company", "5")
     */
    public boolean check(String userId, String relation, String objectType, String objectId) {
        if (!enabled) {
            return true;
        }
        try {
            var request = new ClientCheckRequest()
                    .user("user:" + userId)
                    .relation(relation)
                    ._object(objectType + ":" + objectId);

            var response = client.check(request).get();
            boolean allowed = Boolean.TRUE.equals(response.getAllowed());

            log.debug("OpenFGA check: user:{} {} {}:{} → {}",
                    userId, relation, objectType, objectId, allowed);
            return allowed;
        } catch (Exception e) {
            log.error("OpenFGA check failed, denying access: user:{} {} {}:{}",
                    userId, relation, objectType, objectId, e);
            return false;
        }
    }

    /**
     * List all objects of a type that a user has a relation on.
     * Example: listObjects("1", "viewer", "company") → ["1", "5"]
     * Returns object IDs (without type prefix).
     */
    public List<String> listObjects(String userId, String relation, String objectType) {
        if (!enabled) {
            return devFallbackIds(objectType);
        }
        try {
            var request = new ClientListObjectsRequest()
                    .user("user:" + userId)
                    .relation(relation)
                    .type(objectType);

            var response = client.listObjects(request).get();
            List<String> objects = response.getObjects();
            if (objects == null) {
                return Collections.emptyList();
            }

            String prefix = objectType + ":";
            List<String> ids = objects.stream()
                    .map(o -> o.startsWith(prefix) ? o.substring(prefix.length()) : o)
                    .collect(Collectors.toList());

            log.debug("OpenFGA listObjects: user:{} {} {} → {}", userId, relation, objectType, ids);
            return ids;
        } catch (Exception e) {
            log.error("OpenFGA listObjects failed: user:{} {} {}", userId, relation, objectType, e);
            return Collections.emptyList();
        }
    }

    /**
     * List allowed object IDs as Long set (convenience for scope filtering).
     */
    public Set<Long> listObjectIds(String userId, String relation, String objectType) {
        if (!enabled) {
            return devFallbackLongIds(objectType);
        }
        return listObjects(userId, relation, objectType).stream()
                .map(id -> {
                    try {
                        return Long.parseLong(id);
                    } catch (NumberFormatException e) {
                        log.warn("Non-numeric object ID skipped: {}", id);
                        return null;
                    }
                })
                .filter(id -> id != null)
                .collect(Collectors.toSet());
    }

    /**
     * Write a relationship tuple.
     * Example: writeTuple("1", "admin", "company", "5")
     */
    public void writeTuple(String userId, String relation, String objectType, String objectId) {
        if (!enabled) {
            log.info("OpenFGA disabled — skipping writeTuple: user:{} {} {}:{}",
                    userId, relation, objectType, objectId);
            return;
        }
        try {
            var tuple = new ClientTupleKey()
                    .user("user:" + userId)
                    .relation(relation)
                    ._object(objectType + ":" + objectId);

            var request = new ClientWriteRequest().writes(List.of(tuple));
            client.write(request).get();

            log.info("OpenFGA tuple written: user:{} {} {}:{}", userId, relation, objectType, objectId);
        } catch (Exception e) {
            log.error("OpenFGA writeTuple failed: user:{} {} {}:{}",
                    userId, relation, objectType, objectId, e);
            throw new RuntimeException("Failed to write authorization tuple", e);
        }
    }

    /**
     * Delete a relationship tuple.
     */
    public void deleteTuple(String userId, String relation, String objectType, String objectId) {
        if (!enabled) {
            log.info("OpenFGA disabled — skipping deleteTuple: user:{} {} {}:{}",
                    userId, relation, objectType, objectId);
            return;
        }
        try {
            var tuple = new ClientTupleKey()
                    .user("user:" + userId)
                    .relation(relation)
                    ._object(objectType + ":" + objectId);

            var request = new ClientWriteRequest().deletes(List.of(tuple));
            client.write(request).get();

            log.info("OpenFGA tuple deleted: user:{} {} {}:{}", userId, relation, objectType, objectId);
        } catch (Exception e) {
            log.error("OpenFGA deleteTuple failed: user:{} {} {}:{}",
                    userId, relation, objectType, objectId, e);
            throw new RuntimeException("Failed to delete authorization tuple", e);
        }
    }

    /**
     * Expand the relationship tree for an object and relation.
     * Returns the raw tree structure showing how access is derived.
     * Used for "explain why" features.
     */
    public Object expand(String objectType, String objectId, String relation) {
        if (!enabled) {
            return Map.of("allowed", true, "source", "dev-mode-bypass");
        }
        try {
            var request = new ClientExpandRequest()
                    .relation(relation)
                    ._object(objectType + ":" + objectId);

            var response = client.expand(request).get();
            log.debug("OpenFGA expand: {} {}:{} → tree returned", relation, objectType, objectId);
            return response.getTree();
        } catch (Exception e) {
            log.error("OpenFGA expand failed: {} {}:{}", relation, objectType, objectId, e);
            return Map.of("error", e.getMessage());
        }
    }

    /**
     * Explain access: check + expand combined for a user.
     * Returns allowed flag + relationship chain.
     */
    public Map<String, Object> explainAccess(String userId, String relation, String objectType, String objectId) {
        boolean allowed = check(userId, relation, objectType, objectId);
        Object tree = expand(objectType, objectId, relation);
        return Map.of(
                "allowed", allowed,
                "userId", userId,
                "relation", relation,
                "objectType", objectType,
                "objectId", objectId,
                "tree", tree
        );
    }

    public boolean isEnabled() {
        return enabled;
    }

    private List<String> devFallbackIds(String objectType) {
        OpenFgaProperties.DevScope dev = properties.getDevScope();
        return switch (objectType) {
            case "company" -> dev.getCompanyIds().stream().map(String::valueOf).collect(Collectors.toList());
            case "project" -> dev.getProjectIds().stream().map(String::valueOf).collect(Collectors.toList());
            case "warehouse" -> dev.getWarehouseIds().stream().map(String::valueOf).collect(Collectors.toList());
            default -> Collections.emptyList();
        };
    }

    private Set<Long> devFallbackLongIds(String objectType) {
        OpenFgaProperties.DevScope dev = properties.getDevScope();
        return switch (objectType) {
            case "company" -> dev.getCompanyIds();
            case "project" -> dev.getProjectIds();
            case "warehouse" -> dev.getWarehouseIds();
            default -> Collections.emptySet();
        };
    }
}
