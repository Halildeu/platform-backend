package com.example.ethics;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
 * Faz 35 — three different refusals must stay three different answers (#990).
 *
 * <p>A path that does not exist, a case the caller may not see, and a caller without the
 * scope are separate facts, and until now the first two collapsed. A missing endpoint
 * produced a 404, Spring forwarded it to {@code /error} to render, {@code /error} fell
 * outside the staff matcher into {@code denyAll}, and the caller got a body-less 403. "No
 * such endpoint" and "not for you" read identically.
 *
 * <p>It cost real diagnosis time: a frontend deployed ahead of its service called
 * {@code /timeline} before the endpoint existed and the 403 looked like an authorization
 * fault. Ingress, edge proxy, NetPol and token claims were each eliminated before the cause
 * turned out to be that the endpoint was not there yet.
 *
 * <p><b>The case-level ambiguity is deliberate and must survive this change.</b> A case the
 * caller cannot see answers 404, not 403, precisely so that probing cannot reveal whether it
 * exists. Fixing the path-level confusion must not "tidy" that into a 403 — which is why the
 * second test here is as important as the first.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PathVersusAuthorizationDenialTest {

    private static final UUID ORG = UUID.fromString("00000000-0000-0000-0000-000000000035");

    @Autowired MockMvc mvc;
    @MockitoBean com.example.ethics.security.EthicsAuthorization authorization;
    @MockitoBean com.example.ethics.security.EthicsEntitlementVerifier entitlements;

    @BeforeEach
    void entitled() {
        when(entitlements.hasManageEntitlement(anyString())).thenReturn(true);
        when(authorization.can(any(), anyString(), any())).thenReturn(true);
        when(authorization.gateFor(any(), anyString())).thenReturn(
                new com.example.ethics.security.EthicsAuthorization.CaseGate(true, java.util.Set.of()));
        when(authorization.assignableStaff(any())).thenReturn(
                new OpenFgaAuthzService.UserListResult(true, List.of(), "ok"));
    }

    private static org.springframework.test.web.servlet.request.RequestPostProcessor staff() {
        return jwt().jwt(j -> j.subject("staff-990").claim("org_id", ORG.toString()))
                .authorities(new SimpleGrantedAuthority("SCOPE_ethics:case:manage"));
    }

    @Test
    @DisplayName("olmayan yol 404 döner — 'yetkin yok' gibi görünmez")
    void aPathThatDoesNotExistAnswersNotFound() throws Exception {
        mvc.perform(get("/api/v1/ethics/cases/" + UUID.randomUUID() + "/boyle-bir-uc-yok").with(staff()))
                .andExpect(status().isNotFound());
    }

    /**
     * The existence oracle defence. Deliberately unchanged, and asserted here because the
     * change above is exactly the kind that invites "while we're at it" tidying.
     */
    @Test
    @DisplayName("görülemeyen vaka hâlâ 404 döner — varlık sızdırmaz")
    void aCaseTheCallerMayNotSeeStillAnswersNotFound() throws Exception {
        org.mockito.Mockito.doThrow(new org.springframework.web.server.ResponseStatusException(
                        org.springframework.http.HttpStatus.NOT_FOUND, "Case not found."))
                .when(authorization).require(any(), anyString(), any());

        mvc.perform(get("/api/v1/ethics/cases/" + UUID.randomUUID()).with(staff()))
                .andExpect(status().isNotFound());
    }

    /**
     * The rule this change actually alters, exercised directly.
     *
     * <p>MockMvc does not forward to {@code /error} the way a servlet container does, so a
     * plain request for a missing path never reaches the chain that used to mangle it — the
     * first version of this class passed identically with and without the fix. Driving the
     * ERROR dispatch explicitly is what tests the rule rather than its surroundings.
     */
    @Test
    @DisplayName("ERROR dispatch güvenlik zinciri tarafından reddedilmez")
    void theErrorDispatchIsNotDenied() throws Exception {
        mvc.perform(get("/error").with(request -> {
                    request.setDispatcherType(jakarta.servlet.DispatcherType.ERROR);
                    request.setAttribute("jakarta.servlet.error.status_code", 404);
                    request.setAttribute("jakarta.servlet.error.request_uri",
                            "/api/v1/ethics/cases/" + UUID.randomUUID() + "/boyle-bir-uc-yok");
                    return request;
                }))
                .andExpect(status().is(org.springframework.http.HttpStatus.NOT_FOUND.value()));
    }

    @Test
    @DisplayName("kapsamı olmayan çağıran 403 alır")
    void aCallerWithoutTheScopeIsForbidden() throws Exception {
        mvc.perform(get("/api/v1/ethics/cases")
                        .with(jwt().jwt(j -> j.subject("staff-990").claim("org_id", ORG.toString()))
                                .authorities(new SimpleGrantedAuthority("SCOPE_something:else"))))
                .andExpect(status().isForbidden());
    }
}
