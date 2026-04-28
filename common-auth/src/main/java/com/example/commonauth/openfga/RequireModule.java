package com.example.commonauth.openfga;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * ADR-0012 Phase 3: Replace @PreAuthorize with OpenFGA module check.
 *
 * Instead of checking JWT-embedded permissions via hasAuthority(),
 * this annotation triggers a runtime OpenFGA check for the specified module.
 *
 * Example: @RequireModule(value = "ACCESS", relation = "can_view")
 * → OpenFGA check(userId, "can_view", "module", "ACCESS")
 *
 * Canonical relation values match the deployed OpenFGA `module` type:
 *   - "can_view"   — read-only module access
 *   - "can_manage" — full module management (create/update/delete)
 *   - "can_edit"   — edit (rarely used directly; subset of can_manage)
 *   - "blocked"    — explicit deny
 *
 * Legacy aliases ("viewer", "manager", "admin") remain accepted as input
 * for backward compatibility — RequireModuleInterceptor maps them to the
 * canonical relations above. New code SHOULD use canonical names directly.
 *
 * Falls back to @PreAuthorize behavior when OpenFGA is disabled (dev mode).
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface RequireModule {
    /** OpenFGA module name (e.g., "ACCESS", "AUDIT", "REPORT"). */
    String value();

    /**
     * Required relation. Canonical values: "can_view", "can_manage", "can_edit", "blocked".
     * Legacy aliases ("viewer"→"can_view", "manager"→"can_manage", "admin"→"can_manage",
     * "editor"→"can_edit") are accepted but deprecated; prefer canonical names.
     */
    String relation() default "can_view";
}
