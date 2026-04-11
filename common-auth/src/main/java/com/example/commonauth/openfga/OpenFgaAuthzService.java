package com.example.commonauth.openfga;

import dev.openfga.sdk.api.client.OpenFgaClient;
import dev.openfga.sdk.api.client.model.ClientCheckRequest;
import dev.openfga.sdk.api.client.model.ClientExpandRequest;
import dev.openfga.sdk.api.client.model.ClientListObjectsRequest;
import dev.openfga.sdk.api.client.model.ClientWriteRequest;
import dev.openfga.sdk.api.client.model.ClientBatchCheckItem;
import dev.openfga.sdk.api.client.model.ClientBatchCheckRequest;
import dev.openfga.sdk.api.client.model.ClientBatchCheckResponse;
import dev.openfga.sdk.api.client.model.ClientBatchCheckSingleResponse;
import dev.openfga.sdk.api.client.model.ClientTupleKey;
import dev.openfga.sdk.api.client.model.ClientTupleKeyWithoutCondition;
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

    // SK-2: Check result cache — reduces OpenFGA API calls for repeated checks
    private final com.github.benmanes.caffeine.cache.Cache<String, Boolean> checkCache;

    public OpenFgaAuthzService(OpenFgaClient client, OpenFgaProperties properties) {
        this.client = client;
        this.properties = properties;
        this.enabled = properties.isEnabled() && client != null;
        this.checkCache = com.github.benmanes.caffeine.cache.Caffeine.newBuilder()
                .expireAfterWrite(java.time.Duration.ofSeconds(10))
                .maximumSize(1000)
                .build();
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
        // SK-2: Check cache — 10s TTL, avoids repeated OpenFGA calls for same check
        String cacheKey = userId + ":" + relation + ":" + objectType + ":" + objectId;
        Boolean cached = checkCache.getIfPresent(cacheKey);
        if (cached != null) {
            log.debug("OpenFGA check (cached): user:{} {} {}:{} → {}", userId, relation, objectType, objectId, cached);
            return cached;
        }
        try {
            var request = new ClientCheckRequest()
                    .user("user:" + userId)
                    .relation(relation)
                    ._object(objectType + ":" + objectId);

            var response = client.check(request).get();
            boolean allowed = Boolean.TRUE.equals(response.getAllowed());
            checkCache.put(cacheKey, allowed);

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
     * Batch write multiple tuples in a single API call.
     * Significantly faster than individual writeTuple calls for role propagation.
     */
    public void writeTuples(List<ClientTupleKey> tuples) {
        if (!enabled || tuples == null || tuples.isEmpty()) {
            return;
        }
        try {
            var request = new ClientWriteRequest().writes(tuples);
            client.write(request).get();
            log.info("OpenFGA batch write: {} tuples", tuples.size());
        } catch (Exception e) {
            log.error("OpenFGA batch writeTuples failed ({} tuples)", tuples.size(), e);
            throw new RuntimeException("Failed to batch write authorization tuples", e);
        }
    }

    /**
     * Batch delete multiple tuples in a single API call.
     */
    public void deleteTuples(List<ClientTupleKeyWithoutCondition> tuples) {
        if (!enabled || tuples == null || tuples.isEmpty()) {
            return;
        }
        try {
            var request = new ClientWriteRequest().deletes(tuples);
            client.write(request).get();
            log.info("OpenFGA batch delete: {} tuples", tuples.size());
        } catch (Exception e) {
            log.error("OpenFGA batch deleteTuples failed ({} tuples)", tuples.size(), e);
            throw new RuntimeException("Failed to batch delete authorization tuples", e);
        }
    }

    /**
     * Build a ClientTupleKey for use with batch write operations.
     */
    public static ClientTupleKey writeTupleKey(String userId, String relation, String objectType, String objectId) {
        return new ClientTupleKey()
                .user("user:" + userId)
                .relation(relation)
                ._object(objectType + ":" + objectId);
    }

    /**
     * Build a ClientTupleKeyWithoutCondition for use with batch delete operations.
     */
    public static ClientTupleKeyWithoutCondition deleteTupleKey(String userId, String relation, String objectType, String objectId) {
        return new ClientTupleKeyWithoutCondition()
                .user("user:" + userId)
                .relation(relation)
                ._object(objectType + ":" + objectId);
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

    /**
     * Check with reason — distinguishes "blocked" from "no_relation".
     * Required for frontend AccessLevel mapping (disabled vs hidden).
     * CNS-20260411-005: Codex MODIFY — reason field mandatory for UI semantics.
     */
    public record CheckResult(boolean allowed, String reason) {}

    public CheckResult checkWithReason(String userId, String relation, String objectType, String objectId) {
        if (!enabled) {
            return new CheckResult(true, "granted");
        }
        try {
            boolean allowed = check(userId, relation, objectType, objectId);
            String reason;
            if (allowed) {
                reason = "granted";
            } else {
                boolean isBlocked = check(userId, "blocked", objectType, objectId);
                reason = isBlocked ? "blocked" : "no_relation";
            }
            // SK-5: Per-decision audit log — every authorization decision recorded
            log.info("authz.decision user={} relation={} object={}:{} allowed={} reason={}",
                    userId, relation, objectType, objectId, allowed, reason);
            return new CheckResult(allowed, reason);
        } catch (Exception e) {
            log.error("OpenFGA checkWithReason failed: user:{} {} {}:{}", userId, relation, objectType, objectId, e);
            log.info("authz.decision user={} relation={} object={}:{} allowed=false reason=error",
                    userId, relation, objectType, objectId);
            return new CheckResult(false, "error");
        }
    }

    /**
     * Batch check with reason — bounded parallelism for UI component-level checks.
     * CNS-20260411-005: Codex REJECT (without batch) — batch endpoint mandatory.
     * Max 20 checks per call enforced at controller level.
     */
    public List<CheckResult> batchCheck(String userId, List<BatchCheckRequest> requests) {
        if (!enabled) {
            return requests.stream()
                    .map(r -> new CheckResult(true, "granted"))
                    .toList();
        }
        try {
            // Use OpenFGA native BatchCheck — single HTTP call for N checks (SK-11)
            List<ClientBatchCheckItem> items = requests.stream()
                    .map(r -> new ClientBatchCheckItem()
                            .user("user:" + userId)
                            .relation(r.relation())
                            ._object(r.objectType() + ":" + r.objectId()))
                    .toList();

            var batchRequest = ClientBatchCheckRequest.ofChecks(items);
            var response = client.batchCheck(batchRequest).get();

            var results = new java.util.ArrayList<CheckResult>();
            var singleResults = response.getResult();
            for (int i = 0; i < singleResults.size(); i++) {
                var single = singleResults.get(i);
                boolean allowed = single.isAllowed();
                String reason = allowed ? "granted" : "no_relation";
                // SK-5: Per-decision audit log — batch path
                var req = i < requests.size() ? requests.get(i) : null;
                if (req != null) {
                    log.info("authz.decision user={} relation={} object={}:{} allowed={} reason={} mode=batch",
                            userId, req.relation(), req.objectType(), req.objectId(), allowed, reason);
                }
                results.add(new CheckResult(allowed, reason));
            }
            return results;
        } catch (Exception e) {
            log.warn("BatchCheck failed, falling back to parallel individual checks: {}", e.getMessage());
            // Fallback to parallel individual checks
            return requests.parallelStream()
                    .map(r -> checkWithReason(userId, r.relation(), r.objectType(), r.objectId()))
                    .toList();
        }
    }

    public record BatchCheckRequest(String relation, String objectType, String objectId) {}

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
