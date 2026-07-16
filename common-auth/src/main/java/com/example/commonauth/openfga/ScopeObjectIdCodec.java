package com.example.commonauth.openfga;

import java.util.Locale;
import java.util.Map;
import java.util.OptionalLong;

/**
 * ADR-0008 &sect; "Object id encoding" — type-aware codec for OpenFGA scope object ids.
 *
 * <p><b>Why this exists (board #2531):</b> the canonical grant path
 * ({@code POST /api/v1/access/scope} &rarr; {@code DataAccessScopeTupleEncoder}) writes ADR-0008
 * canonical slugs such as {@code project:wc-project-1204}. The reader
 * ({@link OpenFgaAuthzService#listObjectIds}) used to do a bare {@code Long.parseLong} on the id
 * and <em>silently dropped</em> anything non-numeric ("Non-numeric object ID skipped"). Result:
 * the API answered {@code 201 Created}, the tuple existed in OpenFGA, and
 * {@code /authz/me.allowedScopes} was still empty &rarr; the user got {@code 403}. A granted
 * permission that never takes effect, with no error anywhere.
 *
 * <p><b>Canonical form is the slug, not the number.</b> ADR-0008 fixes the mapping:
 * <pre>
 *   scope_kind  scope_ref     OpenFGA object id              anchor table
 *   company     ["1"]         company:wc-our-company-1       OUR_COMPANY (V25)
 *   project     ["1204"]      project:wc-project-1204        PRO_PROJECTS
 *   depot       ["3792"]      warehouse:wc-department-3792   DEPARTMENT
 *   branch      ["7"]         branch:wc-branch-7             BRANCH
 * </pre>
 * Therefore the fix belongs in the reader, not the writer: flipping the writer to bare numerics
 * would contradict the ADR and would strand existing slug tuples, outbox payloads and the
 * revoke/delete recomputation (they all derive delete targets from the canonical encoding).
 *
 * <p><b>Legacy numeric ids stay readable.</b> {@code TupleSyncService} (role/permission derived
 * tuples) historically writes bare numerics ({@code project:1204}). Those must keep working
 * during the transition, so {@link #decode} accepts them and reports them via
 * {@link DecodedObjectId#legacyNumeric()} so callers can surface a WARN/metric instead of
 * failing closed on data that is already live.
 *
 * <p>Stateless; no Spring dependency so it is unit-testable and reusable from any caller.
 */
public final class ScopeObjectIdCodec {

    /**
     * ADR-0008 object type &rarr; canonical id prefix. Note the deliberate asymmetry:
     * PG {@code scope_kind='depot'} maps to OpenFGA object type {@code warehouse} while the id
     * prefix uses {@code department} (Faz 21.A: source table = DEPARTMENT).
     */
    private static final Map<String, String> CANONICAL_PREFIX = Map.of(
            "company", "wc-our-company-",
            "project", "wc-project-",
            "warehouse", "wc-department-",
            "branch", "wc-branch-");

    private ScopeObjectIdCodec() {}

    /**
     * Result of decoding one OpenFGA object id.
     *
     * @param id            numeric entity id carried by the object id
     * @param legacyNumeric {@code true} when the id was a bare number (pre-ADR-0008 form) rather
     *                      than the canonical {@code wc-*} slug — caller should WARN/meter it
     */
    public record DecodedObjectId(long id, boolean legacyNumeric) {}

    /** @return {@code true} if this object type participates in ADR-0008 scope encoding. */
    public static boolean supports(String objectType) {
        return objectType != null && CANONICAL_PREFIX.containsKey(objectType.toLowerCase(Locale.ROOT));
    }

    /**
     * Canonical encode — mirrors {@code DataAccessScopeTupleEncoder} so reader and writer cannot
     * drift apart silently.
     *
     * @throws IllegalArgumentException if the object type has no ADR-0008 encoding
     */
    public static String encode(String objectType, long id) {
        String prefix = CANONICAL_PREFIX.get(objectType == null ? null : objectType.toLowerCase(Locale.ROOT));
        if (prefix == null) {
            throw new IllegalArgumentException("No ADR-0008 encoding for object type: " + objectType);
        }
        return prefix + id;
    }

    /**
     * Decode an OpenFGA object id (already stripped of the {@code <type>:} prefix).
     *
     * <p>Accepts, in order:
     * <ol>
     *   <li>the ADR-0008 canonical slug for {@code objectType} (e.g. {@code wc-project-1204});</li>
     *   <li>a bare numeric id (legacy {@code TupleSyncService} form) — flagged as legacy.</li>
     * </ol>
     * Anything else (unknown prefix, wrong type's prefix, empty numeric part, non-numeric tail)
     * yields {@link OptionalLong#empty()}: unknown shapes are <em>not</em> guessed at.
     */
    public static java.util.Optional<DecodedObjectId> decode(String objectType, String rawId) {
        if (rawId == null) {
            return java.util.Optional.empty();
        }
        String id = rawId.trim();
        if (id.isEmpty()) {
            return java.util.Optional.empty();
        }

        String prefix = CANONICAL_PREFIX.get(objectType == null ? null : objectType.toLowerCase(Locale.ROOT));
        if (prefix != null && id.startsWith(prefix)) {
            return parseLong(id.substring(prefix.length()))
                    .map(v -> new DecodedObjectId(v, false));
        }

        // Legacy bare-numeric form (TupleSyncService). Kept readable on purpose: these tuples are
        // live today and failing closed on them would revoke working access.
        return parseLong(id).map(v -> new DecodedObjectId(v, true));
    }

    private static java.util.Optional<Long> parseLong(String s) {
        if (s == null || s.isEmpty()) {
            return java.util.Optional.empty();
        }
        for (int i = 0; i < s.length(); i++) {
            if (!Character.isDigit(s.charAt(i))) {
                return java.util.Optional.empty();
            }
        }
        try {
            return java.util.Optional.of(Long.parseLong(s));
        } catch (NumberFormatException e) {
            return java.util.Optional.empty();   // overflow
        }
    }
}
