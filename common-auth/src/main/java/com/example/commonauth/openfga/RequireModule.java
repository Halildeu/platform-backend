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
 * Example: @RequireModule(value = "ACCESS", relation = "viewer")
 * → OpenFGA check(userId, "viewer", "module", "ACCESS")
 *
 * Falls back to @PreAuthorize behavior when OpenFGA is disabled (dev mode).
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface RequireModule {
    /** OpenFGA module name (e.g., "ACCESS", "AUDIT", "REPORT"). */
    String value();

    /** Required relation (e.g., "viewer", "manager", "admin"). */
    String relation() default "viewer";
}
