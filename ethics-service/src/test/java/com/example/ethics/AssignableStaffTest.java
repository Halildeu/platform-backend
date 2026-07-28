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
    private static final String SECRET = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ_abcdef";

    @Autowired MockMvc mvc;
    @Autowired com.fasterxml.jackson.databind.ObjectMapper mapper;
    @MockitoBean com.example.ethics.security.EthicsAuthorization authorization;
    @MockitoBean com.example.ethics.security.EthicsEntitlementVerifier entitlements;
    @MockitoBean com.example.ethics.directory.UserDirectoryClient directory;

    @BeforeEach
    void entitled() {
        when(entitlements.hasManageEntitlement(anyString())).thenReturn(true);
        // ES-203/C default: the directory answers, and knows both synthetic subjects.
        // Individual tests override this to exercise the failure paths.
        when(directory.resolve(any())).thenReturn(new com.example.ethics.directory.UserDirectoryClient.Resolution(
                true, java.util.Map.of("aaa", "Ayşe Yılmaz", "bbb", "Barış Uzun")));
    }

    private static String path(String caseId) {
        return "/api/v1/ethics/cases/" + caseId + "/assignable-staff";
    }

    /** A real case: `requireCase` reads the database, so a mocked permission is not enough. */
    private String newCase() throws Exception {
        String key = "assignable-" + UUID.randomUUID();
        String body = "{\"mode\":\"ANONYMOUS\",\"category\":\"OTHER\",\"subject\":\"Atanabilir personel\","
                + "\"description\":\"Sentetik\",\"locale\":\"tr\",\"accessSecret\":\"" + SECRET
                + "\",\"noticeVersion\":\"tr-test-pilot-v1\"}";
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/api/v1/public/ethics/reports")
                        .header("Host", "etik.acik.com").header("Idempotency-Key", key)
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated());
        var list = mvc.perform(get("/api/v1/ethics/cases").with(staff()))
                .andExpect(status().isOk()).andReturn();
        return mapper.readTree(list.getResponse().getContentAsString()).get(0).get("id").asText();
    }

    private static org.springframework.test.web.servlet.request.RequestPostProcessor staff() {
        return jwt().jwt(j -> j.subject("staff-directory").claim("org_id", ORG.toString()))
                .authorities(new SimpleGrantedAuthority("SCOPE_ethics:case:manage"));
    }

    @Test
    @DisplayName("atama yetkisi olan personel handle + görünen ad alır — subject asla")
    void aTriagerGetsTheList() throws Exception {
        when(authorization.can(any(), org.mockito.ArgumentMatchers.anyString(), any())).thenReturn(true);
        when(authorization.gateFor(any(), org.mockito.ArgumentMatchers.anyString())).thenReturn(
                new com.example.ethics.security.EthicsAuthorization.CaseGate(true, java.util.Set.of()));
        when(authorization.assignableStaff(any())).thenReturn(
                new OpenFgaAuthzService.UserListResult(true, List.of("aaa", "bbb"), "ok"));

        mvc.perform(get(path(newCase())).with(staff()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", org.hamcrest.Matchers.hasSize(2)))
                // Sorted by display name, so the human-readable order is deterministic.
                .andExpect(jsonPath("$[0].displayName").value("Ayşe Yılmaz"))
                .andExpect(jsonPath("$[1].displayName").value("Barış Uzun"))
                .andExpect(jsonPath("$[0].handle", org.hamcrest.Matchers.startsWith("v1.")))
                .andExpect(jsonPath("$[0].handle", org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("aaa"))))
                .andExpect(jsonPath("$[0].subject").doesNotExist())
                .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")));
    }

    /**
     * ES-203/C — this endpoint is where a person gets chosen, so an unreachable name
     * directory fails closed: a manager picking between unnamed rows is exactly the
     * wrong-person assignment ES-203 exists to prevent. Distinct code from the policy
     * engine's 503 so an operator knows which dependency to fix.
     */
    @Test
    @DisplayName("ad dizini cevap veremezse adsız liste değil 503 döner")
    void anUnreachableDirectoryIsNotANamelessList() throws Exception {
        when(authorization.can(any(), org.mockito.ArgumentMatchers.anyString(), any())).thenReturn(true);
        when(authorization.gateFor(any(), org.mockito.ArgumentMatchers.anyString())).thenReturn(
                new com.example.ethics.security.EthicsAuthorization.CaseGate(true, java.util.Set.of()));
        when(authorization.assignableStaff(any())).thenReturn(
                new OpenFgaAuthzService.UserListResult(true, List.of("aaa", "bbb"), "ok"));
        when(directory.resolve(any())).thenReturn(
                com.example.ethics.directory.UserDirectoryClient.Resolution.unavailable());

        mvc.perform(get(path(newCase())).with(staff()))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error.code").value("STAFF_DIRECTORY_UNAVAILABLE"));
    }

    /**
     * A subject the directory answered for but does not know — deleted from the
     * platform while its OpenFGA tuple lingers — must not appear as a selectable
     * ghost. "Assignable" means present in BOTH stores.
     */
    @Test
    @DisplayName("dizinin tanımadığı kişi seçilebilir hayalet olarak listelenmez")
    void aSubjectUnknownToTheDirectoryIsExcluded() throws Exception {
        when(authorization.can(any(), org.mockito.ArgumentMatchers.anyString(), any())).thenReturn(true);
        when(authorization.gateFor(any(), org.mockito.ArgumentMatchers.anyString())).thenReturn(
                new com.example.ethics.security.EthicsAuthorization.CaseGate(true, java.util.Set.of()));
        when(authorization.assignableStaff(any())).thenReturn(
                new OpenFgaAuthzService.UserListResult(true, List.of("aaa", "ghost"), "ok"));
        when(directory.resolve(any())).thenReturn(new com.example.ethics.directory.UserDirectoryClient.Resolution(
                true, java.util.Map.of("aaa", "Ayşe Yılmaz")));

        mvc.perform(get(path(newCase())).with(staff()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", org.hamcrest.Matchers.hasSize(1)))
                .andExpect(jsonPath("$[0].displayName").value("Ayşe Yılmaz"));
    }

    /** Seeing a case is not a reason to be handed everyone who works ethics here. */
    @Test
    @DisplayName("yalnız görüntüleme yetkisi listeyi açmaz")
    void aViewerIsRefused() throws Exception {
        // The case is filed while the caller still has permission; the permission is
        // withdrawn afterwards, so the refusal under test is the endpoint's and not an
        // artefact of an empty case list.
        when(authorization.can(any(), org.mockito.ArgumentMatchers.anyString(), any())).thenReturn(true);
        when(authorization.gateFor(any(), org.mockito.ArgumentMatchers.anyString())).thenReturn(
                new com.example.ethics.security.EthicsAuthorization.CaseGate(true, java.util.Set.of()));
        String caseId = newCase();
        org.mockito.Mockito.doThrow(new org.springframework.web.server.ResponseStatusException(
                        org.springframework.http.HttpStatus.NOT_FOUND, "Case not found."))
                .when(authorization).require(any(), org.mockito.ArgumentMatchers.anyString(), any());

        // `requireCase` answers a denied case with 404 rather than 403: telling the
        // caller a case exists but is closed to them is itself a disclosure.
        mvc.perform(get(path(caseId)).with(staff()))
                .andExpect(status().isNotFound());
    }

    /**
     * The property that matters. An empty 200 here would be read as "the team is empty"
     * and the manager would stop looking for someone to assign.
     */
    @Test
    @DisplayName("yetki motoru cevap veremezse boş liste değil 503 döner")
    void anUnreachablePolicyEngineIsNotAnEmptyTeam() throws Exception {
        when(authorization.can(any(), org.mockito.ArgumentMatchers.anyString(), any())).thenReturn(true);
        when(authorization.gateFor(any(), org.mockito.ArgumentMatchers.anyString())).thenReturn(
                new com.example.ethics.security.EthicsAuthorization.CaseGate(true, java.util.Set.of()));
        when(authorization.assignableStaff(any()))
                .thenReturn(OpenFgaAuthzService.UserListResult.unavailable("circuit-open"));

        mvc.perform(get(path(newCase())).with(staff()))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error.code").value("ASSIGNABLE_STAFF_UNAVAILABLE"));
    }

    /**
     * ES-203/C — the participants view is a display surface, so it degrades where the
     * picker fails closed: an unreachable name directory must not make the people on a
     * case unknowable. The participant is added while the directory works; the listing
     * afterwards answers 200 with a null name, not 503 and not an empty list.
     */
    @Test
    @DisplayName("katılımcı listesi dizin arızasında 503 değil null adla döner")
    void theParticipantsViewDegradesInsteadOfFailing() throws Exception {
        when(authorization.can(any(), org.mockito.ArgumentMatchers.anyString(), any())).thenReturn(true);
        when(authorization.gateFor(any(), org.mockito.ArgumentMatchers.anyString())).thenReturn(
                new com.example.ethics.security.EthicsAuthorization.CaseGate(true, java.util.Set.of()));
        when(authorization.assignableStaff(any())).thenReturn(
                new OpenFgaAuthzService.UserListResult(true, List.of("aaa"), "ok"));
        when(authorization.isProductMember(org.mockito.ArgumentMatchers.anyString(), any())).thenReturn(true);
        String caseId = newCase();

        String handle = mapper.readTree(mvc.perform(get(path(caseId)).with(staff()))
                        .andExpect(status().isOk()).andReturn().getResponse().getContentAsString())
                .get(0).get("handle").asText();
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/api/v1/ethics/cases/" + caseId + "/participants").with(staff())
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{\"handle\":\"" + handle + "\",\"role\":\"handler\"}"))
                .andExpect(status().isNoContent());

        when(directory.resolve(any())).thenReturn(
                com.example.ethics.directory.UserDirectoryClient.Resolution.unavailable());
        mvc.perform(get("/api/v1/ethics/cases/" + caseId + "/participants").with(staff()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", org.hamcrest.Matchers.hasSize(1)))
                .andExpect(jsonPath("$[0].handle").value(handle))
                .andExpect(jsonPath("$[0].displayName").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$[0].role").value("handler"));
    }

    /** An org that genuinely has nobody is a 200 with an empty list — and only that. */
    @Test
    @DisplayName("gerçekten boş org 200 ve boş liste döner")
    void agenuinelyEmptyOrgIsAnEmptyList() throws Exception {
        when(authorization.can(any(), org.mockito.ArgumentMatchers.anyString(), any())).thenReturn(true);
        when(authorization.gateFor(any(), org.mockito.ArgumentMatchers.anyString())).thenReturn(
                new com.example.ethics.security.EthicsAuthorization.CaseGate(true, java.util.Set.of()));
        when(authorization.assignableStaff(any()))
                .thenReturn(new OpenFgaAuthzService.UserListResult(true, List.of(), "empty"));

        mvc.perform(get(path(newCase())).with(staff()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", org.hamcrest.Matchers.hasSize(0)));
    }
}
