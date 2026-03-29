package com.example.commonauth.scope;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a method to bypass company scope filtering.
 * Use only for legitimate cross-company operations
 * (admin reports, migrations, system operations).
 *
 * Every bypass is logged with the reason for audit purposes.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface BypassScopeFilter {
    String reason();
}
