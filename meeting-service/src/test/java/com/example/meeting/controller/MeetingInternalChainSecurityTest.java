package com.example.meeting.controller;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.commonauth.openfga.OpenFgaAuthzService;
import com.example.meeting.config.SecurityConfig;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * ai#244 BE-1b — internal service-to-service filter-chain test (real
 * {@link SecurityConfig} chains, MockMvc, test-scoped mock controller).
 *
 * <p>Loads ONLY a test-scoped {@link InternalMockController} mounted at the
 * production internal path + method with the production {@code @PreAuthorize}
 * gate — no production controller ships under {@code /api/v1/internal/**} in
 * BE-1b (the ingestion endpoint arrives in BE-1c). Authorities are injected via
 * {@code SecurityMockMvcRequestPostProcessors.jwt()} (which bypasses the
 * decoder/converter — those are exercised in {@code MeetingInternalDecoderTest}
 * and {@code MeetingInternalSecurityConverterTest}); this class proves the
 * chain wiring: the {@code @Order(1)} internal chain governs the path and gates
 * strictly on {@code SVC_meeting:analysis-result:write}.
 */
@WebMvcTest(controllers = MeetingInternalChainSecurityTest.InternalMockController.class)
@ActiveProfiles("test")
@Import({SecurityConfig.class, MeetingInternalChainSecurityTest.InternalMockController.class})
class MeetingInternalChainSecurityTest {

    private static final UUID MEETING_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final String PATH = "/api/v1/internal/meetings/{id}/analysis-results";

    @Autowired
    private MockMvc mockMvc;

    // MeetingWebMvcConfig (a WebMvcConfigurer auto-loaded by @WebMvcTest)
    // requires this bean. Its interceptor binds ONLY /api/v1/admin/**, so it
    // never runs for the /internal/** paths this test exercises — the mock
    // simply satisfies the context.
    @MockitoBean
    private OpenFgaAuthzService authzService;

    // ── Keycloak USER token (even admin-privileged) cannot reach internal ────

    @Test
    void keycloakUserJwt_withAdminAuthorities_cannotReachInternalPath_403() throws Exception {
        mockMvc.perform(post(PATH, MEETING_ID)
                        .with(adminUserJwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isForbidden());
    }

    // ── Correct service authority reaches the controller (2xx) ───────────────

    @Test
    void serviceToken_withSvcAuthority_reachesInternalController_2xx() throws Exception {
        mockMvc.perform(post(PATH, MEETING_ID)
                        .with(serviceJwt("SVC_meeting:analysis-result:write"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"summary\":\"ok\"}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.stored").value(true))
                .andExpect(jsonPath("$.meetingId").value(MEETING_ID.toString()));
    }

    // ── Service token missing the required perm authority → 403 ──────────────

    @Test
    void serviceToken_missingRequiredPerm_403() throws Exception {
        mockMvc.perform(post(PATH, MEETING_ID)
                        .with(serviceJwt("SVC_meeting:other:read")) // wrong perm
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isForbidden());
    }

    // ── Internal path does NOT fall through to the default chain ─────────────

    @Test
    void unmappedInternalSubpath_plainAuthenticatedUser_403_notFallThroughTo404() throws Exception {
        // A plain authenticated user (no SVC_ authority) hits an UNMAPPED internal
        // subpath. The @Order(1) internal chain's securityMatcher covers all of
        // /api/v1/internal/meetings/** and denies (403) at the filter — before
        // handler mapping. If the internal chain were missing and the path fell
        // to the default chain (authenticated() only), this authenticated request
        // would pass authorization, reach handler mapping, find no handler and
        // return 404. Asserting 403 (not 404) proves the internal chain governs.
        mockMvc.perform(post("/api/v1/internal/meetings/{id}/does-not-exist", MEETING_ID)
                        .with(jwt().jwt(j -> j.subject("kc-user"))))
                .andExpect(status().isForbidden());
    }

    // ── No credentials → 401 ─────────────────────────────────────────────────

    @Test
    void noAuth_internalPath_401() throws Exception {
        mockMvc.perform(post(PATH, MEETING_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isUnauthorized());
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private static RequestPostProcessor serviceJwt(String svcAuthority) {
        return jwt().jwt(j -> j.subject("meeting-ai-service").claim("iss", "auth-service"))
                .authorities(new SimpleGrantedAuthority(svcAuthority));
    }

    private static RequestPostProcessor adminUserJwt() {
        // A privileged Keycloak user: admin role + meeting scope, but NO SVC_.
        return jwt().jwt(j -> j.subject("admin-user"))
                .authorities(
                        new SimpleGrantedAuthority("ROLE_ADMIN"),
                        new SimpleGrantedAuthority("ROLE_MEETING_ADMIN"),
                        new SimpleGrantedAuthority("SCOPE_meeting"));
    }

    /**
     * Test-scoped stand-in for the BE-1c ingestion endpoint: same production
     * path + method + {@code @PreAuthorize} gate, so the chain + method-security
     * boundary is exercised without shipping a production controller in BE-1b.
     */
    @RestController
    static class InternalMockController {

        @PostMapping(PATH)
        @PreAuthorize("hasAuthority('SVC_meeting:analysis-result:write')")
        public ResponseEntity<Map<String, Object>> store(@PathVariable("id") UUID id,
                                                          @RequestBody(required = false) String body) {
            return ResponseEntity.accepted().body(Map.of(
                    "meetingId", id.toString(),
                    "stored", true));
        }
    }
}
