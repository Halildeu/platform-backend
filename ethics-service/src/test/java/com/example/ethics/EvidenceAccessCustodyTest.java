package com.example.ethics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.ethics.model.AuditOutbox;
import com.example.ethics.repository.AuditOutboxRepository;
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
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Faz 35 ES-206 — reading a piece of evidence leaves a record.
 *
 * <p>The custody ledger held how evidence arrived — declared, scanned, sealed, sanitised —
 * and nothing about who later looked at it. That is the half an investigation asks about:
 * a case is reviewed, someone says they never saw a file, and the ledger has no answer.
 *
 * <p>Refusals are recorded alongside successes. A staff member repeatedly requesting the
 * derivative of an attachment that was quarantined as malicious is the pattern worth
 * keeping; logging only what succeeded would record the ordinary work and drop the rest.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(EvidenceAccessCustodyTest.TestJwtConfiguration.class)
class EvidenceAccessCustodyTest {

    private static final UUID ORG = UUID.fromString("00000000-0000-0000-0000-000000000035");
    private static final String SECRET = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ_abcdef";

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired AuditOutboxRepository auditOutbox;
    @MockitoBean com.example.ethics.security.EthicsAuthorization authorization;
    @MockitoBean com.example.ethics.security.EthicsEntitlementVerifier entitlements;

    @BeforeEach
    void allowStaff() {
        when(authorization.can(any(), anyString(), any())).thenReturn(true);
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

    private static org.springframework.test.web.servlet.request.RequestPostProcessor staff() {
        return jwt().jwt(j -> j.subject("staff-custody").claim("org_id", ORG.toString()))
                .authorities(new SimpleGrantedAuthority("SCOPE_ethics:case:manage"));
    }

    private List<AuditOutbox> eventsFor(String attachmentId) {
        return auditOutbox.findAll().stream()
                .filter(a -> attachmentId.equals(a.getAggregateId().toString()))
                .toList();
    }

    /** Files a report and returns the case staff will see it as. */
    private String newCase(String key) throws Exception {
        String body = "{\"mode\":\"ANONYMOUS\",\"category\":\"OTHER\",\"subject\":\"Kanit erisimi " + key
                + "\",\"description\":\"Sentetik\",\"locale\":\"tr\",\"accessSecret\":\"" + SECRET
                + "\",\"noticeVersion\":\"tr-test-pilot-v1\"}";
        mvc.perform(post("/api/v1/public/ethics/reports")
                        .header("Host", "etik.acik.com").header("Idempotency-Key", key)
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated());
        MvcResult list = mvc.perform(get("/api/v1/ethics/cases").with(staff()))
                .andExpect(status().isOk()).andReturn();
        return mapper.readTree(list.getResponse().getContentAsString()).get(0).get("id").asText();
    }

    /**
     * The attachment does not exist on this case. Before ES-206 the caller got a 404 and
     * the ledger recorded nothing at all — including the fact that someone had gone
     * looking.
     */
    @Test
    @DisplayName("var olmayan eke uzanmak da kayda geçer")
    void reachingForAnAttachmentThatIsNotThereIsRecorded() throws Exception {
        String caseId = newCase("custody-missing");
        String attachmentId = UUID.randomUUID().toString();

        mvc.perform(get("/api/v1/ethics/cases/{c}/attachments/{a}/derivative", caseId, attachmentId)
                        .with(staff()))
                .andExpect(status().isNotFound());

        var events = eventsFor(attachmentId);
        assertThat(events).as("reddedilen erişim kayda geçmeli").hasSize(1);
        assertThat(events.get(0).getEventType()).isEqualTo("ethics.evidence.access_denied");
        assertThat(events.get(0).getPayload()).contains("\"reason\":\"NOT_FOUND\"");
    }

    /** The actor is recorded as a hash, like every other identity in this ledger. */
    @Test
    @DisplayName("aktör ham subject olarak değil hash olarak yazılır")
    void theActorIsRecordedAsAHash() throws Exception {
        String caseId = newCase("custody-actor");
        String attachmentId = UUID.randomUUID().toString();

        mvc.perform(get("/api/v1/ethics/cases/{c}/attachments/{a}/derivative", caseId, attachmentId)
                        .with(staff()))
                .andExpect(status().isNotFound());

        String payload = eventsFor(attachmentId).get(0).getPayload();
        assertThat(payload).contains("\"actorHash\"");
        assertThat(payload)
                .as("personelin ham Keycloak subject'i deftere yazılmamalı")
                .doesNotContain("staff-custody");
    }

    /**
     * A refusal is a fact about a request, not about a document, so the reason has to
     * survive into the ledger — "denied" alone cannot be read back years later.
     */
    @Test
    @DisplayName("red kaydı gerekçesini taşır")
    void aRefusalCarriesItsReason() throws Exception {
        String caseId = newCase("custody-reason");
        String attachmentId = UUID.randomUUID().toString();

        mvc.perform(get("/api/v1/ethics/cases/{c}/attachments/{a}/derivative", caseId, attachmentId)
                        .with(staff()))
                .andExpect(status().isNotFound());

        String payload = eventsFor(attachmentId).get(0).getPayload();
        assertThat(payload).contains("\"outcome\":\"DENIED\"");
        assertThat(payload).contains("\"artifact\":\"DERIVATIVE\"");
    }

    /**
     * The sealed original is not served by any route. {@code evidence_reveal_approved}
     * exists in the authorization model and is consulted by nothing — the break-glass
     * path that would use it is ES-303 (#883). Until then there is no door to force,
     * which is what ES-206 owes: the ordinary case roles cannot reach the original.
     */
    @Test
    @DisplayName("mühürlü orijinali sunan bir rota yok — normal rol ulaşamaz")
    void noRouteServesTheSealedOriginal() throws Exception {
        String caseId = newCase("custody-original");
        String attachmentId = UUID.randomUUID().toString();

        for (String path : List.of("original", "sealed", "raw", "quarantine")) {
            mvc.perform(get("/api/v1/ethics/cases/{c}/attachments/{a}/{p}", caseId, attachmentId, path)
                            .with(staff()))
                    .andExpect(result -> assertThat(result.getResponse().getStatus())
                            .as("'%s' orijinali sunuyor olabilir", path)
                            .isNotEqualTo(200));
        }
    }
}
