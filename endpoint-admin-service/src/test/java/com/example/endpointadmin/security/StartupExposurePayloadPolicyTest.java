package com.example.endpointadmin.security;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * BE — unit tests for {@link StartupExposurePayloadPolicy} (Faz 22.5,
 * AG-040-be). Mirrors AG-039-be {@code ServicesPayloadPolicyTest}.
 *
 * <p>Codex 019e8387 plan iter-1 AGREE invariants pinned:
 * <ul>
 *   <li>10-anchor location enum (no full executable path leaks)</li>
 *   <li>name full-path denylist (drive letter / UNC / unix path / exe ext)</li>
 *   <li>RDP active-sessions / sessionCount FORBIDDEN at top-level</li>
 *   <li>Cap 50 startup apps</li>
 *   <li>probeOrigin REGISTRY|SCHEDULED_TASK enum</li>
 *   <li>probeErrors source allowlist + SUMMARY_VALUE_DENYLIST_RE reuse</li>
 *   <li>Type-confusion bypass closed via sanitize() hook</li>
 * </ul>
 */
class StartupExposurePayloadPolicyTest {

    private final StartupExposurePayloadPolicy policy = new StartupExposurePayloadPolicy();

    // ─── 1. Golden ────────────────────────────────────────────────

    @Test
    void goldenWindowsOk() {
        var p = policy.projectAndHash(goldenWindows());
        assertThat(p.schemaVersion()).isEqualTo(1);
        assertThat(p.supported()).isTrue();
        assertThat(p.probeComplete()).isTrue();
        assertThat(p.rdpEnabled()).isFalse();
        assertThat(p.windowsFirewallEventLogEnabled()).isTrue();
        assertThat(p.startupApps()).hasSize(3);
        assertThat(p.startupApps().get(0).name()).isEqualTo("OneDrive");
        assertThat(p.startupApps().get(0).location()).isEqualTo("HKLM_RUN");
        assertThat(p.startupApps().get(0).probeOrigin()).isEqualTo("REGISTRY");
        assertThat(p.startupApps().get(2).location()).isEqualTo("TASK_SCHEDULER:CUSTOM");
        assertThat(p.startupApps().get(2).probeOrigin()).isEqualTo("SCHEDULED_TASK");
        assertThat(p.probeErrors()).isEmpty();
        assertThat(p.payloadHashSha256()).matches("^[0-9a-f]{64}$");
    }

    @Test
    void goldenUnsupportedEmptyAppsOk() {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("schemaVersion", 1);
        p.put("supported", false);
        p.put("probeComplete", false);
        p.put("rdpEnabled", false);
        p.put("windowsFirewallEventLogEnabled", false);
        p.put("startupApps", List.of());
        p.put("probeDurationMs", 0);
        p.put("probeErrors", List.of(Map.of("code", "UNSUPPORTED_PLATFORM")));
        var proj = policy.projectAndHash(p);
        assertThat(proj.supported()).isFalse();
        assertThat(proj.startupApps()).isEmpty();
        assertThat(proj.probeErrors()).hasSize(1);
    }

    @Test
    void goldenZeroAppsAllowedWhenSupported() {
        Map<String, Object> p = goldenWindows();
        p.put("startupApps", List.of());
        var proj = policy.projectAndHash(p);
        assertThat(proj.startupApps()).isEmpty();
        assertThat(proj.probeComplete()).isTrue();
    }

    // ─── 2. Strict allowlist ─────────────────────────────────────

    @Test
    void unknownTopLevelKeyRejected() {
        Map<String, Object> p = goldenWindows();
        p.put("futureField", 1);
        assertThatThrownBy(() -> policy.projectAndHash(p))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unknown top-level key");
    }

    @Test
    void payloadLevelCollectedAtRejected() {
        Map<String, Object> p = goldenWindows();
        p.put("collectedAt", "2026-06-01T12:00:00Z");
        assertThatThrownBy(() -> policy.projectAndHash(p))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void forbiddenApiUrlRejected() {
        Map<String, Object> p = goldenWindows();
        p.put("apiURL", "https://leak");
        assertThatThrownBy(() -> policy.projectAndHash(p))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("forbidden key");
    }

    @Test
    void forbiddenActiveSessionsRejected() {
        // Codex 019e8387 plan iter-1 P1 #2 absorb: RDP active sessions
        // count is a usage telemetry leak. Forbid hard.
        Map<String, Object> p = goldenWindows();
        p.put("activeSessions", 3);
        assertThatThrownBy(() -> policy.projectAndHash(p))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("forbidden key: activeSessions");
    }

    @Test
    void forbiddenRdpActiveSessionsRejected() {
        Map<String, Object> p = goldenWindows();
        p.put("rdpActiveSessions", 2);
        assertThatThrownBy(() -> policy.projectAndHash(p))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("forbidden key");
    }

    @Test
    void forbiddenExecutablePathRejected() {
        Map<String, Object> p = goldenWindows();
        p.put("executable", "C:\\Program Files\\foo.exe");
        assertThatThrownBy(() -> policy.projectAndHash(p))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("forbidden key");
    }

    // ─── 3. Required keys ────────────────────────────────────────

    @Test
    void missingStartupAppsRejected() {
        Map<String, Object> p = goldenWindows();
        p.remove("startupApps");
        assertThatThrownBy(() -> policy.projectAndHash(p))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("missing required key: startupApps");
    }

    @Test
    void missingRdpEnabledRejected() {
        Map<String, Object> p = goldenWindows();
        p.remove("rdpEnabled");
        assertThatThrownBy(() -> policy.projectAndHash(p))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("missing required key: rdpEnabled");
    }

    @Test
    void missingWindowsFirewallEventLogEnabledRejected() {
        Map<String, Object> p = goldenWindows();
        p.remove("windowsFirewallEventLogEnabled");
        assertThatThrownBy(() -> policy.projectAndHash(p))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("missing required key");
    }

    @Test
    void missingProbeDurationMsRejected() {
        Map<String, Object> p = goldenWindows();
        p.remove("probeDurationMs");
        assertThatThrownBy(() -> policy.projectAndHash(p))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("missing required key: probeDurationMs");
    }

    // ─── 4. Type contracts ───────────────────────────────────────

    @Test
    void rdpEnabledStringRejected() {
        Map<String, Object> p = goldenWindows();
        p.put("rdpEnabled", "false");
        assertThatThrownBy(() -> policy.projectAndHash(p))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must be a Boolean");
    }

    @Test
    void schemaVersionNonOneRejected() {
        Map<String, Object> p = goldenWindows();
        p.put("schemaVersion", 2);
        assertThatThrownBy(() -> policy.projectAndHash(p))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unsupported schemaVersion");
    }

    @Test
    void probeDurationOutOfRangeRejected() {
        Map<String, Object> p = goldenWindows();
        p.put("probeDurationMs", 200000);
        assertThatThrownBy(() -> policy.projectAndHash(p))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("probeDurationMs out of range");
    }

    // ─── 5. StartupApps validation ───────────────────────────────

    @Test
    void appUnknownKeyRejected() {
        Map<String, Object> p = goldenWindows();
        List<Map<String, Object>> apps = mutableAppsList(p);
        apps.get(0).put("commandLine", "foo.exe /silent");
        p.put("startupApps", apps);
        assertThatThrownBy(() -> policy.projectAndHash(p))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unknown key");
    }

    @Test
    void appLocationNotInAllowlistRejected() {
        Map<String, Object> p = goldenWindows();
        List<Map<String, Object>> apps = mutableAppsList(p);
        apps.get(0).put("location", "HKEY_LOCAL_MACHINE\\SOFTWARE\\Microsoft\\Windows\\CurrentVersion\\Run");
        p.put("startupApps", apps);
        assertThatThrownBy(() -> policy.projectAndHash(p))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("canonical anchor allowlist");
    }

    @Test
    void appNameWithDriveLetterRejected() {
        // Codex 019e8387 plan iter-1 P1 #1 absorb: full executable
        // path MUST never appear in name (we only carry the registry
        // value name / task name / folder basename).
        Map<String, Object> p = goldenWindows();
        List<Map<String, Object>> apps = mutableAppsList(p);
        apps.get(0).put("name", "C:\\Program Files\\OneDrive\\OneDrive.exe");
        p.put("startupApps", apps);
        assertThatThrownBy(() -> policy.projectAndHash(p))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("forbidden value pattern");
    }

    @Test
    void appNameWithExeExtensionRejected() {
        Map<String, Object> p = goldenWindows();
        List<Map<String, Object>> apps = mutableAppsList(p);
        apps.get(0).put("name", "OneDrive.exe");
        p.put("startupApps", apps);
        assertThatThrownBy(() -> policy.projectAndHash(p))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("forbidden value pattern");
    }

    @Test
    void appNameWithUncPathRejected() {
        Map<String, Object> p = goldenWindows();
        List<Map<String, Object>> apps = mutableAppsList(p);
        apps.get(0).put("name", "\\\\server\\share\\foo");
        p.put("startupApps", apps);
        assertThatThrownBy(() -> policy.projectAndHash(p))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("forbidden value pattern");
    }

    @Test
    void appProbeOriginInvalidRejected() {
        Map<String, Object> p = goldenWindows();
        List<Map<String, Object>> apps = mutableAppsList(p);
        apps.get(0).put("probeOrigin", "WMI");
        p.put("startupApps", apps);
        assertThatThrownBy(() -> policy.projectAndHash(p))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("probeOrigin must be in");
    }

    @Test
    void appNameWithControlCharRejected() {
        Map<String, Object> p = goldenWindows();
        List<Map<String, Object>> apps = mutableAppsList(p);
        apps.get(0).put("name", "OneDriveBeep");
        p.put("startupApps", apps);
        assertThatThrownBy(() -> policy.projectAndHash(p))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("control character");
    }

    @Test
    void appsExceedingCapRejected() {
        Map<String, Object> p = goldenWindows();
        List<Map<String, Object>> apps = new ArrayList<>();
        for (int i = 0; i < StartupExposurePayloadPolicy.STARTUP_APPS_MAX + 1; i++) {
            apps.add(makeApp("App" + i, "HKLM_RUN", true, "REGISTRY"));
        }
        p.put("startupApps", apps);
        assertThatThrownBy(() -> policy.projectAndHash(p))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("exceeds cap");
    }

    @Test
    void appsAtCapAccepted() {
        Map<String, Object> p = goldenWindows();
        List<Map<String, Object>> apps = new ArrayList<>();
        for (int i = 0; i < StartupExposurePayloadPolicy.STARTUP_APPS_MAX; i++) {
            apps.add(makeApp("App" + i, "HKLM_RUN", true, "REGISTRY"));
        }
        p.put("startupApps", apps);
        var proj = policy.projectAndHash(p);
        assertThat(proj.startupApps()).hasSize(StartupExposurePayloadPolicy.STARTUP_APPS_MAX);
    }

    // ─── 6. ProbeErrors validation ───────────────────────────────

    @Test
    void probeErrorsAbsentAccepted() {
        Map<String, Object> p = goldenWindows();
        // probeErrors is already absent.
        var proj = policy.projectAndHash(p);
        assertThat(proj.probeErrors()).isEmpty();
    }

    @Test
    void probeErrorsExplicitNullRejected() {
        Map<String, Object> p = goldenWindows();
        p.put("probeErrors", null);
        assertThatThrownBy(() -> policy.projectAndHash(p))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must be a List or omitted, not null");
    }

    @Test
    void probeErrorsCodeNotInEnumRejected() {
        Map<String, Object> p = goldenWindows();
        p.put("probeErrors", List.of(Map.of("code", "BAD_CODE")));
        assertThatThrownBy(() -> policy.projectAndHash(p))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("code must be in");
    }

    @Test
    void probeErrorSourceNotInAllowlistRejected() {
        Map<String, Object> p = goldenWindows();
        p.put("probeErrors", List.of(Map.of(
                "code", "REGISTRY_QUERY_FAILED",
                "source", "HKCR_Run")));
        assertThatThrownBy(() -> policy.projectAndHash(p))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("source must be in canonical anchor allowlist");
    }

    @Test
    void probeErrorSummaryUrlDenylistRejected() {
        // SUMMARY_VALUE_DENYLIST_RE reuse from AG-038-be — URL pattern.
        Map<String, Object> p = goldenWindows();
        p.put("probeErrors", List.of(Map.of(
                "code", "RDP_PROBE_FAILED",
                "summary", "GET https://attacker.example/exfil failed")));
        assertThatThrownBy(() -> policy.projectAndHash(p))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("forbidden value pattern");
    }

    @Test
    void probeErrorSummaryBearerDenylistRejected() {
        Map<String, Object> p = goldenWindows();
        p.put("probeErrors", List.of(Map.of(
                "code", "FIREWALL_PROBE_FAILED",
                "summary", "auth header bearer ABC123 invalid")));
        assertThatThrownBy(() -> policy.projectAndHash(p))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("forbidden value pattern");
    }

    @Test
    void probeErrorSummaryIpDenylistRejected() {
        Map<String, Object> p = goldenWindows();
        p.put("probeErrors", List.of(Map.of(
                "code", "REGISTRY_QUERY_FAILED",
                "summary", "Connect to 192.168.1.10 timed out")));
        assertThatThrownBy(() -> policy.projectAndHash(p))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("forbidden value pattern");
    }

    @Test
    void probeErrorSummaryCrlfRejected() {
        Map<String, Object> p = goldenWindows();
        p.put("probeErrors", List.of(Map.of(
                "code", "RDP_PROBE_FAILED",
                "summary", "First line\nSecond line")));
        assertThatThrownBy(() -> policy.projectAndHash(p))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("control character");
    }

    @Test
    void probeErrorSummaryTooLongRejected() {
        Map<String, Object> p = goldenWindows();
        StringBuilder big = new StringBuilder();
        for (int i = 0; i < 250; i++) big.append("x");
        p.put("probeErrors", List.of(Map.of(
                "code", "RDP_PROBE_FAILED",
                "summary", big.toString())));
        assertThatThrownBy(() -> policy.projectAndHash(p))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("length cap");
    }

    // ─── 7. Sanitize hook (type-confusion bypass) ────────────────

    @Test
    void sanitizeRejectsNonMapStartupExposureBlock() {
        // If the agent ships startupExposure as a List or scalar by
        // mistake, this is a type-confusion attempt the generic policy
        // would miss; sanitize MUST reject hard.
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("startupExposure", List.of("oops"));
        assertThatThrownBy(() -> policy.sanitize(details))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must be a Map or absent");
    }

    @Test
    void sanitizeAcceptsAbsentBlock() {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("software", Map.of("apps", List.of()));
        assertThatCode(() -> policy.sanitize(details)).doesNotThrowAnyException();
    }

    @Test
    void sanitizeAcceptsInventoryNestedBlock() {
        Map<String, Object> inv = new LinkedHashMap<>();
        inv.put("startupExposure", goldenWindows());
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("inventory", inv);
        assertThatCode(() -> policy.sanitize(details)).doesNotThrowAnyException();
    }

    @Test
    void sanitizeRejectsTypeConfusionNestedBlock() {
        Map<String, Object> inv = new LinkedHashMap<>();
        inv.put("startupExposure", "not-a-map");
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("inventory", inv);
        assertThatThrownBy(() -> policy.sanitize(details))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must be a Map or absent");
    }

    // ─── 8. Canonical hash stability ─────────────────────────────

    @Test
    void canonicalHashStableAcrossReorder() {
        Map<String, Object> a = goldenWindows();
        Map<String, Object> b = new LinkedHashMap<>();
        // Different insertion order — same logical content.
        b.put("probeDurationMs", a.get("probeDurationMs"));
        b.put("startupApps", a.get("startupApps"));
        b.put("windowsFirewallEventLogEnabled", a.get("windowsFirewallEventLogEnabled"));
        b.put("rdpEnabled", a.get("rdpEnabled"));
        b.put("probeComplete", a.get("probeComplete"));
        b.put("supported", a.get("supported"));
        b.put("schemaVersion", a.get("schemaVersion"));
        var pa = policy.projectAndHash(a);
        var pb = policy.projectAndHash(b);
        assertThat(pa.payloadHashSha256()).isEqualTo(pb.payloadHashSha256());
    }

    @Test
    void canonicalHashChangesOnRdpFlip() {
        Map<String, Object> a = goldenWindows();
        Map<String, Object> b = new LinkedHashMap<>(a);
        b.put("rdpEnabled", true);
        var pa = policy.projectAndHash(a);
        var pb = policy.projectAndHash(b);
        assertThat(pa.payloadHashSha256()).isNotEqualTo(pb.payloadHashSha256());
    }

    // ─── Helpers ─────────────────────────────────────────────────

    private static Map<String, Object> goldenWindows() {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("schemaVersion", 1);
        p.put("supported", true);
        p.put("probeComplete", true);
        p.put("rdpEnabled", false);
        p.put("windowsFirewallEventLogEnabled", true);
        List<Map<String, Object>> apps = new ArrayList<>();
        apps.add(makeApp("OneDrive", "HKLM_RUN", true, "REGISTRY"));
        apps.add(makeApp("Slack", "HKCU_RUN", true, "REGISTRY"));
        apps.add(makeApp("UpdateOrchestrator", "TASK_SCHEDULER:CUSTOM", true, "SCHEDULED_TASK"));
        p.put("startupApps", apps);
        p.put("probeDurationMs", 250);
        return p;
    }

    private static Map<String, Object> makeApp(String name, String location, boolean enabled, String origin) {
        Map<String, Object> a = new LinkedHashMap<>();
        a.put("name", name);
        a.put("location", location);
        a.put("enabled", enabled);
        a.put("probeOrigin", origin);
        return a;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> mutableAppsList(Map<String, Object> p) {
        Object raw = p.get("startupApps");
        List<Map<String, Object>> rawList = (List<Map<String, Object>>) raw;
        List<Map<String, Object>> mutable = new ArrayList<>();
        for (Map<String, Object> entry : rawList) {
            mutable.add(new LinkedHashMap<>(entry));
        }
        return mutable;
    }
}
