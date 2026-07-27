package com.example.ethics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.example.commonauth.openfga.OpenFgaAuthzService;
import com.example.commonauth.openfga.OpenFgaProperties;
import com.example.ethics.security.EthicsAuthorization;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * ES-203 / B+ slice 1 — the check that decides whether someone may be named on a case.
 *
 * <p>This one is asked about a third party rather than about the caller, which changes what a
 * wrong answer costs: an unverified name lands in the data that routing and conflict decisions
 * later read. So it fails closed in every direction that is not an explicit allow.
 */
class EthicsProductMembershipTest {
    private final OpenFgaAuthzService openFga = mock(OpenFgaAuthzService.class);
    private final OpenFgaProperties properties = enabledProperties();
    private final EthicsAuthorization authorization = new EthicsAuthorization(openFga, properties);
    private final UUID org = UUID.randomUUID();
    private final String target = UUID.randomUUID().toString();

    @Test
    void aMemberOfTheOrgProductIsAssignable() {
        when(openFga.checkNoCacheResult(target, "case_viewer", EthicsAuthorization.PRODUCT_OBJECT, org.toString()))
                .thenReturn(new OpenFgaAuthzService.CheckResult(true, "granted"));

        assertThat(authorization.isProductMember(target, org)).isTrue();
    }

    @Test
    void aSubjectFromAnotherOrgIsNotAssignableHere() {
        UUID otherOrg = UUID.randomUUID();
        when(openFga.checkNoCacheResult(eq(target), anyString(), anyString(), eq(org.toString())))
                .thenReturn(new OpenFgaAuthzService.CheckResult(false, "no_relation"));
        when(openFga.checkNoCacheResult(eq(target), anyString(), anyString(), eq(otherOrg.toString())))
                .thenReturn(new OpenFgaAuthzService.CheckResult(true, "granted"));

        assertThat(authorization.isProductMember(target, org))
                .as("membership in a different tenant must not make someone assignable here")
                .isFalse();
    }

    /**
     * An unreadable policy engine must answer "not a member". Refusing a legitimate assignment is
     * recoverable; recording a participant nobody verified is not — the row outlives the outage and
     * is read later as though it had been checked.
     */
    @Test
    void anUnreadablePolicyEngineMeansNotAMember() {
        when(openFga.checkNoCacheResult(anyString(), anyString(), anyString(), anyString()))
                .thenThrow(new IllegalStateException("synthetic outage"));

        assertThat(authorization.isProductMember(target, org)).isFalse();
    }

    @Test
    void aDisabledPolicyEngineMeansNotAMember() {
        OpenFgaProperties disabled = new OpenFgaProperties();
        disabled.setEnabled(false);
        assertThat(new EthicsAuthorization(openFga, disabled).isProductMember(target, org)).isFalse();
        verifyNoInteractions(openFga);
    }

    @Test
    void aMissingSubjectOrOrgIsNotAMember() {
        assertThat(authorization.isProductMember(null, org)).isFalse();
        assertThat(authorization.isProductMember(target, null)).isFalse();
        verifyNoInteractions(openFga);
    }

    private static OpenFgaProperties enabledProperties() {
        var value = new OpenFgaProperties();
        value.setEnabled(true);
        value.setStoreId("test-store");
        value.setModelId("test-model");
        return value;
    }
}
