package com.example.ethics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.ethics.model.CaseEscalation;
import com.example.ethics.repository.AuditOutboxRepository;
import com.example.ethics.repository.CaseEscalationRepository;
import com.example.ethics.service.EscalationSweeper;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
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
import org.springframework.dao.DataAccessException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Faz 35 ES-301 — the escalation record, end to end against a real schema (#882).
 *
 * <p>A case filed through the public intake, its deadline pushed into the past, swept twice:
 * exactly one row per level, one audit event per row, and the level visible on the staff
 * list, the detail and the timeline. Then the two negatives that matter — an acknowledgement
 * committed first means no acknowledgement level, and the schema itself refuses a second row
 * for the same level.
 */
@SpringBootTest(properties = {
        "ethics.sla.escalation.enabled=true",
        "ethics.sla.escalation.initial-delay=PT720H",
        "ethics.sla.escalation.steps=PT0S,P3D"})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(EscalationIntegrationTest.TestJwtConfiguration.class)
class EscalationIntegrationTest {

    private static final UUID ORG = UUID.fromString("00000000-0000-0000-0000-000000000035");
    private static final String SECRET = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ_abcdef";
    private static final Instant NOW = Instant.parse("2026-07-29T12:00:00Z");

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired EscalationSweeper sweeper;
    @Autowired CaseEscalationRepository escalations;
    @Autowired AuditOutboxRepository auditOutbox;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean com.example.ethics.security.EthicsAuthorization authorization;
    @MockitoBean com.example.ethics.security.EthicsEntitlementVerifier entitlements;
    @MockitoBean com.example.ethics.directory.UserDirectoryClient directory;

    @BeforeEach
    void allowStaff() {
        when(authorization.can(any(), anyString(), any())).thenReturn(true);
        when(authorization.gateFor(any(), anyString())).thenReturn(
                new com.example.ethics.security.EthicsAuthorization.CaseGate(true, java.util.Set.of()));
        org.mockito.Mockito.doNothing().when(authorization).require(any(), anyString(), any());
        when(authorization.assignableStaff(any())).thenReturn(
                new com.example.commonauth.openfga.OpenFgaAuthzService.UserListResult(true, List.of(), "ok"));
        when(entitlements.hasManageEntitlement(anyString())).thenReturn(true);
        when(directory.resolve(any())).thenReturn(
                new com.example.ethics.directory.UserDirectoryClient.Resolution(true, java.util.Map.of()));
    }

    @TestConfiguration
    static class TestJwtConfiguration {
        @Bean @Primary
        JwtDecoder testJwtDecoder() {
            return token -> Jwt.withTokenValue(token).header("alg", "none")
                    .subject("test").claim("org_id", ORG.toString()).build();
        }
    }

    @Test
    @DisplayName("ihlal tek kez kaydedilir, denetime düşer ve personel API'sinde görünür")
    void anEscalationIsRecordedOnceAndSurfacedToStaff() throws Exception {
        String id = newCase("escalation-surface");
        // Acknowledgement due 5 days ago: level 1 (PT0S) and level 2 (P3D) both passed.
        backdate(id, NOW.minus(Duration.ofDays(12)));

        var first = sweeper.runCycle(NOW);
        var second = sweeper.runCycle(NOW.plusSeconds(1));

        assertThat(first.enabled()).isTrue();
        assertThat(second.failed()).isZero();
        var rows = escalations.findAllByCaseIdOrderByEscalatedAtAscLevelAsc(UUID.fromString(id));
        assertThat(rows).extracting(CaseEscalation::getLevel).containsExactly(1, 2);
        assertThat(rows).allSatisfy(row -> {
            assertThat(row.getObligation()).isEqualTo(CaseEscalation.ACKNOWLEDGEMENT);
            assertThat(row.getOrgId()).isEqualTo(ORG);
            assertThat(row.getEscalatedAt()).isEqualTo(NOW);
        });
        assertThat(rows.get(1).getThresholdAt()).isEqualTo(rows.get(1).getDueAt().plus(Duration.ofDays(3)));

        assertThat(auditOutbox.findAllByOrgIdAndAggregateIdOrderByCreatedAtAsc(ORG, UUID.fromString(id)))
                .filteredOn(a -> EscalationSweeper.EVENT_TYPE.equals(a.getEventType()))
                .hasSize(2);

        mvc.perform(get("/api/v1/ethics/cases/{id}", id).with(staff()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.escalationLevel").value(2))
                .andExpect(jsonPath("$.escalatedAt").value(NOW.toString()))
                .andExpect(jsonPath("$.acknowledgementState").value("BREACHED"));

        var list = mapper.readTree(mvc.perform(get("/api/v1/ethics/cases").with(staff()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        var mine = java.util.stream.StreamSupport.stream(list.spliterator(), false)
                .filter(row -> id.equals(row.get("id").asText())).findFirst().orElseThrow();
        assertThat(mine.get("escalationLevel").asInt()).isEqualTo(2);

        var timeline = mapper.readTree(mvc.perform(get("/api/v1/ethics/cases/{id}/timeline", id).with(staff()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        var details = java.util.stream.StreamSupport.stream(timeline.spliterator(), false)
                .filter(entry -> EscalationSweeper.EVENT_TYPE.equals(entry.get("event").asText()))
                .map(entry -> entry.get("detail").asText()).toList();
        assertThat(details).containsExactlyInAnyOrder("ACKNOWLEDGEMENT L1", "ACKNOWLEDGEMENT L2");
    }

    /** History, not activity: meeting the obligation afterwards keeps the level on record. */
    @Test
    @DisplayName("sonradan onaylanan vaka ulaşılmış seviyeyi kaybetmez, yeni seviye de almaz")
    void aLateAcknowledgementKeepsTheRecordedLevelAndAddsNone() throws Exception {
        String id = newCase("escalation-late-ack");
        backdate(id, NOW.minus(Duration.ofDays(8)));
        sweeper.runCycle(NOW);
        assertThat(escalations.findAllByCaseIdOrderByEscalatedAtAscLevelAsc(UUID.fromString(id))).hasSize(1);

        reply(id, "late-ack-" + id, "Bildiriminiz alindi.");
        sweeper.runCycle(NOW.plus(Duration.ofDays(30)));

        assertThat(escalations.findAllByCaseIdOrderByEscalatedAtAscLevelAsc(UUID.fromString(id)))
                .extracting(CaseEscalation::getLevel).containsExactly(1);
        mvc.perform(get("/api/v1/ethics/cases/{id}", id).with(staff()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.escalationLevel").value(1))
                .andExpect(jsonPath("$.acknowledgementState").value("MET"))
                .andExpect(jsonPath("$.acknowledgedLate").value(true));
    }

    @Test
    @DisplayName("taramadan önce onaylanan vaka onay seviyesi almaz")
    void anAcknowledgementCommittedBeforeTheSweepMeansNoLevel() throws Exception {
        String id = newCase("escalation-ack-first");
        backdate(id, NOW.minus(Duration.ofDays(12)));
        reply(id, "ack-first-" + id, "Bildiriminiz alindi.");

        sweeper.runCycle(NOW);

        assertThat(escalations.findAllByCaseIdOrderByEscalatedAtAscLevelAsc(UUID.fromString(id))).isEmpty();
    }

    @Test
    @DisplayName("şema aynı seviye için ikinci satırı reddeder")
    void theSchemaRefusesASecondRowForTheSameLevel() throws Exception {
        String id = newCase("escalation-unique");
        Instant due = NOW.minus(Duration.ofDays(1));
        insertRow(id, 1, due);

        assertThatThrownBy(() -> insertRow(id, 1, due)).isInstanceOf(DataAccessException.class);
        assertThat(escalations.findAllByCaseIdOrderByEscalatedAtAscLevelAsc(UUID.fromString(id))).hasSize(1);
    }

    private void insertRow(String caseId, int level, Instant dueAt) {
        jdbc.update("INSERT INTO ethics_case_escalation"
                        + " (id, case_id, org_id, obligation, level, due_at, threshold_at, escalated_at)"
                        + " VALUES (?, ?, ?, 'ACKNOWLEDGEMENT', ?, ?, ?, ?)",
                UUID.randomUUID(), UUID.fromString(caseId), ORG, level,
                Timestamp.from(dueAt), Timestamp.from(dueAt), Timestamp.from(NOW));
    }

    private void backdate(String caseId, Instant createdAt) {
        int changed = jdbc.update("UPDATE ethics_cases SET created_at = ? WHERE id = ?",
                Timestamp.from(createdAt), UUID.fromString(caseId));
        assertThat(changed).isEqualTo(1);
    }

    private void reply(String id, String key, String body) throws Exception {
        mvc.perform(post("/api/v1/ethics/cases/{id}/messages", id).with(staff())
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"body\":\"" + body + "\"}"))
                .andExpect(status().isCreated());
    }

    private static org.springframework.test.web.servlet.request.RequestPostProcessor staff() {
        return jwt().jwt(j -> j.subject("staff-escalation").claim("org_id", ORG.toString()))
                .authorities(new SimpleGrantedAuthority("SCOPE_ethics:case:manage"));
    }

    /** Files a report through the public intake and returns the case id staff will see. */
    private String newCase(String idempotencyKey) throws Exception {
        String payload = "{\"mode\":\"ANONYMOUS\",\"category\":\"WORKPLACE_CONDUCT\","
                + "\"subject\":\"Eskalasyon testi\",\"description\":\"Sentetik anlatım\","
                + "\"locale\":\"tr\",\"accessSecret\":\"" + SECRET + "\",\"noticeVersion\":\"tr-test-pilot-v1\"}";
        mvc.perform(post("/api/v1/public/ethics/reports")
                        .header("Host", "etik.acik.com").header("Idempotency-Key", idempotencyKey)
                        .contentType(MediaType.APPLICATION_JSON).content(payload))
                .andExpect(status().isCreated());
        var all = mapper.readTree(mvc.perform(get("/api/v1/ethics/cases").with(staff()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        // The list is newest-first by updatedAt; the case just filed is the first row.
        return all.get(0).get("id").asText();
    }
}
