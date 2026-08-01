package com.example.ethics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.ethics.repository.AuditOutboxRepository;
import com.example.ethics.repository.EthicsCaseRepository;
import com.example.ethics.repository.EthicsMessageRepository;
import com.example.ethics.service.AckNetWorker;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Faz 35 ES-2 (#3271) — automatic draft, human dispatch, last-day net.
 *
 * <p>What must hold: the draft comes from the template with the placeholders filled;
 * human dispatch stamps the acknowledgement and writes the template identity to the
 * ledger; an edit that removed a mandatory section is recorded rather than blocked;
 * and when nobody sends, the seventh day's start sends the draft as-is with
 * {@code dispatch=AUTOMATIC} — the exception made visible, exactly once.
 */
@SpringBootTest(properties = {
        "ethics.acknowledgement.net-enabled=false", // cycles run by hand in tests
        "ethics.rate-limit-per-minute=300"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@org.springframework.context.annotation.Import(AcknowledgementIntegrationTest.TestJwtConfiguration.class)
class AcknowledgementIntegrationTest {

    private static final String ACCESS_SECRET = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ_acktst";
    private static final UUID ORG = UUID.fromString("00000000-0000-0000-0000-000000000035");

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired EthicsCaseRepository cases;
    @Autowired EthicsMessageRepository messages;
    @Autowired AuditOutboxRepository audit;
    @Autowired AckNetWorker net;
    @Autowired jakarta.persistence.EntityManager entityManager;
    @Autowired com.example.ethics.repository.ReporterAccessGrantRepository grants;
    @Autowired org.springframework.transaction.support.TransactionTemplate transactions;
    @org.springframework.test.context.bean.override.mockito.MockitoBean
    com.example.ethics.security.EthicsAuthorization authorization;
    @org.springframework.test.context.bean.override.mockito.MockitoBean
    com.example.ethics.security.EthicsEntitlementVerifier entitlements;

    @org.junit.jupiter.api.BeforeEach
    void allowStaff() {
        org.mockito.Mockito.when(authorization.can(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any())).thenReturn(true);
        org.mockito.Mockito.when(authorization.gateFor(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyString())).thenReturn(
                new com.example.ethics.security.EthicsAuthorization.CaseGate(true, java.util.Set.of()));
        org.mockito.Mockito.doNothing().when(authorization).require(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any());
        org.mockito.Mockito.when(entitlements.hasManageEntitlement(
                org.mockito.ArgumentMatchers.anyString())).thenReturn(true);
    }

    @Test
    void draftIsFilledFromTheTemplateAndHumanDispatchRecordsTheTemplateIdentity() throws Exception {
        UUID caseId = createCase("OTHER");

        MvcResult draftResult = mvc.perform(
                        get("/api/v1/ethics/cases/{id}/acknowledgement-draft", caseId).with(staff()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.alreadyAcknowledged").value(false))
                .andReturn();
        var draft = mapper.readTree(draftResult.getResponse().getContentAsString());
        String body = draft.get("body").asText();
        // Placeholders are gone and the mandatory sections are present.
        assertThat(body).doesNotContain("{{").contains("Misilleme").contains("Dış kanallar");

        mvc.perform(post("/api/v1/ethics/cases/{id}/acknowledgement", caseId)
                        .with(staff())
                        .header("Idempotency-Key", "ack-manual-" + caseId)
                        .contentType("application/json")
                        .content(mapper.writeValueAsString(Map.of(
                                "body", body,
                                "templateId", draft.get("templateId").asText(),
                                "templateVersion", draft.get("templateVersion").asInt()))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.missingSections").isEmpty());

        assertThat(cases.findById(caseId).orElseThrow().getAcknowledgedAt()).isNotNull();
        assertThat(dispatchAuditPayloads(caseId)).singleElement()
                .satisfies(payload -> assertThat(payload).contains("\"dispatch\":\"MANUAL\""));
    }

    @Test
    void anEditThatRemovedAMandatorySectionIsRecordedNotBlocked() throws Exception {
        UUID caseId = createCase("OTHER");
        var draft = mapper.readTree(mvc.perform(
                        get("/api/v1/ethics/cases/{id}/acknowledgement-draft", caseId).with(staff()))
                .andReturn().getResponse().getContentAsString());
        String gutted = draft.get("body").asText()
                .replace("Misilleme yasağı", "..").replace("Dış kanallar", "..");

        mvc.perform(post("/api/v1/ethics/cases/{id}/acknowledgement", caseId)
                        .with(staff())
                        .header("Idempotency-Key", "ack-gutted-" + caseId)
                        .contentType("application/json")
                        .content(mapper.writeValueAsString(Map.of(
                                "body", gutted,
                                "templateId", draft.get("templateId").asText(),
                                "templateVersion", draft.get("templateVersion").asInt()))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.missingSections[0]").value("EXTERNAL_CHANNELS"))
                .andExpect(jsonPath("$.missingSections[1]").value("RETALIATION_BAN"));

        assertThat(dispatchAuditPayloads(caseId)).singleElement()
                .satisfies(payload -> assertThat(payload)
                        .contains("RETALIATION_BAN").contains("EXTERNAL_CHANNELS"));
    }

    @Test
    void theLastDayNetSendsTheDraftAsIsExactlyOnceAndSaysSo() throws Exception {
        UUID caseId = createCase("HARASSMENT_DISCRIMINATION");
        // Age the case to the seventh day. Direct SQL because created_at has no setter —
        // the same reasoning as everywhere else here: production code must not be able
        // to do this, the fixture must.
        transactions.executeWithoutResult(tx -> entityManager.createNativeQuery(
                        "update ethics_cases set created_at = :aged where id = :id")
                .setParameter("aged", Instant.now().minusSeconds(6 * 86_400 + 3_600))
                .setParameter("id", caseId)
                .executeUpdate());

        assertThat(net.runCycle(Instant.now())).isEqualTo(1);
        assertThat(cases.findById(caseId).orElseThrow().getAcknowledgedAt()).isNotNull();
        // The harassment variant reached the reporter — support wording included.
        var visible = messages.findAllByCaseIdAndVisibilityInOrderByCreatedAtAsc(
                caseId, java.util.List.of("REPORTER_VISIBLE"));
        assertThat(visible).singleElement()
                .satisfies(message -> assertThat(message.getBody())
                        .contains("Destek").doesNotContain("{{"));
        assertThat(dispatchAuditPayloads(caseId)).singleElement()
                .satisfies(payload -> assertThat(payload).contains("\"dispatch\":\"AUTOMATIC\""));

        // A second cycle finds nothing: the stamp closed the window.
        assertThat(net.runCycle(Instant.now())).isZero();
        assertThat(dispatchAuditPayloads(caseId)).hasSize(1);
    }

    @Test
    void aFreshCaseIsNotTouchedByTheNet() throws Exception {
        UUID caseId = createCase("OTHER");
        assertThat(net.runCycle(Instant.now())).isZero();
        assertThat(cases.findById(caseId).orElseThrow().getAcknowledgedAt()).isNull();
    }

    // ---------- fixtures ----------

    private UUID createCase(String category) throws Exception {
        MvcResult created = mvc.perform(post("/api/v1/public/ethics/reports")
                        .header("Host", "etik.acik.com")
                        .header("Idempotency-Key", "ack-case-" + UUID.randomUUID())
                        .contentType("application/json")
                        .content(mapper.writeValueAsString(Map.of(
                                "mode", "ANONYMOUS",
                                "category", category,
                                "subject", "Sentetik ack vakası",
                                "description", "Gerçek PII içermeyen sentetik anlatım",
                                "locale", "tr",
                                "accessSecret", ACCESS_SECRET,
                                "noticeVersion", "tr-test-pilot-v1"))))
                .andExpect(status().isCreated())
                .andReturn();
        UUID receiptId = UUID.fromString(mapper.readTree(
                created.getResponse().getContentAsString()).get("receiptId").asText());
        return grants.findAll().stream()
                .filter(grant -> receiptId.equals(grant.getReceiptId()))
                .findFirst().orElseThrow().getCaseId();
    }

    private java.util.List<String> dispatchAuditPayloads(UUID caseId) {
        return audit.findAll().stream()
                .filter(entry -> caseId.equals(entry.getAggregateId()))
                .filter(entry -> "ethics.case.acknowledgement.dispatched".equals(entry.getEventType()))
                .map(com.example.ethics.model.AuditOutbox::getPayload)
                .toList();
    }

    private static org.springframework.test.web.servlet.request.RequestPostProcessor staff() {
        return jwt().jwt(token -> token.subject("ack-test-staff")
                        .claim("org_id", ORG.toString()))
                .authorities(new org.springframework.security.core.authority.SimpleGrantedAuthority(
                        "SCOPE_ethics:case:manage"));
    }

    @org.springframework.boot.test.context.TestConfiguration
    static class TestJwtConfiguration {
        @org.springframework.context.annotation.Bean
        @org.springframework.context.annotation.Primary
        org.springframework.security.oauth2.jwt.JwtDecoder testJwtDecoder() {
            return token -> org.springframework.security.oauth2.jwt.Jwt.withTokenValue(token)
                    .header("alg", "none")
                    .subject("test")
                    .claim("org_id", ORG.toString())
                    .build();
        }
    }
}
