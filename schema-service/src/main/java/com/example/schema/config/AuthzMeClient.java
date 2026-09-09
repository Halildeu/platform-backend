package com.example.schema.config;

import java.util.List;
import java.util.Map;
import java.util.OptionalLong;

/**
 * Reads the caller's authorization projection from permission-service
 * ({@code GET /api/v1/authz/me}) with the caller's own bearer token.
 *
 * <p>gitops#3608: the Explorer's shell gate is driven by exactly this
 * projection, so gating the API on the same answer gives menu/API parity by
 * construction — identity resolution (sub/email → canonical user, disabled and
 * deleted accounts), the REPORT module invariant and deny-wins all stay in the
 * one place that owns them instead of being re-implemented here.
 */
public interface AuthzMeClient {

    AuthzMeResult fetch(String bearerToken);

    /**
     * Current platform authorization revision ({@code GET /api/v1/authz/version},
     * bumped after every grant/revoke). Read with the caller's bearer because the
     * endpoint is authenticated. Empty when it cannot be read — the gate then
     * bypasses its memo rather than trusting a stale revision.
     */
    OptionalLong fetchVersion(String bearerToken);

    /** Outcome of one {@code /authz/me} call. Exactly one of the three shapes. */
    record AuthzMeResult(Kind kind,
                         boolean superAdmin,
                         Map<String, String> modules,
                         List<String> allowedModules,
                         Long authzVersion,
                         String detail) {

        public enum Kind {
            /** permission-service answered 200 with a projection for a resolved identity. */
            OK,
            /** permission-service refused the token (401/403) — the caller is not a usable principal. */
            REJECTED,
            /** transport failure, 5xx, unparsable or identity-less body — no answer, never an allow. */
            UNAVAILABLE
        }

        public static AuthzMeResult ok(boolean superAdmin, Map<String, String> modules,
                                       List<String> allowedModules, Long authzVersion) {
            return new AuthzMeResult(Kind.OK, superAdmin,
                    modules == null ? Map.of() : Map.copyOf(modules),
                    allowedModules == null ? List.of() : List.copyOf(allowedModules),
                    authzVersion, "ok");
        }

        public static AuthzMeResult rejected(int status) {
            return new AuthzMeResult(Kind.REJECTED, false, Map.of(), List.of(), null, "http_" + status);
        }

        public static AuthzMeResult unavailable(String detail) {
            return new AuthzMeResult(Kind.UNAVAILABLE, false, Map.of(), List.of(), null, detail);
        }
    }
}
