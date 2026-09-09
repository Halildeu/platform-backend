package com.example.schema.config;

import com.example.schema.config.AuthzMeClient.AuthzMeResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * gitops#3608 — the gate is the frontend's {@code hasModule("REPORT")} applied to
 * permission-service's own projection, fails closed on every non-answer, and
 * memoises per token only while a freshly-read authorization revision stands.
 */
class ReportModuleAccessGateTest {

    private static final String TOKEN = "eyJ.token.one";

    /** Scripted permission-service: what /me and /version answer, and how often each was asked. */
    static final class FakeAuthz implements AuthzMeClient {
        AuthzMeResult me = AuthzMeResult.ok(false, Map.of("REPORT", "VIEW"), List.of(), 7L);
        OptionalLong version = OptionalLong.of(7L);
        int meCalls;
        int versionCalls;
        String lastVersionBearer;

        @Override
        public AuthzMeResult fetch(String bearerToken) {
            meCalls++;
            return me;
        }

        @Override
        public OptionalLong fetchVersion(String bearerToken) {
            versionCalls++;
            lastVersionBearer = bearerToken;
            return version;
        }
    }

    private final FakeAuthz authz = new FakeAuthz();
    private final AtomicLong clock = new AtomicLong(1_000_000_000L);
    private ReportModuleAccessGate gate;

    @BeforeEach
    void setUp() {
        gate = new ReportModuleAccessGate(authz, true, false, Duration.ofMinutes(1), Duration.ofSeconds(5), clock::get);
    }

    private static AuthzMeResult projection(boolean superAdmin, Map<String, String> modules, List<String> legacy) {
        return AuthzMeResult.ok(superAdmin, modules, legacy, 7L);
    }

    private void advance(Duration d) {
        clock.addAndGet(d.toNanos());
    }

    @Test
    void reportViewAllows() {
        authz.me = projection(false, Map.of("REPORT", "VIEW"), List.of());
        var d = gate.decide(TOKEN);
        assertThat(d.allowed()).isTrue();
        assertThat(d.reason()).isEqualTo("module_view");
    }

    @Test
    void reportManageAllows() {
        authz.me = projection(false, Map.of("REPORT", "MANAGE", "THEME", "VIEW"), List.of());
        assertThat(gate.decide(TOKEN).reason()).isEqualTo("module_manage");
    }

    @Test
    void otherModulesOnlyDenies() {
        authz.me = projection(false, Map.of("THEME", "MANAGE"), List.of());
        var d = gate.decide(TOKEN);
        assertThat(d.allowed()).isFalse();
        assertThat(d.reason()).isEqualTo("no_report_module");
    }

    @Test
    void explicitDenyLevelDenies() {
        authz.me = projection(false, Map.of("REPORT", "DENY"), List.of());
        var d = gate.decide(TOKEN);
        assertThat(d.allowed()).isFalse();
        assertThat(d.reason()).isEqualTo("report_module_deny");
    }

    @Test
    void superAdminAllowsRegardlessOfModules() {
        authz.me = projection(true, Map.of(), List.of());
        assertThat(gate.decide(TOKEN).reason()).isEqualTo("super_admin");
    }

    @Test
    void legacyAllowedModulesOnlyConsultedWhenModulesMapIsEmpty() {
        authz.me = projection(false, Map.of(), List.of("REPORT"));
        assertThat(gate.decide(TOKEN).reason()).isEqualTo("legacy_allowed_modules");

        gate.invalidateAll();
        authz.me = projection(false, Map.of("THEME", "VIEW"), List.of("REPORT"));
        assertThat(gate.decide(TOKEN).allowed()).as("modules map present wins over legacy list").isFalse();
    }

    @Test
    void refusedTokenDenies() {
        authz.me = AuthzMeResult.rejected(401);
        var d = gate.decide(TOKEN);
        assertThat(d.allowed()).isFalse();
        assertThat(d.reason()).isEqualTo("authz_me_http_401");
    }

    @Test
    void unavailablePermissionServiceDeniesNotAllows() {
        authz.me = AuthzMeResult.unavailable("transport:ConnectException");
        var d = gate.decide(TOKEN);
        assertThat(d.allowed()).isFalse();
        assertThat(d.reason()).isEqualTo("authz_unavailable:transport:ConnectException");
    }

    @Test
    void identityLessProjectionDenies() {
        authz.me = AuthzMeResult.unavailable("no_identity");
        assertThat(gate.decide(TOKEN).reason()).isEqualTo("authz_unavailable:no_identity");
    }

    @Test
    void decisionIsMemoisedPerTokenWhileTheRevisionStands() {
        gate.decide(TOKEN);
        gate.decide(TOKEN);
        assertThat(authz.meCalls).isEqualTo(1);

        gate.decide("eyJ.token.two");
        assertThat(authz.meCalls).as("a different token is a different caller").isEqualTo(2);
    }

    @Test
    void revisionIsReadWithTheCallersBearerAndMemoisedForTheWindow() {
        gate.decide(TOKEN);
        assertThat(authz.lastVersionBearer).isEqualTo(TOKEN);
        assertThat(authz.versionCalls).isEqualTo(1);

        advance(Duration.ofSeconds(4));
        gate.decide(TOKEN);
        assertThat(authz.versionCalls).as("inside the memo window").isEqualTo(1);

        advance(Duration.ofSeconds(2));
        gate.decide(TOKEN);
        assertThat(authz.versionCalls).as("window elapsed → re-read").isEqualTo(2);
        assertThat(authz.meCalls).as("same revision → decision reused").isEqualTo(1);
    }

    @Test
    void revisionBumpInvalidatesTheMemo() {
        assertThat(gate.decide(TOKEN).allowed()).isTrue();

        advance(Duration.ofSeconds(6));
        authz.version = OptionalLong.of(8L);
        authz.me = projection(false, Map.of(), List.of());
        assertThat(gate.decide(TOKEN).allowed()).as("revoke shows up after the revision moves").isFalse();
        assertThat(authz.meCalls).isEqualTo(2);
    }

    @Test
    void unreadableRevisionBypassesTheMemoInsteadOfTrustingTheOldOne() {
        assertThat(gate.decide(TOKEN).allowed()).isTrue();
        assertThat(authz.meCalls).isEqualTo(1);

        advance(Duration.ofSeconds(6));
        authz.version = OptionalLong.empty();
        authz.me = projection(false, Map.of(), List.of());
        assertThat(gate.decide(TOKEN).allowed()).as("fresh /me decides, cached allow is not reused").isFalse();
        assertThat(authz.meCalls).isEqualTo(2);

        gate.decide(TOKEN);
        assertThat(authz.meCalls).as("nothing is memoised while the revision is unreadable").isEqualTo(3);
        assertThat(authz.versionCalls).as("a failed read is not memoised either").isEqualTo(3);
    }

    @Test
    void denialsAreMemoisedToo() {
        authz.me = AuthzMeResult.rejected(403);
        gate.decide(TOKEN);
        gate.decide(TOKEN);
        assertThat(authz.meCalls).isEqualTo(1);
    }

    @Test
    void blankTokenDeniesWithoutCalling() {
        assertThat(gate.decide(" ").reason()).isEqualTo("no_token");
        assertThat(gate.decide(null).allowed()).isFalse();
        assertThat(authz.meCalls).isZero();
        assertThat(authz.versionCalls).isZero();
    }

    @Test
    void disabledGateOnlyPassesThroughInDevProfiles() {
        var dev = new ReportModuleAccessGate(authz, false, true, Duration.ofSeconds(1), Duration.ofSeconds(1));
        assertThat(dev.decide(TOKEN).reason()).isEqualTo("gate_disabled_dev");

        var nonDev = new ReportModuleAccessGate(authz, false, false, Duration.ofSeconds(1), Duration.ofSeconds(1));
        var d = nonDev.decide(TOKEN);
        assertThat(d.allowed()).isFalse();
        assertThat(d.reason()).isEqualTo("gate_disabled");
        assertThat(authz.meCalls).isZero();
    }

    @Test
    void tokenKeyIsADigestNotTheToken() {
        String key = ReportModuleAccessGate.tokenKey(TOKEN);
        assertThat(key).hasSize(64).doesNotContain(TOKEN);
        assertThat(ReportModuleAccessGate.tokenKey(TOKEN)).isEqualTo(key);
    }
}
