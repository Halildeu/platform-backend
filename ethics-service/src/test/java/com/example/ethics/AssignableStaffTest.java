package com.example.ethics;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.example.commonauth.openfga.OpenFgaAuthzService;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Faz 35 ES-203 — the assignable-staff endpoint.
 *
 * <p>Three properties, and the third is the one that is easy to get wrong: an unreachable
 * policy engine must not be reported as an empty team. "Nobody may be assigned" and "we
 * could not find out" are opposite facts, and a manager shown the first while the second
 * is true would conclude there is no one to hand the case to.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AssignableStaffTest {

    private static final UUID ORG = UUID.fromString("00000000-0000-0000-0000-000000000035");
    private static final String PATH = "/api/v1/ethics/assignable-staff";

    @Autowired MockMvc mvc;
    @MockitoBean com.example.ethics.security.EthicsAuthorization authorization;
    @MockitoBean com.example.ethics.security.EthicsEntitlementVerifier entitlements;

    @BeforeEach
    void entitled() {
        when(entitlements.hasManageEntitlement(anyString())).thenReturn(true);
    }

    private static org.springframework.test.web.servlet.request.RequestPostProcessor staff() {
        return jwt().jwt(j -> j.subject("staff-directory").claim("org_id", ORG.toString()))
                .authorities(new SimpleGrantedAuthority("SCOPE_ethics:case:manage"));
    }

    @Test
    @DisplayName("atama yetkisi olan personel listeyi alır")
    void aTriagerGetsTheList() throws Exception {
        when(authorization.canOnProduct(any(), eq("case_triager"))).thenReturn(true);
        when(authorization.assignableStaff(ORG)).thenReturn(
                new OpenFgaAuthzService.UserListResult(true, List.of("aaa", "bbb"), "ok"));

        mvc.perform(get(PATH).with(staff()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", org.hamcrest.Matchers.hasSize(2)))
                .andExpect(jsonPath("$[0]").value("aaa"))
                .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")));
    }

    /** Seeing a case is not a reason to be handed everyone who works ethics here. */
    @Test
    @DisplayName("yalnız görüntüleme yetkisi listeyi açmaz")
    void aViewerIsRefused() throws Exception {
        when(authorization.canOnProduct(any(), eq("case_triager"))).thenReturn(false);

        mvc.perform(get(PATH).with(staff()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("ASSIGNABLE_STAFF_DENIED"));
    }

    /**
     * The property that matters. An empty 200 here would be read as "the team is empty"
     * and the manager would stop looking for someone to assign.
     */
    @Test
    @DisplayName("yetki motoru cevap veremezse boş liste değil 503 döner")
    void anUnreachablePolicyEngineIsNotAnEmptyTeam() throws Exception {
        when(authorization.canOnProduct(any(), eq("case_triager"))).thenReturn(true);
        when(authorization.assignableStaff(ORG))
                .thenReturn(OpenFgaAuthzService.UserListResult.unavailable("circuit-open"));

        mvc.perform(get(PATH).with(staff()))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error.code").value("ASSIGNABLE_STAFF_UNAVAILABLE"));
    }

    /** An org that genuinely has nobody is a 200 with an empty list — and only that. */
    @Test
    @DisplayName("gerçekten boş org 200 ve boş liste döner")
    void agenuinelyEmptyOrgIsAnEmptyList() throws Exception {
        when(authorization.canOnProduct(any(), eq("case_triager"))).thenReturn(true);
        when(authorization.assignableStaff(ORG))
                .thenReturn(new OpenFgaAuthzService.UserListResult(true, List.of(), "empty"));

        mvc.perform(get(PATH).with(staff()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", org.hamcrest.Matchers.hasSize(0)));
    }
}
