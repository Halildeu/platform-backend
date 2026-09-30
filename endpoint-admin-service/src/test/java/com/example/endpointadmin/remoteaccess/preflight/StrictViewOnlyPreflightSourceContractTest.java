package com.example.endpointadmin.remoteaccess.preflight;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.junit.jupiter.api.Test;

class StrictViewOnlyPreflightSourceContractTest {
    @Test
    void bindsEveryCheckToOneExactSourceAdapter() {
        assertThat(Map.ofEntries(
                Map.entry("targetIdentity", StrictViewOnlyPreflightEnvelopeVerifier.requiredSourceForCheck("targetIdentity")),
                Map.entry("pkceAuthorizationCode", StrictViewOnlyPreflightEnvelopeVerifier.requiredSourceForCheck("pkceAuthorizationCode")),
                Map.entry("tokenRefresh", StrictViewOnlyPreflightEnvelopeVerifier.requiredSourceForCheck("tokenRefresh")),
                Map.entry("routeApi", StrictViewOnlyPreflightEnvelopeVerifier.requiredSourceForCheck("routeApi")),
                Map.entry("browserConsole", StrictViewOnlyPreflightEnvelopeVerifier.requiredSourceForCheck("browserConsole")),
                Map.entry("replayIsolation", StrictViewOnlyPreflightEnvelopeVerifier.requiredSourceForCheck("replayIsolation")),
                Map.entry("clusterContext", StrictViewOnlyPreflightEnvelopeVerifier.requiredSourceForCheck("clusterContext")),
                Map.entry("portsTunnels", StrictViewOnlyPreflightEnvelopeVerifier.requiredSourceForCheck("portsTunnels")),
                Map.entry("imageDigests", StrictViewOnlyPreflightEnvelopeVerifier.requiredSourceForCheck("imageDigests")),
                Map.entry("policyMask", StrictViewOnlyPreflightEnvelopeVerifier.requiredSourceForCheck("policyMask")),
                Map.entry("runnerCapacity", StrictViewOnlyPreflightEnvelopeVerifier.requiredSourceForCheck("runnerCapacity")),
                Map.entry("watchdogRollback", StrictViewOnlyPreflightEnvelopeVerifier.requiredSourceForCheck("watchdogRollback"))))
                .containsExactlyInAnyOrderEntriesOf(Map.ofEntries(
                        Map.entry("targetIdentity", "attestor-runtime"),
                        Map.entry("pkceAuthorizationCode", "browser-fixed-function"),
                        Map.entry("tokenRefresh", "browser-fixed-function"),
                        Map.entry("routeApi", "browser-fixed-function"),
                        Map.entry("browserConsole", "browser-fixed-function"),
                        Map.entry("replayIsolation", "browser-fixed-function"),
                        Map.entry("clusterContext", "kubernetes-readonly"),
                        Map.entry("portsTunnels", "attestor-runtime"),
                        Map.entry("imageDigests", "kubernetes-readonly"),
                        Map.entry("policyMask", "policy-bundle"),
                        Map.entry("runnerCapacity", "github-api"),
                        Map.entry("watchdogRollback", "remote-bridge-device-channel")));
    }

    @Test
    void rejectsUnknownCheckInsteadOfFallingBackToAGenericSource() {
        assertThatThrownBy(() -> StrictViewOnlyPreflightEnvelopeVerifier.requiredSourceForCheck("unknown"))
                .isInstanceOf(ViewOnlyAuthorityException.class)
                .hasMessageContaining("no source authority");
    }
}
