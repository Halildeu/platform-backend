package com.example.ethics;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
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
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Faz 35 ES-203 slice 2 — retiring {@code assignedTo}.
 *
 * <p>The live column held {@code team:ethics-test} on 26 cases and {@code jbjb} on one.
 * Neither names a person and nothing maps them to one, so the label is preserved, never
 * turned into a principal, and suppressed as soon as the case has a real answer.
 *
 * <p>The first test is the one that matters. Simply deleting the field would have been
 * quieter and worse: Spring's mapper ignores unknown properties by default, so an old
 * client would have received 200 and believed it had assigned somebody while the case
 * stayed unowned.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class LegacyAssignmentLabelTest {

    private static final UUID ORG = UUID.fromString("00000000-0000-0000-0000-000000000035");
    private static final String SECRET = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ_abcdef";

    @Autowired MockMvc mvc;
    @Autowired com.fasterxml.jackson.databind.ObjectMapper mapper;
    @Autowired com.example.ethics.repository.EthicsCaseRepository cases;
    @MockitoBean com.example.ethics.security.EthicsAuthorization authorization;
    @MockitoBean com.example.ethics.security.EthicsEntitlementVerifier entitlements;
    @MockitoBean com.example.ethics.directory.UserDirectoryClient directory;

    @BeforeEach
    void entitled() {
        when(entitlements.hasManageEntitlement(anyString())).thenReturn(true);
        when(authorization.can(any(), anyString(), any())).thenReturn(true);
        when(authorization.isProductMember(anyString(), any())).thenReturn(true);
        when(authorization.assignableStaff(any())).thenReturn(
                new OpenFgaAuthzService.UserListResult(true, List.of("aaa"), "ok"));
        when(directory.resolve(any())).thenReturn(
                new com.example.ethics.directory.UserDirectoryClient.Resolution(
                        true, java.util.Map.of("aaa", "Ayşe Yılmaz")));
    }

    private static org.springframework.test.web.servlet.request.RequestPostProcessor staff() {
        return jwt().jwt(j -> j.subject("legacy-label-staff").claim("org_id", ORG.toString()))
                .authorities(new SimpleGrantedAuthority("SCOPE_ethics:case:manage"));
    }

    /** A real case: the read paths hit the database, so a mocked permission is not enough. */
    private String newCase() throws Exception {
        String key = "legacy-" + UUID.randomUUID();
        String body = "{\"mode\":\"ANONYMOUS\",\"category\":\"OTHER\",\"subject\":\"Eski etiket\","
                + "\"description\":\"Sentetik\",\"locale\":\"tr\",\"accessSecret\":\"" + SECRET
                + "\",\"noticeVersion\":\"tr-test-pilot-v1\"}";
        mvc.perform(post("/api/v1/public/ethics/reports")
                        .header("Host", "etik.acik.com").header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated());
        var list = mvc.perform(get("/api/v1/ethics/cases").with(staff()))
                .andExpect(status().isOk()).andReturn();
        return mapper.readTree(list.getResponse().getContentAsString()).get(0).get("id").asText();
    }

    /** Writes the legacy value the way history did — straight into the column. */
    private void stampLegacyLabel(String caseId, String label) {
        var item = cases.findById(UUID.fromString(caseId)).orElseThrow();
        item.setAssignedTo(label);
        cases.saveAndFlush(item);
    }

    @Test
    @DisplayName("assignedTo ile atama denemesi sessizce yutulmaz, açıkça reddedilir")
    void anAssignmentAttemptIsRefusedRatherThanIgnored() throws Exception {
        String caseId = newCase();
        mvc.perform(patch("/api/v1/ethics/cases/{id}", caseId).with(staff()).header("If-Match", "\"0\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"assignedTo\":\"team:ethics-test\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("CASE_ASSIGNED_TO_RETIRED"));

        // Blank too: "clear the assignment" is still the retired knob, and answering 200
        // would tell the caller a field it can no longer use is still working.
        mvc.perform(patch("/api/v1/ethics/cases/{id}", caseId).with(staff()).header("If-Match", "\"0\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"assignedTo\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("CASE_ASSIGNED_TO_RETIRED"));
    }

    @Test
    @DisplayName("katılımcısı olmayan davada eski etiket korunur — tahminle kişiye bağlanmaz")
    void anUnresolvedLabelIsPreservedAndNotMappedToAnyone() throws Exception {
        String caseId = newCase();
        stampLegacyLabel(caseId, "jbjb");

        mvc.perform(get("/api/v1/ethics/cases/{id}", caseId).with(staff()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.legacyAssignmentLabel").value("jbjb"))
                // The retired name must not come back as an alias.
                .andExpect(jsonPath("$.assignedTo").doesNotExist());

        // And it names nobody: the participant list stays empty, so nothing downstream can
        // read the label as a principal.
        mvc.perform(get("/api/v1/ethics/cases/{id}/participants", caseId).with(staff()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", org.hamcrest.Matchers.hasSize(0)));
    }

    @Test
    @DisplayName("katılımcı eklenince eski etiket gösterilmez — rakip cevap kalmaz")
    void theLabelDisappearsOnceThereIsARealAnswer() throws Exception {
        String caseId = newCase();
        stampLegacyLabel(caseId, "team:ethics-test");

        String handle = mapper.readTree(mvc.perform(
                        get("/api/v1/ethics/cases/{id}/assignable-staff", caseId).with(staff()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString())
                .get(0).get("handle").asText();
        mvc.perform(post("/api/v1/ethics/cases/{id}/participants", caseId).with(staff())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"handle\":\"" + handle + "\",\"role\":\"handler\"}"))
                .andExpect(status().isNoContent());

        mvc.perform(get("/api/v1/ethics/cases/{id}", caseId).with(staff()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.legacyAssignmentLabel").value(org.hamcrest.Matchers.nullValue()));
        mvc.perform(get("/api/v1/ethics/cases").with(staff()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id=='" + caseId + "')].legacyAssignmentLabel")
                        .value(org.hamcrest.Matchers.everyItem(org.hamcrest.Matchers.nullValue())));

        // The column keeps the historical value; only the response hides it. Erasing it
        // would destroy the record of what the case used to say about itself.
        org.assertj.core.api.Assertions
                .assertThat(cases.findById(UUID.fromString(caseId)).orElseThrow().getAssignedTo())
                .isEqualTo("team:ethics-test");
    }

    /**
     * The property the whole slice exists for: a label grants nothing. Product membership
     * is withdrawn while the legacy label stays on the case; the case must vanish, not
     * remain visible on the strength of a word somebody typed.
     */
    @Test
    @DisplayName("eski etiket hiçbir görünürlük üretmez")
    void aLegacyLabelGrantsNoVisibility() throws Exception {
        String caseId = newCase();
        stampLegacyLabel(caseId, "team:ethics-test");

        when(authorization.can(any(), anyString(), any())).thenReturn(false);
        org.mockito.Mockito.doThrow(new org.springframework.web.server.ResponseStatusException(
                        org.springframework.http.HttpStatus.NOT_FOUND, "Case not found."))
                .when(authorization).require(any(), anyString(), any());

        mvc.perform(get("/api/v1/ethics/cases").with(staff()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id=='" + caseId + "')]", org.hamcrest.Matchers.hasSize(0)));
        mvc.perform(get("/api/v1/ethics/cases/{id}", caseId).with(staff()))
                .andExpect(status().isNotFound());
    }
}
