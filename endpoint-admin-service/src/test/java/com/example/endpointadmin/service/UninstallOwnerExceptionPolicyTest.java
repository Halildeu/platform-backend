package com.example.endpointadmin.service;

import com.example.endpointadmin.model.EndpointUninstallRequest;
import com.example.endpointadmin.model.UninstallRequestState;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class UninstallOwnerExceptionPolicyTest {
    private static final Instant NOW = Instant.parse("2026-09-06T15:00:00Z");
    private static final String PREFIX = "endpoint-admin.uninstall.owner-exception.";
    private final EndpointUninstallRequest request = request();
    private final MockEnvironment environment = configured();

    @Test void permitsOnlyExactPendingTestRequest() {
        assertThat(resolve()).isNotNull();
        request.setState(UninstallRequestState.APPROVED);
        assertThat(resolve()).isNull();
    }

    @Test void productionMissingAndOtherNamespacesRefuseEvenWithCompleteGrant() {
        for (String namespace : new String[]{"", "platform-prod", "platform-dev"}) {
            environment.setProperty("POD_NAMESPACE", namespace);
            assertThat(resolve()).isNull();
        }
    }

    @Test void everyBindingIsMandatoryAndExact() {
        for (String key : new String[]{"tenant-id", "device-id", "catalog-item-id", "request-id", "actor-id"}) {
            String previous = environment.getProperty(PREFIX + key);
            environment.setProperty(PREFIX + key, UUID.randomUUID().toString());
            assertThat(resolve()).as(key).isNull();
            environment.setProperty(PREFIX + key, previous);
        }
        assertThat(new UninstallOwnerExceptionPolicy(environment, clock()).resolve(request, "other")).isNull();
    }

    @Test void absentExpiredFutureMalformedAndOverlongGrantsRefuse() {
        assertThat(new UninstallOwnerExceptionPolicy(new MockEnvironment(), clock()).resolve(request, "owner")).isNull();
        for (String expiry : new String[]{NOW.toString(), "2026-09-05T15:00:00Z", "invalid", "2026-09-08T15:00:00Z"}) {
            environment.setProperty(PREFIX + "expires-at", expiry);
            assertThat(resolve()).isNull();
        }
        environment.setProperty(PREFIX + "expires-at", NOW.plusSeconds(3600).toString());
        environment.setProperty(PREFIX + "issued-at", NOW.plusSeconds(1).toString());
        assertThat(resolve()).isNull();
    }

    @Test void missingDecisionEvidenceRefuses() {
        environment.setProperty(PREFIX + "decision-ref", "");
        assertThat(resolve()).isNull();
    }

    private UninstallOwnerExceptionPolicy.Grant resolve() {
        return new UninstallOwnerExceptionPolicy(environment, clock()).resolve(request, "owner");
    }

    private static Clock clock() { return Clock.fixed(NOW, ZoneOffset.UTC); }

    private MockEnvironment configured() {
        return new MockEnvironment().withProperty("POD_NAMESPACE", "platform-test")
                .withProperty(PREFIX + "tenant-id", request.getTenantId().toString())
                .withProperty(PREFIX + "device-id", request.getDeviceId().toString())
                .withProperty(PREFIX + "catalog-item-id", request.getCatalogItemId().toString())
                .withProperty(PREFIX + "request-id", request.getId().toString())
                .withProperty(PREFIX + "actor-id", "owner")
                .withProperty(PREFIX + "issued-at", NOW.minusSeconds(60).toString())
                .withProperty(PREFIX + "expires-at", NOW.plusSeconds(3600).toString())
                .withProperty(PREFIX + "decision-ref", "https://github.com/Halildeu/platform-k8s-gitops/issues/2828#issuecomment-1");
    }

    private static EndpointUninstallRequest request() {
        EndpointUninstallRequest value = new EndpointUninstallRequest();
        value.setId(UUID.randomUUID());
        value.setTenantId(UUID.randomUUID());
        value.setDeviceId(UUID.randomUUID());
        value.setCatalogItemId(UUID.randomUUID());
        value.setCreatedBy("owner");
        value.setState(UninstallRequestState.PENDING_APPROVAL);
        return value;
    }
}
