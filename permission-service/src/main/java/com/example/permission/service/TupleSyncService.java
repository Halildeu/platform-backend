package com.example.permission.service;

import com.example.commonauth.openfga.OpenFgaAuthzService;
import com.example.commonauth.openfga.ScopeObjectIdCodec;
import com.example.commonauth.scope.ScopeContextCache;
import dev.openfga.sdk.api.client.model.ClientTupleKeyWithoutCondition;
import com.example.permission.model.GrantType;
import com.example.permission.model.PermissionType;
import com.example.permission.model.RolePermission;
import com.example.permission.model.UserRoleAssignment;
import com.example.permission.repository.RolePermissionRepository;
import com.example.permission.repository.UserRoleAssignmentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Centralized OpenFGA tuple synchronization for the Zanzibar authorization model.
 * Handles feature-level (module/action/report/page/field) and data-level (scope) tuples.
 * Implements deny-wins semantics when combining permissions from multiple roles.
 */
@Service
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name = "erp.openfga.enabled", havingValue = "true", matchIfMissing = false)
public class TupleSyncService {

    private static final Logger log = LoggerFactory.getLogger(TupleSyncService.class);

    private final OpenFgaAuthzService authzService;
    private final RolePermissionRepository rolePermissionRepository;
    private final UserRoleAssignmentRepository assignmentRepository;
    private final AuthzVersionService authzVersionService;
    private final ScopeContextCache scopeContextCache;

    @org.springframework.beans.factory.annotation.Autowired
    public TupleSyncService(OpenFgaAuthzService authzService,
                            RolePermissionRepository rolePermissionRepository,
                            UserRoleAssignmentRepository assignmentRepository,
                            AuthzVersionService authzVersionService,
                            @org.springframework.lang.Nullable ScopeContextCache scopeContextCache) {
        this.authzService = authzService;
        this.rolePermissionRepository = rolePermissionRepository;
        this.assignmentRepository = assignmentRepository;
        this.authzVersionService = authzVersionService;
        this.scopeContextCache = scopeContextCache;
    }

    /**
     * R16 PR-B-2 (Codex 019e27f5 önerisi): {@code reports.<GROUP>}
     * permission key'leri OpenFGA'da {@code report_group} type'a yazılır,
     * normal {@code REPORT} key'leri (örn. dashboard {@code FIN_ANALYTICS})
     * legacy {@code report} type'ında kalır.
     */
    public static final Set<String> REPORT_GROUP_KEYS = Set.of(
            "FINANCE_REPORTS",
            "HR_REPORTS",
            "SALES_REPORTS",
            "ANALYTICS_REPORTS"
    );

    private static final String REPORTS_PREFIX = "reports.";

    /**
     * Write feature tuples for a user based on their combined role permissions.
     * Applies deny-wins semantics: if any role DENYs a permission, the user is blocked.
     */
    public void syncFeatureTuplesForUser(String userId, List<RolePermission> allPermissions) {
        syncFeatureTuplesForUser(userId, allPermissions, false);
    }

    public void syncFeatureTuplesForUser(String userId, List<RolePermission> allPermissions, boolean skipVersionIncrement) {
        boolean anyWriteFailed = false;
        for (DesiredTuple t : granuleDesiredTuples(allPermissions)) {
            try {
                authzService.writeTuple(userId, t.relation(), t.objectType(), t.objectId());
            } catch (Exception e) {
                anyWriteFailed = true;
                log.warn("OpenFGA feature tuple write failed for user:{} {} {}:{} — {}",
                        userId, t.relation(), t.objectType(), t.objectId(), e.getMessage());
            }
        }
        // Fail-loud (AG-028 #1272, Codex Option B): a reconciliation that could
        // not write all granted tuples MUST NOT be reported as success — the
        // in-tx caller rolls back, or propagateRoleChange's outbox retries.
        // Idempotent "already exists" writes are swallowed inside writeTuple and
        // never set anyWriteFailed, so only genuine failures throw here.
        if (anyWriteFailed) {
            throw new RuntimeException("OpenFGA feature tuple write failed for user:" + userId);
        }
        if (!skipVersionIncrement) {
            authzVersionService.incrementVersion();
            if (scopeContextCache != null) scopeContextCache.evictUser(userId);
        }
    }

    /**
     * Compute the user's DESIRED granule feature tuples from the deny-wins
     * effective grants. Single source for both the WRITE path
     * ({@link #syncFeatureTuplesForUser}) and the spare-set in
     * {@link #deleteStaleFeatureTuples}, so the two never drift.
     *
     * <p>R16 PR-B-2 (Codex 019e27f5): {@code reports.<GROUP>} keys map to the
     * {@code report_group} type with a normalized object id.
     */
    private List<DesiredTuple> granuleDesiredTuples(List<RolePermission> allPermissions) {
        List<DesiredTuple> out = new ArrayList<>();
        for (var entry : resolveEffectiveGrants(allPermissions).entrySet()) {
            String[] parts = entry.getKey().split(":", 2);
            PermissionType type = PermissionType.valueOf(parts[0]);
            String key = parts[1];
            TupleMapping mapping = toTupleMapping(type, key, entry.getValue().grantType());
            if (mapping == null) continue;
            String objectId = "report_group".equals(mapping.objectType())
                    ? normalizeReportGroupKey(key)
                    : key;
            out.add(new DesiredTuple(mapping.relation(), mapping.objectType(), objectId));
        }
        return out;
    }

    /**
     * Reconcile the user's granule feature tuples to their CURRENT active roles
     * (Codex Option B, AG-028 #1272): delete the OpenFGA tuples this permission
     * system OWNS that are no longer justified, then (re)write the granule
     * desired set. The delete spare-set is granule-desired ∪ legacy-desired, so a
     * mixed legacy+granule user never loses legacy {@code module:*} tuples that
     * this method does not re-write (legacy positive writes stay owned by the
     * legacy path / {@link #writeLegacyTuplesForUser}).
     */
    @Transactional
    public void refreshFeatureTuples(String userId) {
        refreshFeatureTuples(userId, false);
    }

    // read-write @Transactional (was readOnly=true): the !skip path bumps
    // authz_sync_version (an UPDATE), which a read-only tx rejects — the OI-03
    // Bug 4 (Codex 019da431) trap already fixed on propagateRoleChange.
    // Self-invocation from propagateRoleChange (skip=true) joins that tx.
    @Transactional
    public void refreshFeatureTuples(String userId, boolean skipVersionIncrement) {
        Long numericUserId = Long.parseLong(userId);
        List<UserRoleAssignment> assignments = assignmentRepository.findActiveAssignments(numericUserId);
        List<Long> roleIds = assignments.stream()
                .map(a -> a.getRole().getId())
                .distinct()
                .toList();
        List<RolePermission> allPermissions = roleIds.isEmpty()
                ? List.of()
                : rolePermissionRepository.findByRoleIdIn(roleIds);

        // B: delete owned stored tuples no longer justified, then (re)write
        // granule desired. Both fail-loud — a partial failure rolls back the
        // in-tx caller (revoke/replace/delete) or keeps the role-change outbox
        // PENDING for retry.
        deleteStaleFeatureTuples(userId, allPermissions);
        syncFeatureTuplesForUser(userId, allPermissions, true);

        if (!skipVersionIncrement) {
            authzVersionService.incrementVersion();
            if (scopeContextCache != null) scopeContextCache.evictUser(userId);
        }
    }

    /**
     * GAIN-reconcile for a user who gained or swapped a role (#1275, Codex
     * 019ea233): the fail-loud Option-A end-state that replaces
     * {@code PermissionService.syncTuplesToOpenFga}'s fail-silent per-role legacy
     * write. Mirrors {@link AccessRoleService#applyBulkPermissions}'s composition
     * so the three legacy-mutating paths (bulk permissions, assignRole,
     * updateAssignment) share ONE ordering and ONE version bump:
     * <ol>
     *   <li>{@link #refreshFeatureTuples} deletes any owned tuple (granule OR
     *       legacy {@code module:*}) no longer justified by the user's current
     *       active roles, then writes the granule desired set;</li>
     *   <li>{@link #writeLegacyTuplesForUser} writes the aggregate legacy desired
     *       set — the positive legacy {@code module:*} writes the granule refresh
     *       deliberately does NOT perform (add/upgrade).</li>
     * </ol>
     * Both steps are fail-loud, so a genuine OpenFGA failure rolls back the in-tx
     * caller (assignRole/updateAssignment). The single version bump + cache evict
     * run once, AFTER both tuple sets are consistent. Pure-REMOVE paths
     * (revokeRole) do NOT use this — {@link #refreshFeatureTuples} alone deletes
     * the stale legacy tuple via its spare-set and needs no positive legacy write.
     */
    @Transactional
    public void refreshFeatureAndLegacyTuplesForUser(String userId) {
        refreshFeatureTuples(userId, true);
        writeLegacyTuplesForUser(userId, true);
        authzVersionService.incrementVersion();
        if (scopeContextCache != null) scopeContextCache.evictUser(userId);
    }

    /**
     * Write the user's aggregate LEGACY desired module tuples (fail-loud). Used
     * by legacy permission paths (e.g. {@code PATCH /roles/{id}/permissions/
     * bulk}) where {@link #refreshFeatureTuples} deletes a stale legacy relation
     * but does not write the new one (B keeps legacy positive writes out of the
     * granule refresh). Reuses {@link LegacyPermissionTupleMapper} so the mapping
     * matches the refresh spare-set and the {@code PermissionService} write path.
     */
    @Transactional
    public void writeLegacyTuplesForUser(String userId, boolean skipVersionIncrement) {
        Long numericUserId = Long.parseLong(userId);
        List<UserRoleAssignment> assignments = assignmentRepository.findActiveAssignments(numericUserId);
        List<Long> roleIds = assignments.stream().map(a -> a.getRole().getId()).distinct().toList();
        List<RolePermission> allPermissions = roleIds.isEmpty()
                ? List.of()
                : rolePermissionRepository.findByRoleIdIn(roleIds);

        boolean anyWriteFailed = false;
        Set<String> written = new HashSet<>();
        for (RolePermission rp : allPermissions) {
            if (rp.getPermission() == null) continue;
            LegacyPermissionTupleMapper.LegacyTuple t =
                    LegacyPermissionTupleMapper.toTuple(rp.getPermission().getCode());
            if (t == null || !written.add(t.canonical())) continue;
            try {
                authzService.writeTuple(userId, t.relation(), t.objectType(), t.objectId());
            } catch (Exception e) {
                anyWriteFailed = true;
                log.warn("OpenFGA legacy tuple write failed for user:{} {} {}:{} — {}",
                        userId, t.relation(), t.objectType(), t.objectId(), e.getMessage());
            }
        }
        if (anyWriteFailed) {
            throw new RuntimeException("OpenFGA legacy tuple write failed for user:" + userId);
        }
        if (!skipVersionIncrement) {
            authzVersionService.incrementVersion();
            if (scopeContextCache != null) scopeContextCache.evictUser(userId);
        }
    }

    /**
     * When a role's permissions change, refresh tuples for ALL users assigned to that role.
     */
    // 2026-04-18 OI-03 Bug 4 (Codex 019da431): propagateRoleChange must be
    // writable because authzVersionService.incrementVersion() performs an UPDATE
    // on authz_sync_version. Previous `readOnly=true` caused Spring/Hibernate to
    // reject the UPDATE with "cannot execute UPDATE in a read-only transaction"
    // and the fastpath consistently failed — outbox poller would retry 30 s
    // later, inflating authzVersion propagation latency and log noise.
    @Transactional
    public void propagateRoleChange(Long roleId) {
        List<UserRoleAssignment> assignments = assignmentRepository.findByRoleIdAndActiveTrue(roleId);
        Set<Long> userIds = assignments.stream()
                .map(UserRoleAssignment::getUserId)
                .collect(Collectors.toSet());

        log.info("Propagating role {} change to {} users", roleId, userIds.size());

        // AG-028 #1272 (Codex Option B): attempt ALL users, but if any user's
        // reconciliation fails, throw BEFORE the version bump so the fast path
        // does NOT mark the outbox DONE — RoleChangeEventHandler.onRoleChange
        // leaves the entry PENDING and the poller retries (idempotent).
        List<Long> failedUsers = new java.util.ArrayList<>();
        for (Long numericUserId : userIds) {
            try {
                refreshFeatureTuples(String.valueOf(numericUserId), true);
            } catch (Exception e) {
                failedUsers.add(numericUserId);
                log.error("Failed to refresh tuples for user:{} after role:{} change", numericUserId, roleId, e);
            }
        }
        if (!failedUsers.isEmpty()) {
            throw new RuntimeException("Role " + roleId
                    + " tuple propagation failed for users " + failedUsers + " — outbox will retry");
        }
        authzVersionService.incrementVersion();
        if (scopeContextCache != null) scopeContextCache.evictAll();
    }

    /**
     * Write data scope tuples for a user.
     */
    public void syncScopeTuples(String userId, List<Long> companyIds, List<Long> projectIds,
                                List<Long> warehouseIds, List<Long> branchIds) {
        syncScopeTuples(userId, companyIds, projectIds, warehouseIds, branchIds, false);
    }

    public void syncScopeTuples(String userId, List<Long> companyIds, List<Long> projectIds,
                                List<Long> warehouseIds, List<Long> branchIds, boolean skipVersionIncrement) {
        boolean anyFailed = false;
        // Codex thread 019e0891 iter-2 AGREE absorb (2026-05-08 PR-BE-9 Phase 1):
        // Faz 21.3 ADR-0008 "explicit-scope contract" canonical model uses
        // `viewer: [user]` for ALL scope object types (company/project/
        // warehouse/branch). Previous code wrote `operator` for warehouse and
        // `member` for branch — relations defined ONLY in legacy multi-tier
        // model.fga (root); `backend/openfga/model.fga` (canonical per ADR-0008
        // 2026-04-26) drops them entirely. On a cluster loading the canonical
        // model, those writes return success at SDK level but produce a tuple
        // that no `viewer` lookup can ever resolve — silent persistence loss
        // surfaced as the "şirket yetkileri kayıt olmuyor" production bug.
        // Aligning all four object types on `viewer` makes WRITE relation
        // match what ScopeContextFilter (line ~125-132) already reads.
        anyFailed |= !writeScopeTuplesSafe(userId, "viewer", "company", companyIds);
        anyFailed |= !writeScopeTuplesSafe(userId, "viewer", "project", projectIds);
        anyFailed |= !writeScopeTuplesSafe(userId, "viewer", "warehouse", warehouseIds);
        anyFailed |= !writeScopeTuplesSafe(userId, "viewer", "branch", branchIds);
        if (!skipVersionIncrement && !anyFailed) {
            authzVersionService.incrementVersion();
            if (scopeContextCache != null) scopeContextCache.evictUser(userId);
        } else if (anyFailed) {
            log.warn("Skipping version bump for user:{} — scope tuple writes had failures", userId);
        }
    }

    /**
     * Resolve effective grants from multiple role permissions using deny-wins semantics.
     * Key format: "PERMISSION_TYPE:permission_key"
     */
    public Map<String, ResolvedGrant> resolveEffectiveGrants(List<RolePermission> permissions) {
        Map<String, ResolvedGrant> effective = new LinkedHashMap<>();

        for (RolePermission rp : permissions) {
            if (rp.getPermissionType() == null || rp.getPermissionKey() == null || rp.getGrantType() == null) {
                continue;
            }

            String compositeKey = rp.getPermissionType().name() + ":" + rp.getPermissionKey();
            ResolvedGrant existing = effective.get(compositeKey);

            if (existing == null) {
                effective.put(compositeKey, new ResolvedGrant(rp.getGrantType(), rp.getRole().getName()));
            } else if (rp.getGrantType() == GrantType.DENY) {
                // DENY always wins
                effective.put(compositeKey, new ResolvedGrant(GrantType.DENY, rp.getRole().getName()));
            } else if (existing.grantType() != GrantType.DENY && isHigherGrant(rp.getGrantType(), existing.grantType())) {
                // Higher grant wins (MANAGE > VIEW, ALLOW > VIEW)
                effective.put(compositeKey, new ResolvedGrant(rp.getGrantType(), rp.getRole().getName()));
            }
        }

        return effective;
    }

    // --- Private helpers ---

    /**
     * @return true if write succeeded, false on failure
     *
     * <p>Board #2542: emits the ADR-0008 <em>canonical</em> object id
     * ({@code project:wc-project-1204}) via {@link ScopeObjectIdCodec#encode}, not the historical
     * bare numeric ({@code project:1204}). Before this change permission-service had two scope
     * writers on two encodings — {@code DataAccessScopeTupleEncoder} (canonical) and this one
     * (numeric) — which is the writer half of the #2530 principal/object contract drift.
     *
     * <p><b>Why this is access-safe on the way in.</b> {@code OpenFgaAuthzService.listObjectIds}
     * decodes canonical AND legacy numeric to the same {@code long} and collects into a
     * {@code LinkedHashSet<Long>} (#2531), so a user who holds both encodings for entity 1204
     * resolves to a single scope id — no duplicate, no gap. Existing numeric tuples keep granting
     * access until the backfill/cleanup slice removes them.
     *
     * <p><b>Deliberately fail-loud on an unsupported type.</b> {@code encode} throws for an object
     * type with no ADR-0008 encoding. The four callers below are all encodable; a future fifth
     * scope type must be added to the codec rather than silently written in a non-canonical shape
     * that the reader would then have to guess at.
     */
    private boolean writeScopeTuplesSafe(String userId, String relation, String objectType, List<Long> ids) {
        if (ids == null || ids.isEmpty()) return true;
        var tuples = ids.stream()
                .map(id -> OpenFgaAuthzService.writeTupleKey(
                        userId, relation, objectType, ScopeObjectIdCodec.encode(objectType, id)))
                .toList();
        try {
            authzService.writeTuples(tuples);
            log.debug("OpenFGA batch scope write: {} tuples for user:{} {}:{}", tuples.size(), userId, relation, objectType);
            return true;
        } catch (Exception e) {
            log.warn("OpenFGA batch scope write failed for user:{} {}:{} ({} tuples) — {}",
                    userId, relation, objectType, tuples.size(), e.getMessage());
            return false;
        }
    }

    /**
     * Object types whose tuples are managed by feature-grant sync (module /
     * action / report / report_group). Scope tuples (company / project /
     * warehouse / branch) are managed separately by {@link #syncScopeTuples}
     * and MUST NOT be touched by a feature refresh — so the ground-truth Read is
     * scoped to exactly these types.
     */
    private static final java.util.List<String> FEATURE_OBJECT_TYPES =
            java.util.List.of("module", "action", "report", "report_group");

    /**
     * Relations this permission system OWNS per feature object type. The
     * reconcile delete is filtered to these so a future unrelated direct-user
     * relation on the same object types is never swept (Codex Option B point 2).
     */
    private static final Map<String, Set<String>> OWNED_RELATIONS = Map.of(
            "module", Set.of("can_view", "can_edit", "can_manage", "blocked"),
            "action", Set.of("allowed", "blocked"),
            "report", Set.of("can_view", "can_edit", "blocked"),
            "report_group", Set.of("can_view", "can_edit", "blocked"));

    /** A desired feature tuple ({@code objectType:objectId#relation}). */
    private record DesiredTuple(String relation, String objectType, String objectId) {
        /** Canonical {@code relation|objectType:objectId} identity. */
        String canonical() {
            return relation + "|" + objectType + ":" + objectId;
        }
    }

    /**
     * Delete the user's stored feature tuples that this system OWNS and that are
     * NO LONGER justified by their current active roles (Codex Option B, AG-028
     * #1272). The spare-set is {@code granuleDesired ∪ legacyDesired} compared by
     * EXACT tuple identity (user+relation+type+id) — so a tuple still granted by
     * another active role, or a legacy {@code module:*} tuple this method does
     * not re-write, is preserved. Fail-loud: a genuine {@code deleteTuples}
     * failure propagates (missing-tuple is idempotent inside deleteTuples) so a
     * partial cleanup is never reported as success.
     */
    private void deleteStaleFeatureTuples(String userId, List<RolePermission> allPermissions) {
        Set<String> completeDesired = new HashSet<>();
        for (DesiredTuple t : granuleDesiredTuples(allPermissions)) {
            completeDesired.add(t.canonical());
        }
        completeDesired.addAll(legacyDesiredCanonical(allPermissions));

        List<ClientTupleKeyWithoutCondition> stored = authzService.readUserTuples(userId, FEATURE_OBJECT_TYPES);
        List<ClientTupleKeyWithoutCondition> toDelete = new ArrayList<>();
        for (ClientTupleKeyWithoutCondition t : stored) {
            String object = t.getObject();      // e.g. "module:ACCESS"
            String relation = t.getRelation();
            if (object == null || relation == null) continue;
            int sep = object.indexOf(':');
            if (sep < 0) continue;
            String objectType = object.substring(0, sep);
            Set<String> owned = OWNED_RELATIONS.get(objectType);
            if (owned == null || !owned.contains(relation)) continue; // never sweep foreign relations
            if (!completeDesired.contains(relation + "|" + object)) {
                toDelete.add(t);
            }
        }
        if (toDelete.isEmpty()) return;
        authzService.deleteTuples(toDelete);
        log.debug("OpenFGA feature reconcile: deleted {} stale tuples for user:{}", toDelete.size(), userId);
    }

    /**
     * The user's LEGACY desired module tuples (canonical identity) from their
     * active legacy FK permission rows, via {@link LegacyPermissionTupleMapper}.
     * Used only to SPARE legacy tuples from the reconcile delete — legacy WRITES
     * stay owned by the legacy path (B).
     */
    private Set<String> legacyDesiredCanonical(List<RolePermission> allPermissions) {
        Set<String> out = new HashSet<>();
        for (RolePermission rp : allPermissions) {
            if (rp.getPermission() == null) continue;
            LegacyPermissionTupleMapper.LegacyTuple t =
                    LegacyPermissionTupleMapper.toTuple(rp.getPermission().getCode());
            if (t != null) out.add(t.canonical());
        }
        return out;
    }

    /**
     * Key normalize + REPORT_GROUP_KEYS membership check.
     */
    public static boolean isReportGroupKey(String key) {
        if (key == null || key.isBlank()) return false;
        String normalized = normalizeReportGroupKey(key);
        return REPORT_GROUP_KEYS.contains(normalized);
    }

    /**
     * Permission key {@code reports.FINANCE_REPORTS} → {@code FINANCE_REPORTS}.
     * Prefix yoksa key olduğu gibi döner.
     *
     * <p>R16 PR-B-2 (Codex 019e27f5): kullanılan yerler:
     * <ul>
     *   <li>{@link TupleSyncService} OpenFGA tuple write (object_id normalize)</li>
     *   <li>{@code AuthorizationControllerV1} /authz/me reports map (FE
     *       {@code canViewReport(reportGroup)} suffix-only beklediği için)</li>
     * </ul>
     */
    public static String normalizeReportGroupKey(String key) {
        if (key == null) return null;
        return key.startsWith(REPORTS_PREFIX) ? key.substring(REPORTS_PREFIX.length()) : key;
    }

    /**
     * @deprecated R16 PR-B-2: key-aware overload kullanılmalı
     *     ({@link #toTupleMapping(PermissionType, String, GrantType)}).
     *     Bu overload geriye dönük testler için tutuluyor.
     */
    @Deprecated
    TupleMapping toTupleMapping(PermissionType type, GrantType grant) {
        return toTupleMapping(type, null, grant);
    }

    private TupleMapping toTupleMapping(PermissionType type, String key, GrantType grant) {
        String reportObjectType = isReportGroupKey(key) ? "report_group" : "report";
        return switch (type) {
            case MODULE -> switch (grant) {
                case MANAGE -> new TupleMapping("can_manage", "module");
                case VIEW -> new TupleMapping("can_view", "module");
                case DENY -> new TupleMapping("blocked", "module");
                case ALLOW -> new TupleMapping("can_view", "module");
            };
            case ACTION -> switch (grant) {
                case ALLOW -> new TupleMapping("allowed", "action");
                case DENY -> new TupleMapping("blocked", "action");
                default -> null;
            };
            case REPORT -> switch (grant) {
                case MANAGE -> new TupleMapping("can_edit", reportObjectType);
                case ALLOW, VIEW -> new TupleMapping("can_view", reportObjectType);
                case DENY -> new TupleMapping("blocked", reportObjectType);
            };
            // PAGE, FIELD removed in V10 migration (TB-21)
        };
    }

    private boolean isHigherGrant(GrantType candidate, GrantType existing) {
        return grantOrdinal(candidate) > grantOrdinal(existing);
    }

    private int grantOrdinal(GrantType grant) {
        return switch (grant) {
            case DENY -> -1;
            case VIEW -> 1;
            case ALLOW -> 2;
            case MANAGE -> 3;
        };
    }

    public record ResolvedGrant(GrantType grantType, String sourceRole) {}
    private record TupleMapping(String relation, String objectType) {}
}
