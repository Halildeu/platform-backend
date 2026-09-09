package com.example.schema.config;

import com.example.commonauth.openfga.OpenFgaAuthzService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * gitops#3608 — the gate must agree with permission-service's
 * {@code /authz/me.modules.REPORT} projection and fail closed when OpenFGA
 * cannot answer.
 */
class ReportModuleAccessGateTest {

    private static final String USER = "42";

    private OpenFgaAuthzService fga;
    private ReportModuleAccessGate gate;

    @BeforeEach
    void setUp() {
        fga = mock(OpenFgaAuthzService.class);
        when(fga.isEnabled()).thenReturn(true);
        when(fga.check(any(), any(), any(), any())).thenReturn(false);
        when(fga.listObjectsResult(any(), any(), any()))
                .thenReturn(new OpenFgaAuthzService.ObjectListResult(true, List.of(), "no_objects"));
        gate = new ReportModuleAccessGate(fga);
    }

    @Test
    void explicitModuleGrantAllows() {
        when(fga.check(USER, "can_view", "module", "REPORT")).thenReturn(true);

        var d = gate.decide(USER);

        assertThat(d.allowed()).isTrue();
        assertThat(d.reason()).isEqualTo("module_grant");
        verify(fga, never()).listObjectsResult(any(), any(), any());
    }

    @Test
    void noGrantAnywhereDenies() {
        var d = gate.decide(USER);

        assertThat(d.allowed()).isFalse();
        assertThat(d.reason()).isEqualTo("no_report_grant");
        verify(fga).listObjectsResult(USER, "can_view", "report");
        verify(fga).listObjectsResult(USER, "can_view", "report_group");
    }

    @Test
    void reportLevelGrantSurfacesTheModuleLikeAuthzMeDoes() {
        when(fga.listObjectsResult(USER, "can_view", "report"))
                .thenReturn(new OpenFgaAuthzService.ObjectListResult(true, List.of("FIN_ANALYTICS"), "ok"));

        var d = gate.decide(USER);

        assertThat(d.allowed()).isTrue();
        assertThat(d.reason()).isEqualTo("derived:report");
    }

    @Test
    void reportGroupGrantSurfacesTheModuleToo() {
        when(fga.listObjectsResult(USER, "can_view", "report_group"))
                .thenReturn(new OpenFgaAuthzService.ObjectListResult(true, List.of("FINANCE_REPORTS"), "ok"));

        var d = gate.decide(USER);

        assertThat(d.allowed()).isTrue();
        assertThat(d.reason()).isEqualTo("derived:report_group");
    }

    @Test
    void explicitModuleDenyWinsOverReportGrants() {
        when(fga.check(USER, "blocked", "module", "REPORT")).thenReturn(true);
        when(fga.listObjectsResult(USER, "can_view", "report"))
                .thenReturn(new OpenFgaAuthzService.ObjectListResult(true, List.of("FIN_ANALYTICS"), "ok"));

        var d = gate.decide(USER);

        assertThat(d.allowed()).isFalse();
        assertThat(d.reason()).isEqualTo("module_blocked");
        verify(fga, never()).listObjectsResult(any(), any(), any());
    }

    @Test
    void organizationAdminAllowedOutright() {
        when(fga.check(USER, "admin", "organization", "default")).thenReturn(true);

        var d = gate.decide(USER);

        assertThat(d.allowed()).isTrue();
        assertThat(d.reason()).isEqualTo("org_admin");
        verify(fga, never()).check(eq(USER), eq("can_view"), any(), any());
    }

    @Test
    void openFgaUnavailableFailsClosed() {
        when(fga.listObjectsResult(USER, "can_view", "report"))
                .thenReturn(OpenFgaAuthzService.ObjectListResult.unavailable("circuit_open"));

        var d = gate.decide(USER);

        assertThat(d.allowed()).isFalse();
        assertThat(d.reason()).isEqualTo("openfga_unavailable:circuit_open");
    }

    @Test
    void disabledOpenFgaPassesThrough() {
        when(fga.isEnabled()).thenReturn(false);

        var d = gate.decide(USER);

        assertThat(d.allowed()).isTrue();
        assertThat(d.reason()).isEqualTo("openfga_disabled");
        verify(fga, never()).check(any(), any(), any(), any());
    }

    @Test
    void missingUserIdDeniesBeforeAskingOpenFga() {
        assertThat(gate.decide(null).reason()).isEqualTo("no_user_id");
        assertThat(gate.decide(" ").allowed()).isFalse();
        verify(fga, never()).check(any(), any(), any(), any());
    }
}
