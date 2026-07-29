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
 * Faz 35 — the case's own history.
 *
 * <p>Every one of these events has been written since the first day and none of it could
 * be read. The screen showed where a case ended up and not how it got there, so a handler
 * inheriting one had no way to ask "who moved this, and when".
 *
 * <p>The properties worth holding are about what must not disappear: the sequence survives
 * a directory outage, an unparseable payload does not shorten the history, and an actor who
 * has left the product reads as unknown rather than as somebody else.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class CaseTimelineTest {

    private static final UUID ORG = UUID.fromString("00000000-0000-0000-0000-000000000035");
    private static final String SECRET = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ_abcdef";

    @Autowired MockMvc mvc;
    @Autowired com.fasterxml.jackson.databind.ObjectMapper mapper;
    @Autowired com.example.ethics.repository.AuditOutboxRepository auditRows;
    @Autowired com.example.ethics.repository.EvidenceAttachmentRepository attachments;
    @MockitoBean com.example.ethics.security.EthicsAuthorization authorization;
    @MockitoBean com.example.ethics.security.EthicsEntitlementVerifier entitlements;
    @MockitoBean com.example.ethics.directory.UserDirectoryClient directory;

    @BeforeEach
    void entitled() {
        when(entitlements.hasManageEntitlement(anyString())).thenReturn(true);
        when(authorization.can(any(), anyString(), any())).thenReturn(true);
        when(authorization.gateFor(any(), anyString())).thenReturn(
                new com.example.ethics.security.EthicsAuthorization.CaseGate(true, java.util.Set.of()));
        when(authorization.assignableStaff(any())).thenReturn(
                new OpenFgaAuthzService.UserListResult(true, List.of("staff-timeline"), "ok"));
        when(directory.resolve(any())).thenReturn(
                new com.example.ethics.directory.UserDirectoryClient.Resolution(
                        true, java.util.Map.of("staff-timeline", "Ayşe Yılmaz")));
    }

    private static org.springframework.test.web.servlet.request.RequestPostProcessor staff() {
        return jwt().jwt(j -> j.subject("staff-timeline").claim("org_id", ORG.toString()))
                .authorities(new SimpleGrantedAuthority("SCOPE_ethics:case:manage"));
    }

    private String newCase() throws Exception {
        String key = "timeline-" + UUID.randomUUID();
        String body = "{\"mode\":\"ANONYMOUS\",\"category\":\"OTHER\",\"subject\":\"Zaman çizelgesi\","
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

    @Test
    @DisplayName("vakanın kendi geçmişi eskiden yeniye okunur ve intake ilk sırada durur")
    void theHistoryReadsOldestFirst() throws Exception {
        mvc.perform(get("/api/v1/ethics/cases/" + newCase() + "/timeline").with(staff()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].event").value("ethics.report.created"))
                .andExpect(jsonPath("$[0].occurredAt").exists())
                .andExpect(header().string("Cache-Control",
                        org.hamcrest.Matchers.containsString("no-store")));
    }

    /**
     * The trail stores a one-way hash and there is deliberately no reverse table, so an
     * actor is found by hashing this org's own members and matching. A person who has left
     * the product no longer hashes to anything here — and that must read as unknown, never
     * as whoever happens to be left.
     */
    @Test
    @DisplayName("çözülemeyen aktör bilinmiyor kalır — başkası olarak gösterilmez")
    void anUnresolvableActorStaysUnknown() throws Exception {
        String caseId = newCase();
        // Nobody in the org: no hash can match, so no entry may claim an actor.
        when(authorization.assignableStaff(any())).thenReturn(
                new OpenFgaAuthzService.UserListResult(true, List.of(), "ok"));

        var result = mvc.perform(get("/api/v1/ethics/cases/" + caseId + "/timeline").with(staff()))
                .andExpect(status().isOk()).andReturn();

        for (var entry : mapper.readTree(result.getResponse().getContentAsString())) {
            org.assertj.core.api.Assertions.assertThat(entry.get("actorHandle").isNull()).isTrue();
            org.assertj.core.api.Assertions.assertThat(entry.get("actorDisplayName").isNull()).isTrue();
        }
    }

    /**
     * A display surface, so it degrades. Losing the name directory costs the names; the
     * sequence of what happened to the case is the part that must not go missing.
     */
    @Test
    @DisplayName("ad servisi düşse de tarih kaybolmaz — yalnız isimler eksilir")
    void anUnreachableDirectoryCostsNamesNotHistory() throws Exception {
        String caseId = newCase();
        when(directory.resolve(any())).thenReturn(
                com.example.ethics.directory.UserDirectoryClient.Resolution.unavailable());

        mvc.perform(get("/api/v1/ethics/cases/" + caseId + "/timeline").with(staff()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", org.hamcrest.Matchers.hasSize(
                        org.hamcrest.Matchers.greaterThan(0))))
                .andExpect(jsonPath("$[0].event").value("ethics.report.created"));
    }

    /**
     * The case gate, not a separate one: a reader who cannot see the case sees no history.
     *
     * <p>Stubs {@code require}, not {@code can}. {@code requireCase} calls the former, and
     * it returns void — so on a mock it does nothing by default and a test that only stubs
     * {@code can} passes against an endpoint with no gate at all. That is the failure this
     * assertion exists to catch, so it has to reach the method the code actually calls.
     */
    /**
     * A null actor used to mean two opposite things at once — nobody was recorded, or
     * somebody was and no longer resolves — and the reader could not tell which. On an
     * audit trail that difference is the whole point: "nobody touched this" is a claim
     * about the case; "we cannot say who did" is a claim about our own records.
     */
    @Test
    @DisplayName("aktörü olmayan satır ile çözülemeyen satır ayrı durumlar döner")
    void theAbsenceOfAnActorIsNotTheSameAsFailingToNameOne() throws Exception {
        String caseId = newCase();
        // The intake carries no actorHash at all: an anonymous filing.
        var byEvent = timelineByEvent(caseId);
        org.assertj.core.api.Assertions.assertThat(byEvent.get("ethics.report.created"))
                .isEqualTo("NONE");

        // Same trail, but this org now has no members, so a recorded actor cannot be named.
        when(authorization.assignableStaff(any())).thenReturn(
                new OpenFgaAuthzService.UserListResult(true, List.of(), "ok"));
        for (var entry : mapper.readTree(timelineJson(caseId))) {
            if (entry.get("actorState").asText().equals("UNRESOLVED")) {
                // An unnamed actor must not leak a handle either.
                org.assertj.core.api.Assertions.assertThat(entry.get("actorHandle").isNull()).isTrue();
            }
        }
    }

    /**
     * The fail-closed direction. A payload this service cannot parse might have carried an
     * actor — we simply cannot see. Reporting {@code NONE} there would assert that nobody
     * acted, which is a statement about the case that an unreadable record cannot support.
     */
    @Test
    @DisplayName("okunamayan kayıt UNRESOLVED kalır — 'kimse yoktu' denmez")
    void anUnreadablePayloadNeverClaimsThereWasNoActor() throws Exception {
        String caseId = newCase();
        auditRows.save(new com.example.ethics.model.AuditOutbox(
                UUID.randomUUID(), ORG, UUID.fromString(caseId), "ethics.case.sealed",
                "{bu gecerli JSON degil", java.time.Instant.now()));

        var byEvent = timelineByEvent(caseId);
        org.assertj.core.api.Assertions.assertThat(byEvent.get("ethics.case.sealed"))
                .isEqualTo("UNRESOLVED");
    }

    /**
     * The gap this query closes. {@code aggregate_id} is polymorphic: a case event carries
     * the case id, an evidence event carries the attachment id. Querying by case id alone
     * returned the case's own events and dropped its whole evidence custody chain — on the
     * live cell, one visible event against thirteen invisible ones for the same case.
     *
     * <p>A history that omits what happened to the evidence is the failure this screen was
     * built to end: a handler reads a short list and concludes little happened.
     */
    @Test
    @DisplayName("kanıt olayları da vakanın geçmişinde görünür")
    void theHistoryIncludesEventsFiledUnderAnAttachment() throws Exception {
        String caseId = newCase();
        UUID attachment = UUID.randomUUID();
        var now = java.time.Instant.now();
        attachments.save(new com.example.ethics.model.EvidenceAttachment(
                attachment, UUID.fromString(caseId), ORG, "etik.acik.com",
                "idem-" + attachment, "0".repeat(64), "tr-test-pilot-v1",
                "text/plain", 10L, "0".repeat(64),
                "quarantine/" + attachment, "sealed/" + attachment, "derivative/" + attachment,
                "0".repeat(64), now.plusSeconds(900), now));
        auditRows.save(new com.example.ethics.model.AuditOutbox(
                UUID.randomUUID(), ORG, attachment, "ethics.evidence.declared",
                "{\"sizeClass\":\"SMALL\"}", java.time.Instant.now()));

        var byEvent = timelineByEvent(caseId);
        org.assertj.core.api.Assertions.assertThat(byEvent)
                .as("ek dosya kimliğiyle yazılan olay vakanın geçmişinde yok")
                .containsKey("ethics.evidence.declared");
    }

    private String timelineJson(String caseId) throws Exception {
        return mvc.perform(get("/api/v1/ethics/cases/" + caseId + "/timeline").with(staff()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }

    private java.util.Map<String, String> timelineByEvent(String caseId) throws Exception {
        var out = new java.util.LinkedHashMap<String, String>();
        for (var entry : mapper.readTree(timelineJson(caseId)))
            out.put(entry.get("event").asText(), entry.get("actorState").asText());
        return out;
    }

    @Test
    @DisplayName("vakayı göremeyen geçmişini de göremez")
    void theCaseGateGuardsTheHistory() throws Exception {
        String caseId = newCase();
        org.mockito.Mockito.doThrow(new org.springframework.web.server.ResponseStatusException(
                        org.springframework.http.HttpStatus.NOT_FOUND, "Case not found."))
                .when(authorization).require(any(), anyString(), any());

        mvc.perform(get("/api/v1/ethics/cases/" + caseId + "/timeline").with(staff()))
                .andExpect(status().isNotFound());
    }
}
