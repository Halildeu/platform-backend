package com.example.endpointadmin.service;

import com.example.endpointadmin.model.EndpointUninstallRequest;
import com.example.endpointadmin.model.UninstallRequestState;
import org.springframework.core.env.Environment;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/** Deployment-controlled, expiring permission for one TEST request, never a caller-supplied override. */
final class UninstallOwnerExceptionPolicy {
    private static final String PREFIX = "endpoint-admin.uninstall.owner-exception.";
    private final Environment environment;
    private final Clock clock;

    UninstallOwnerExceptionPolicy(Environment environment, Clock clock) {
        this.environment = environment;
        this.clock = clock;
    }

    record Grant(String decisionRef, Instant expiresAt) { }

    Grant resolve(EndpointUninstallRequest request, String actor) {
        if (!"platform-test".equals(environment.getProperty("POD_NAMESPACE"))
                || request.getState() != UninstallRequestState.PENDING_APPROVAL
                || !Objects.equals(request.getCreatedBy(), actor)
                || actor == null || actor.isBlank()) {
            return null;
        }
        if (!matches("tenant-id", request.getTenantId())
                || !matches("device-id", request.getDeviceId())
                || !matches("catalog-item-id", request.getCatalogItemId())
                || !matches("request-id", request.getId())
                || !matches("actor-id", actor)) {
            return null;
        }
        try {
            Instant issuedAt = Instant.parse(value("issued-at"));
            Instant expiresAt = Instant.parse(value("expires-at"));
            Instant now = clock.instant();
            Duration lifetime = Duration.between(issuedAt, expiresAt);
            String reference = value("decision-ref");
            if (now.isBefore(issuedAt) || !now.isBefore(expiresAt)
                    || lifetime.isNegative() || lifetime.isZero()
                    || lifetime.compareTo(Duration.ofHours(24)) > 0
                    || !reference.matches("https://github\\.com/Halildeu/platform-k8s-gitops/issues/[0-9]+#issuecomment-[0-9]+")) {
                return null;
            }
            return new Grant(reference, expiresAt);
        } catch (RuntimeException invalidConfiguration) {
            return null;
        }
    }

    private boolean matches(String key, Object actual) {
        return actual != null && actual.toString().equals(value(key));
    }

    private String value(String key) {
        return environment.getProperty(PREFIX + key, "");
    }
}
