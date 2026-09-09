package com.example.schema.config;

import com.example.commonauth.scope.AuthzVersionProvider;
import com.example.schema.config.AuthzMeClient.AuthzMeResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * gitops#3608 — the gate is the frontend's {@code hasModule("REPORT")} applied to
 * permission-service's own projection, fails closed on every non-answer, and
 * memoises per token only while the authorization revision stands.
 */
class ReportModuleAccessGateTest {

    private static final String TOKEN = "eyJ.token.one";

    private final AtomicReference<AuthzMeResult> next = new AtomicReference<>();
    private final AtomicInteger calls = new AtomicInteger();
    private final AtomicLong revision = new AtomicLong(7L);
    private final AuthzMeClient client = token -> {
        calls.incrementAndGet();
        return next.get();
    };
    private final AuthzVersionProvider versions = revision::get;

    private ReportModuleAccessGate gate;

    @BeforeEach
    void setUp() {
        gate = new ReportModuleAccessGate(client, versions, true, false, Duration.ofMinutes(1));
    }

    private static AuthzMeResult projection(boolean superAdmin, Map<String, String> modules, List<String> legacy) {
        return AuthzMeResult.ok(superAdmin, modules, legacy, 7L);
    }

    @Test
    void reportViewAllows() {
        next.set(projection(false, Map.of("REPORT", "VIEW"), List.of()));
        var d = gate.decide(TOKEN);
        assertThat(d.allowed()).isTrue();
        assertThat(d.reason()).isEqualTo("module_view");
    }

    @Test
    void reportManageAllows() {
        next.set(projection(false, Map.of("REPORT", "MANAGE", "THEME", "VIEW"), List.of()));
        assertThat(gate.decide(TOKEN).reason()).isEqualTo("module_manage");
    }

    @Test
    void otherModulesOnlyDenies() {
        next.set(projection(false, Map.of("THEME", "MANAGE"), List.of()));
        var d = gate.decide(TOKEN);
        assertThat(d.allowed()).isFalse();
        assertThat(d.reason()).isEqualTo("no_report_module");
    }

    @Test
    void explicitDenyLevelDenies() {
        next.set(projection(false, Map.of("REPORT", "DENY"), List.of()));
        var d = gate.decide(TOKEN);
        assertThat(d.allowed()).isFalse();
        assertThat(d.reason()).isEqualTo("report_module_deny");
    }

    @Test
    void superAdminAllowsRegardlessOfModules() {
        next.set(projection(true, Map.of(), List.of()));
        assertThat(gate.decide(TOKEN).reason()).isEqualTo("super_admin");
    }

    @Test
    void legacyAllowedModulesOnlyConsultedWhenModulesMapIsEmpty() {
        next.set(projection(false, Map.of(), List.of("REPORT")));
        assertThat(gate.decide(TOKEN).reason()).isEqualTo("legacy_allowed_modules");

        gate.invalidateAll();
        next.set(projection(false, Map.of("THEME", "VIEW"), List.of("REPORT")));
        assertThat(gate.decide(TOKEN).allowed()).as("modules map present wins over legacy list").isFalse();
    }

    @Test
    void refusedTokenDenies() {
        next.set(AuthzMeResult.rejected(401));
        var d = gate.decide(TOKEN);
        assertThat(d.allowed()).isFalse();
        assertThat(d.reason()).isEqualTo("authz_me_http_401");
    }

    @Test
    void unavailablePermissionServiceDeniesNotAllows() {
        next.set(AuthzMeResult.unavailable("transport:ConnectException"));
        var d = gate.decide(TOKEN);
        assertThat(d.allowed()).isFalse();
        assertThat(d.reason()).isEqualTo("authz_unavailable:transport:ConnectException");
    }

    @Test
    void decisionIsMemoisedPerTokenWhileTheRevisionStands() {
        next.set(projection(false, Map.of("REPORT", "VIEW"), List.of()));
        gate.decide(TOKEN);
        gate.decide(TOKEN);
        assertThat(calls.get()).isEqualTo(1);

        gate.decide("eyJ.token.two");
        assertThat(calls.get()).as("a different token is a different caller").isEqualTo(2);
    }

    @Test
    void revisionBumpInvalidatesTheMemo() {
        next.set(projection(false, Map.of("REPORT", "VIEW"), List.of()));
        assertThat(gate.decide(TOKEN).allowed()).isTrue();

        revision.set(8L);
        next.set(projection(false, Map.of(), List.of()));
        assertThat(gate.decide(TOKEN).allowed()).as("revoke shows up after the revision moves").isFalse();
        assertThat(calls.get()).isEqualTo(2);
    }

    @Test
    void denialsAreMemoisedToo() {
        next.set(AuthzMeResult.rejected(403));
        gate.decide(TOKEN);
        gate.decide(TOKEN);
        assertThat(calls.get()).isEqualTo(1);
    }

    @Test
    void blankTokenDeniesWithoutCalling() {
        assertThat(gate.decide(" ").reason()).isEqualTo("no_token");
        assertThat(gate.decide(null).allowed()).isFalse();
        assertThat(calls.get()).isZero();
    }

    @Test
    void disabledGateOnlyPassesThroughInDevProfiles() {
        var dev = new ReportModuleAccessGate(client, versions, false, true, Duration.ofSeconds(1));
        assertThat(dev.decide(TOKEN).reason()).isEqualTo("gate_disabled_dev");

        var nonDev = new ReportModuleAccessGate(client, versions, false, false, Duration.ofSeconds(1));
        var d = nonDev.decide(TOKEN);
        assertThat(d.allowed()).isFalse();
        assertThat(d.reason()).isEqualTo("gate_disabled");
        assertThat(calls.get()).isZero();
    }

    @Test
    void tokenKeyIsADigestNotTheToken() {
        String key = ReportModuleAccessGate.tokenKey(TOKEN);
        assertThat(key).hasSize(64).doesNotContain(TOKEN);
        assertThat(ReportModuleAccessGate.tokenKey(TOKEN)).isEqualTo(key);
    }
}
