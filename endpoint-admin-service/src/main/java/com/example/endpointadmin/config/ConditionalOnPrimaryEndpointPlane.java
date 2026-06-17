package com.example.endpointadmin.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Gates the primary endpoint-admin enrollment and command plane. Dedicated
 * remote-bridge deployments disable this plane so they do not need, or expose,
 * device-command encryption material.
 */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Documented
@ConditionalOnProperty(
        prefix = "endpoint-admin.primary-plane",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true)
public @interface ConditionalOnPrimaryEndpointPlane {
}
