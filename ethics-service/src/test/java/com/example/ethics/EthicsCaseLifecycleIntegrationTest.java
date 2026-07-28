package com.example.ethics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.example.ethics.repository.AuditOutboxRepository;
import com.example.ethics.repository.EthicsCaseRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Faz 35 ES-301A — the lifecycle over HTTP, against a real schema.
 *
 * <p>{@link com.example.ethics.model.CaseLifecycleContractTest} fixes which moves exist;
 * this fixes what the service does with them, including the two things the standards
 * actually measure: when the reporter was first written to (EU 2019/1937 art. 9(1)(b),
 * seven days) and what the case concluded (art. 9(1)(f)).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(EthicsCaseLifecycleIntegrationTest.TestJwtConfiguration.class)
class EthicsCaseLifecycleIntegrationTest {

    private static final UUID ORG = UUID.fromString("00000000-0000-0000-0000-000000000035");
    private static final String SECRET = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ_abcdef";

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired EthicsCaseRepository cases;
    @Autowired AuditOutboxRepository auditOutbox;
    @MockitoBean com.example.ethics.security.EthicsAuthorization authorization;
    @MockitoBean com.example.ethics.security.EthicsEntitlementVerifier entitlements;

    @BeforeEach
    void allowStaff() {
        when(authorization.can(any(), anyString(), any())).thenReturn(true);
        when(authorization.gateFor(any(), anyString())).thenReturn(
                new com.example.ethics.security.EthicsAuthorization.CaseGate(true, java.util.Set.of()));
        org.mockito.Mockito.doNothing().when(authorization).require(any(), anyString(), any());
        when(entitlements.hasManageEntitlement(anyString())).thenReturn(true);
    }

    @TestConfiguration
    static class TestJwtConfiguration {
        @Bean @Primary
        JwtDecoder testJwtDecoder() {
            return token -> Jwt.withTokenValue(token).header("alg", "none")
                    .subject("test").claim("org_id", ORG.toString()).build();
        }
    }

    // ---------- transitions ----------

    @Test
    @DisplayName("dava NEW → ASSESSING → INVESTIGATING → CLOSED yolunu izler")
    void forwardPathIsAccepted() throws Exception {
        String id = newCase("lifecycle-forward");
        patchCase(id, 0, "{\"status\":\"ASSESSING\"}").andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ASSESSING"));
        patchCase(id, 1, "{\"status\":\"INVESTIGATING\"}").andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("INVESTIGATING"));
        patchCase(id, 2, "{\"status\":\"CLOSED\",\"outcome\":\"SUBSTANTIATED\",\"closingMessage\":\"Inceleme tamamlandi.\"}").andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CLOSED"))
                .andExpect(jsonPath("$.outcome").value("SUBSTANTIATED"))
                .andExpect(jsonPath("$.closedAt").isNotEmpty());
    }

    /**
     * The move that was previously legal. Sending a concluded case back to {@code NEW}
     * erased both the fact of the conclusion and the finding it rested on.
     */
    @Test
    @DisplayName("kapalı dava NEW'e döndürülemez")
    void closedCaseCannotBeSentBackToNew() throws Exception {
        String id = newCase("lifecycle-reopen-new");
        patchCase(id, 0, "{\"status\":\"CLOSED\",\"outcome\":\"OUT_OF_SCOPE\",\"closingMessage\":\"Bildirim kapsam disinda.\"}").andExpect(status().isOk());
        patchCase(id, 1, "{\"status\":\"NEW\"}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("CASE_TRANSITION_NOT_ALLOWED"));
    }

    @Test
    @DisplayName("aşama atlanamaz: NEW doğrudan INVESTIGATING olamaz")
    void stagesCannotBeSkipped() throws Exception {
        String id = newCase("lifecycle-skip");
        patchCase(id, 0, "{\"status\":\"INVESTIGATING\"}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("CASE_TRANSITION_NOT_ALLOWED"));
    }

    @Test
    @DisplayName("yeniden açma gerekçe ister ve sonucu temizler")
    void reopenRequiresAReasonAndClearsTheFinding() throws Exception {
        String id = newCase("lifecycle-reopen");
        patchCase(id, 0, "{\"status\":\"CLOSED\",\"outcome\":\"UNSUBSTANTIATED\",\"closingMessage\":\"Iddia dogrulanamadi.\"}").andExpect(status().isOk());

        patchCase(id, 1, "{\"status\":\"ASSESSING\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("CASE_REOPEN_REASON_REQUIRED"));

        patchCase(id, 1, "{\"status\":\"ASSESSING\",\"reason\":\"Yeni tanık beyanı geldi\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ASSESSING"))
                .andExpect(jsonPath("$.outcome").doesNotExist())
                .andExpect(jsonPath("$.closedAt").doesNotExist());

        assertThat(auditEventTypes(id)).contains("ethics.case.reopened");
    }

    // ---------- the conclusion has to reach the reporter (ES-301B) ----------

    /**
     * A finding filed internally is not feedback. Art. 9(1)(f) asks the organisation to tell
     * the reporting person what came of their report, so closing writes to them or does not
     * happen — the same shape as acknowledgement, and for the same reason.
     */
    @Test
    @DisplayName("kapanış mesajsız reddedilir")
    void closingWithoutTellingTheReporterIsRefused() throws Exception {
        String id = newCase("lifecycle-no-closing-message");
        patchCase(id, 0, "{\"status\":\"CLOSED\",\"outcome\":\"UNSUBSTANTIATED\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("CASE_CLOSING_MESSAGE_REQUIRED"));
        assertThat(cases.findById(UUID.fromString(id)).orElseThrow().getStatus()).isEqualTo("NEW");
    }

    @Test
    @DisplayName("kapanış mesajı ihbarcının posta kutusuna düşer")
    void theClosingMessageReachesTheReporter() throws Exception {
        String id = newCase("lifecycle-closing-message");
        patchCase(id, 0, "{\"status\":\"CLOSED\",\"outcome\":\"OUT_OF_SCOPE\","
                        + "\"closingMessage\":\"Bildiriminiz etik hattin kapsami disinda kaldi.\"}")
                .andExpect(status().isOk());

        MvcResult detail = mvc.perform(get("/api/v1/ethics/cases/{id}", id).with(staff()))
                .andExpect(status().isOk()).andReturn();
        var messages = mapper.readTree(detail.getResponse().getContentAsString()).get("messages");
        assertThat(messages).hasSize(1);
        assertThat(messages.get(0).get("visibility").asText())
                .as("kapanis mesaji ic not degil, ihbarcinin gordugu bir mesaj olmali")
                .isEqualTo("REPORTER_VISIBLE");
        assertThat(messages.get(0).get("body").asText())
                .isEqualTo("Bildiriminiz etik hattin kapsami disinda kaldi.");
    }

    /** Closing straight out of NEW still contacts the reporter, so it is the acknowledgement too. */
    @Test
    @DisplayName("doğrudan kapanan dava da teyit almış sayılır")
    void closingImmediatelyAlsoAcknowledges() throws Exception {
        String id = newCase("lifecycle-close-acks");
        assertThat(cases.findById(UUID.fromString(id)).orElseThrow().getAcknowledgedAt()).isNull();
        patchCase(id, 0, "{\"status\":\"CLOSED\",\"outcome\":\"WITHDRAWN\","
                        + "\"closingMessage\":\"Bildiriminizi geri cektiginizi kaydettik.\"}")
                .andExpect(status().isOk());
        assertThat(cases.findById(UUID.fromString(id)).orElseThrow().getAcknowledgedAt()).isNotNull();
        assertThat(auditEventTypes(id)).contains("ethics.case.acknowledged");
    }

    @Test
    @DisplayName("açık davaya kapanış mesajı iliştirilemez")
    void aClosingMessageOnAnOpenCaseIsRefused() throws Exception {
        String id = newCase("lifecycle-early-closing-message");
        patchCase(id, 0, "{\"status\":\"ASSESSING\",\"closingMessage\":\"Erken kapanis metni\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("CASE_CLOSING_MESSAGE_NOT_APPLICABLE"));
    }

    // ---------- conclusion ----------

    @Test
    @DisplayName("sonuçsuz kapanış reddedilir")
    void closingWithoutAFindingIsRefused() throws Exception {
        String id = newCase("lifecycle-no-outcome");
        patchCase(id, 0, "{\"status\":\"CLOSED\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("CASE_OUTCOME_REQUIRED"));
        assertThat(cases.findById(UUID.fromString(id)).orElseThrow().getStatus()).isEqualTo("NEW");
    }

    @Test
    @DisplayName("tanımsız sonuç kabul edilmez")
    void unknownFindingIsRefused() throws Exception {
        String id = newCase("lifecycle-bad-outcome");
        patchCase(id, 0, "{\"status\":\"CLOSED\",\"outcome\":\"PROBABLY_FINE\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("CASE_OUTCOME_INVALID"));
    }

    /** A finding on an open case would sit there reading as decided when nothing is. */
    @Test
    @DisplayName("açık davaya sonuç yazılamaz")
    void findingOnAnOpenCaseIsRefused() throws Exception {
        String id = newCase("lifecycle-open-outcome");
        patchCase(id, 0, "{\"status\":\"ASSESSING\",\"outcome\":\"SUBSTANTIATED\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("CASE_OUTCOME_NOT_APPLICABLE"));
    }

    // ---------- acknowledgement ----------

    /**
     * The seven-day clock. It is stamped by the act of writing to the reporter rather than
     * by a field an operator can set, so the service cannot record compliance with
     * art. 9(1)(b) for a reporter who was never contacted.
     */
    @Test
    @DisplayName("teyit ilk ihbarcı-görünür yanıtla damgalanır, iç notla değil")
    void acknowledgementFollowsTheReporterVisibleReply() throws Exception {
        String id = newCase("lifecycle-ack");
        assertThat(cases.findById(UUID.fromString(id)).orElseThrow().getAcknowledgedAt()).isNull();

        mvc.perform(post("/api/v1/ethics/cases/{id}/internal-notes", id).with(staff())
                        .header("Idempotency-Key", "note-1")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"body\":\"İç not\"}"))
                .andExpect(status().isCreated());
        assertThat(cases.findById(UUID.fromString(id)).orElseThrow().getAcknowledgedAt())
                .as("iç not ihbarcıya ulaşmaz, teyit sayılamaz").isNull();

        mvc.perform(post("/api/v1/ethics/cases/{id}/messages", id).with(staff())
                        .header("Idempotency-Key", "reply-1")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"body\":\"Bildiriminiz alındı\"}"))
                .andExpect(status().isCreated());

        var acknowledged = cases.findById(UUID.fromString(id)).orElseThrow();
        assertThat(acknowledged.getAcknowledgedAt()).isNotNull();
        assertThat(auditEventTypes(id)).contains("ethics.case.acknowledged");
        assertThat(acknowledged.getVersion())
                .as("teyit operatörün optimistic lock'unu bozmamalı").isZero();
    }

    @Test
    @DisplayName("sonraki yanıtlar teyit anını kaydırmaz")
    void laterRepliesDoNotMoveTheDeadline() throws Exception {
        String id = newCase("lifecycle-ack-once");
        reply(id, "reply-1", "İlk yanıt");
        var first = cases.findById(UUID.fromString(id)).orElseThrow().getAcknowledgedAt();
        reply(id, "reply-2", "İkinci yanıt");
        assertThat(cases.findById(UUID.fromString(id)).orElseThrow().getAcknowledgedAt()).isEqualTo(first);
        assertThat(auditEventTypes(id).stream().filter("ethics.case.acknowledged"::equals).count())
                .as("teyit tam olarak bir kez kaydedilir").isEqualTo(1);
    }

    // ---------- deprecated alias ----------

    @Test
    @DisplayName("IN_REVIEW hâlâ kabul edilir ama ASSESSING olarak saklanır")
    void deprecatedAliasIsAcceptedButNotStored() throws Exception {
        String id = newCase("lifecycle-alias");
        patchCase(id, 0, "{\"status\":\"IN_REVIEW\"}").andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ASSESSING"));
        assertThat(cases.findById(UUID.fromString(id)).orElseThrow().getStatus()).isEqualTo("ASSESSING");
    }

    // ---------- helpers ----------

    private org.springframework.test.web.servlet.ResultActions patchCase(String id, long version, String body) throws Exception {
        return mvc.perform(patch("/api/v1/ethics/cases/{id}", id).with(staff())
                .header("If-Match", "\"" + version + "\"")
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private void reply(String id, String key, String body) throws Exception {
        mvc.perform(post("/api/v1/ethics/cases/{id}/messages", id).with(staff())
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"body\":\"" + body + "\"}"))
                .andExpect(status().isCreated());
    }

    private static org.springframework.test.web.servlet.request.RequestPostProcessor staff() {
        return jwt().jwt(j -> j.subject("staff-lifecycle").claim("org_id", ORG.toString()))
                .authorities(new SimpleGrantedAuthority("SCOPE_ethics:case:manage"));
    }

    private List<String> auditEventTypes(String caseId) {
        return auditOutbox.findAll().stream()
                .filter(a -> caseId.equals(a.getAggregateId().toString()))
                .map(a -> a.getEventType()).toList();
    }

    /** Files a report through the public intake and returns the case id staff will see. */
    private String newCase(String idempotencyKey) throws Exception {
        String payload = "{\"mode\":\"ANONYMOUS\",\"category\":\"WORKPLACE_CONDUCT\","
                + "\"subject\":\"Yaşam döngüsü testi\",\"description\":\"Sentetik anlatım\","
                + "\"locale\":\"tr\",\"accessSecret\":\"" + SECRET + "\",\"noticeVersion\":\"tr-test-pilot-v1\"}";
        mvc.perform(post("/api/v1/public/ethics/reports")
                        .header("Host", "etik.acik.com").header("Idempotency-Key", idempotencyKey)
                        .contentType(MediaType.APPLICATION_JSON).content(payload))
                .andExpect(status().isCreated());
        MvcResult list = mvc.perform(get("/api/v1/ethics/cases").with(staff()))
                .andExpect(status().isOk()).andReturn();
        var all = mapper.readTree(list.getResponse().getContentAsString());
        return all.get(0).get("id").asText();
    }
}
