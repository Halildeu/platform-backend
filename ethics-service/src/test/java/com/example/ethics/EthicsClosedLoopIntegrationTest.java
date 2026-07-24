package com.example.ethics;

import static org.hamcrest.Matchers.*;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.example.ethics.security.PublicCredentialBoundaryFilter;
import com.example.ethics.repository.AuditOutboxRepository;
import com.example.ethics.repository.NotificationOutboxRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.http.Cookie;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.List;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
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

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(EthicsClosedLoopIntegrationTest.TestJwtConfiguration.class)
class EthicsClosedLoopIntegrationTest {
    private static final UUID ORG=UUID.fromString("00000000-0000-0000-0000-000000000035");
    private static final String CLIENT_SECRET="0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ_abcdef";
    @Autowired MockMvc mvc; @Autowired ObjectMapper mapper;
    @Autowired AuditOutboxRepository auditOutbox;
    @Autowired NotificationOutboxRepository notificationOutbox;
    @Autowired MeterRegistry metrics;
    @MockitoBean com.example.ethics.security.EthicsAuthorization authorization;
    @MockitoBean com.example.ethics.security.EthicsEntitlementVerifier entitlements;

    @BeforeEach
    void allowTestStaffObjects() {
        when(authorization.can(any(), anyString(), any())).thenReturn(true);
        org.mockito.Mockito.doNothing().when(authorization).require(any(), anyString(), any());
        when(entitlements.hasManageEntitlement(anyString())).thenReturn(true);
    }

    @Test
    void validSameOrgJwtAndOpenFgaAllowStillDenyWithoutCurrentEthicEntitlement() throws Exception {
        when(entitlements.hasManageEntitlement(anyString())).thenReturn(false);
        mvc.perform(get("/api/v1/ethics/cases")
                        .with(jwt().jwt(j -> j.subject("same-org-revoked")
                                        .claim("org_id", ORG.toString()))
                                .authorities(new SimpleGrantedAuthority("SCOPE_ethics:case:manage"))))
                .andExpect(status().isForbidden());
    }

    @TestConfiguration
    static class TestJwtConfiguration {
        @Bean @Primary
        JwtDecoder testJwtDecoder() {
            return token -> Jwt.withTokenValue(token)
                    .header("alg", "none")
                    .subject("test")
                    .claim("org_id", ORG.toString())
                    .build();
        }
    }

    @Test
    void dualHostIntakeStaffReplyAndReporterMailboxCloseTheLoop() throws Exception {
        long notificationSignalsBefore = notificationOutbox.count();
        String payload="{\"mode\":\"ANONYMOUS\",\"category\":\"WORKPLACE_CONDUCT\",\"subject\":\"Sentetik bildirim\",\"description\":\"Sentetik test anlatımı\",\"locale\":\"tr\",\"accessSecret\":\""+CLIENT_SECRET+"\",\"noticeVersion\":\"tr-test-pilot-v1\"}";
        MvcResult created=mvc.perform(post("/api/v1/public/ethics/reports")
                        .header("Host","etik.acik.com").header("Idempotency-Key","intake-1")
                        .contentType(MediaType.APPLICATION_JSON).content(payload))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.accessSecret").doesNotExist())
                .andReturn();
        JsonNode receipt=mapper.readTree(created.getResponse().getContentAsString());
        String receiptId=receipt.get("receiptId").asText(); String secret=CLIENT_SECRET;

        mvc.perform(post("/api/v1/public/ethics/reports").header("Host","etik.acik.com").header("Idempotency-Key","intake-1").contentType(MediaType.APPLICATION_JSON).content(payload))
                .andExpect(status().isOk()).andExpect(jsonPath("$.idempotentReplay").value(true)).andExpect(jsonPath("$.accessSecret").doesNotExist());

        mvc.perform(post("/api/v1/public/ethics/mailbox/sessions").header("Host","speakup.acik.com").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"receiptId\":\""+receiptId+"\",\"accessSecret\":\""+secret+"\"}"))
                .andExpect(status().isNotFound());

        MvcResult list=mvc.perform(get("/api/v1/ethics/cases").with(jwt().jwt(j->j.subject("staff-1").claim("org_id",ORG.toString())).authorities(new SimpleGrantedAuthority("SCOPE_ethics:case:manage"))))
                .andExpect(status().isOk()).andExpect(jsonPath("$",not(empty()))).andReturn();
        String caseId=mapper.readTree(list.getResponse().getContentAsString()).get(0).get("id").asText();
        MvcResult staffReplyResult=mvc.perform(post("/api/v1/ethics/cases/{id}/messages",caseId).with(jwt().jwt(j->j.subject("staff-1").claim("org_id",ORG.toString())).authorities(new SimpleGrantedAuthority("SCOPE_ethics:case:manage")))
                        .header("Idempotency-Key","staff-reply-1").contentType(MediaType.APPLICATION_JSON).content("{\"body\":\"Sentetik yetkili yanıtı\"}"))
                .andExpect(status().isCreated()).andReturn();
        String staffMessageId=mapper.readTree(staffReplyResult.getResponse().getContentAsString()).get("id").asText();
        mvc.perform(post("/api/v1/ethics/cases/{id}/internal-notes",caseId).with(jwt().jwt(j->j.subject("staff-1").claim("org_id",ORG.toString())).authorities(new SimpleGrantedAuthority("SCOPE_ethics:case:manage")))
                        .header("Idempotency-Key","staff-note-1").contentType(MediaType.APPLICATION_JSON).content("{\"body\":\"Reporter görmemeli\"}"))
                .andExpect(status().isCreated());

        MvcResult login=mvc.perform(post("/api/v1/public/ethics/mailbox/sessions").header("Host","etik.acik.com").contentType(MediaType.APPLICATION_JSON).content("{\"receiptId\":\""+receiptId+"\",\"accessSecret\":\""+secret+"\"}"))
                .andExpect(status().isOk()).andExpect(header().string("Set-Cookie",containsString("__Host-etik_mailbox="))).andExpect(header().string("Set-Cookie",containsString("Path=/"))).andExpect(header().string("Set-Cookie",not(containsString("Domain=")))).andReturn();
        String cookieHeader=login.getResponse().getHeader("Set-Cookie"); String token=cookieHeader.substring(cookieHeader.indexOf('=')+1,cookieHeader.indexOf(';'));
        Cookie mailbox=new Cookie(PublicCredentialBoundaryFilter.MAILBOX_COOKIE,token);
        mvc.perform(get("/api/v1/public/ethics/mailbox/messages").header("Host","etik.acik.com").cookie(mailbox))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control",containsString("no-store")))
                .andExpect(jsonPath("$.status").value("NEW"))
                .andExpect(jsonPath("$.messages",hasSize(1)))
                .andExpect(jsonPath("$.messages[0].body").value("Sentetik yetkili yanıtı"))
                .andExpect(jsonPath("$.caseId").doesNotExist())
                .andExpect(jsonPath("$.orgId").doesNotExist())
                .andExpect(jsonPath("$.assignedTo").doesNotExist());
        MvcResult reporterReplyResult=mvc.perform(post("/api/v1/public/ethics/mailbox/messages").header("Host","etik.acik.com").cookie(mailbox).header("Idempotency-Key","reporter-reply-1").contentType(MediaType.APPLICATION_JSON).content("{\"body\":\"Sentetik reporter yanıtı\"}"))
                .andExpect(status().isCreated()).andReturn();
        String reporterMessageId=mapper.readTree(reporterReplyResult.getResponse().getContentAsString()).get("id").asText();

        var staff=jwt().jwt(j->j.subject("staff-1").claim("org_id",ORG.toString())).authorities(new SimpleGrantedAuthority("SCOPE_ethics:case:manage"));
        mvc.perform(patch("/api/v1/ethics/cases/{id}",caseId).with(staff).header("If-Match","\"0\"")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"CLOSED\"}"))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/public/ethics/mailbox/messages").header("Host","etik.acik.com").cookie(mailbox))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CLOSED"))
                .andExpect(jsonPath("$.messages",hasSize(2)))
                .andExpect(jsonPath("$.caseId").doesNotExist())
                .andExpect(jsonPath("$.assignedTo").doesNotExist());
        mvc.perform(post("/api/v1/ethics/cases/{id}/messages",caseId).with(staff)
                        .header("Idempotency-Key","staff-reply-1").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"body\":\"Sentetik yetkili yanıtı\"}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.id").value(staffMessageId));
        mvc.perform(post("/api/v1/public/ethics/mailbox/messages").header("Host","etik.acik.com").cookie(mailbox)
                        .header("Idempotency-Key","reporter-reply-1").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"body\":\"Sentetik reporter yanıtı\"}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.id").value(reporterMessageId));
        mvc.perform(post("/api/v1/ethics/cases/{id}/messages",caseId).with(staff)
                        .header("Idempotency-Key","closed-staff-reply").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"body\":\"Kapanmış vakaya yazılmamalı\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("CASE_CLOSED"));
        mvc.perform(post("/api/v1/public/ethics/mailbox/messages").header("Host","etik.acik.com").cookie(mailbox)
                        .header("Idempotency-Key","closed-reporter-reply").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"body\":\"Kapanmış vakaya yazılmamalı\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("CASE_CLOSED"));
        // Exactly one signal for the new report and one for the reporter reply.
        // Staff messages, idempotent replays and rejected closed-case writes do
        // not create duplicate or content-bearing notification work.
        assertThat(notificationOutbox.count(), equalTo(notificationSignalsBefore + 2));
    }

    @Test
    void unavailableAuditSinkDoesNotRollbackIntakeAndBacklogIsObservable() throws Exception {
        long pendingBefore = auditOutbox.countByStatusIn(List.of("PENDING", "PROCESSING"));
        String payload="{\"mode\":\"ANONYMOUS\",\"category\":\"OTHER\",\"subject\":\"Audit outage\",\"description\":\"Sentetik backlog kanıtı\",\"locale\":\"tr\",\"accessSecret\":\""+CLIENT_SECRET+"\",\"noticeVersion\":\"tr-test-pilot-v1\"}";

        mvc.perform(post("/api/v1/public/ethics/reports")
                        .header("Host","etik.acik.com")
                        .header("Idempotency-Key","audit-outage-intake")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isCreated());

        assertThat(auditOutbox.countByStatusIn(List.of("PENDING", "PROCESSING")),
                equalTo(pendingBefore + 1));
        assertThat(metrics.get("ethics.audit.outbox.pending.entries").gauge().value(),
                greaterThanOrEqualTo((double) (pendingBefore + 1)));
    }

    @Test
    void disabledNotificationProviderDoesNotRollbackIntakeAndSignalIsDurable() throws Exception {
        long pendingBefore =
                notificationOutbox.countByStatusIn(List.of("PENDING", "PROCESSING"));
        String payload="{\"mode\":\"ANONYMOUS\",\"category\":\"OTHER\",\"subject\":\"Notification outage\",\"description\":\"Sentetik provider outage kanıtı\",\"locale\":\"tr\",\"accessSecret\":\""+CLIENT_SECRET+"\",\"noticeVersion\":\"tr-test-pilot-v1\"}";

        mvc.perform(post("/api/v1/public/ethics/reports")
                        .header("Host","etik.acik.com")
                        .header("Idempotency-Key","notification-outage-intake")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isCreated());

        assertThat(notificationOutbox.countByStatusIn(List.of("PENDING", "PROCESSING")),
                equalTo(pendingBefore + 1));
        assertThat(metrics.get("ethics.notification.outbox.pending.entries").gauge().value(),
                greaterThanOrEqualTo((double) (pendingBefore + 1)));
    }

    @Test
    void intakeIdempotencyCanonicalizationDistinguishesNewlineBoundaries() throws Exception {
        Map<String,Object> first=new LinkedHashMap<>();
        first.put("mode","ANONYMOUS"); first.put("category","WORKPLACE_CONDUCT");
        first.put("subject","a\nb"); first.put("description","c"); first.put("locale","tr");
        first.put("accessSecret",CLIENT_SECRET); first.put("noticeVersion","tr-test-pilot-v1");
        Map<String,Object> second=new LinkedHashMap<>(first);
        second.put("subject","a"); second.put("description","b\nc");

        mvc.perform(post("/api/v1/public/ethics/reports")
                        .header("Host","etik.acik.com").header("Idempotency-Key","newline-boundary")
                        .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsBytes(first)))
                .andExpect(status().isCreated());
        mvc.perform(post("/api/v1/public/ethics/reports")
                        .header("Host","etik.acik.com").header("Idempotency-Key","newline-boundary")
                        .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsBytes(second)))
                .andExpect(status().isConflict());
    }

    @Test
    void credentialAndOrganizationBoundariesFailClosed() throws Exception {
        mvc.perform(post("/api/v1/public/ethics/reports").header("Authorization","Bearer suite-token").header("Host","speakup.acik.com").header("Idempotency-Key","deny-1").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest()).andExpect(header().exists("X-Request-Id"))
                .andExpect(jsonPath("$.error.code").value("CREDENTIAL_CONFUSION"))
                .andExpect(jsonPath("$.error.requestId", not(emptyString())));
        mvc.perform(get("/api/v1/ethics/cases")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/ethics/cases").with(jwt().jwt(j->j.subject("staff-1").claim("org_id",UUID.randomUUID().toString())).authorities(new SimpleGrantedAuthority("SCOPE_ethics:case:manage"))))
                .andExpect(status().isOk()).andExpect(jsonPath("$",hasSize(0)));
        mvc.perform(post("/api/v1/public/ethics/reports").header("Host","etik.acik.com").header("Idempotency-Key","named-disabled")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"mode\":\"NAMED\",\"category\":\"OTHER\",\"subject\":\"x\",\"description\":\"y\",\"locale\":\"tr\",\"accessSecret\":\""+CLIENT_SECRET+"\",\"noticeVersion\":\"tr-test-pilot-v1\"}"))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void concurrentIntakeIsOneDurableReportAndOneSecretDisclosure() throws Exception {
        String payload="{\"mode\":\"ANONYMOUS\",\"category\":\"OTHER\",\"subject\":\"Race\",\"description\":\"Same request\",\"locale\":\"tr\",\"accessSecret\":\""+CLIENT_SECRET+"\",\"noticeVersion\":\"tr-test-pilot-v1\"}";
        ExecutorService pool=Executors.newFixedThreadPool(2); CountDownLatch start=new CountDownLatch(1);
        Callable<MvcResult> request=()->{start.await();return mvc.perform(post("/api/v1/public/ethics/reports")
                    .header("Host","speakup.acik.com").header("Idempotency-Key","race-intake-1")
                    .contentType(MediaType.APPLICATION_JSON).content(payload)).andReturn();};
        Future<MvcResult> first=pool.submit(request); Future<MvcResult> second=pool.submit(request); start.countDown();
        List<JsonNode> results=List.of(mapper.readTree(first.get(15,TimeUnit.SECONDS).getResponse().getContentAsString()),mapper.readTree(second.get(15,TimeUnit.SECONDS).getResponse().getContentAsString()));
        pool.shutdownNow();
        assertThat(results.get(0).get("receiptId").asText(),equalTo(results.get(1).get("receiptId").asText()));
        assertThat(results.stream().filter(node->node.has("accessSecret")).count(),equalTo(0L));
    }

    @Test
    void failedMailboxAttemptsPersistAndLockEvenWhenRequestIsDenied() throws Exception {
        MvcResult created=mvc.perform(post("/api/v1/public/ethics/reports").header("Host","etik.acik.com").header("Idempotency-Key","lockout-intake")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"mode\":\"ANONYMOUS\",\"category\":\"OTHER\",\"subject\":\"Lock\",\"description\":\"Lockout\",\"locale\":\"tr\",\"accessSecret\":\""+CLIENT_SECRET+"\",\"noticeVersion\":\"tr-test-pilot-v1\"}"))
                .andExpect(status().isCreated()).andReturn();
        JsonNode receipt=mapper.readTree(created.getResponse().getContentAsString()); String receiptId=receipt.get("receiptId").asText(); String secret=CLIENT_SECRET;
        for(int i=0;i<5;i++) mvc.perform(post("/api/v1/public/ethics/mailbox/sessions").header("Host","etik.acik.com").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"receiptId\":\""+receiptId+"\",\"accessSecret\":\"wrong-"+i+"\"}"))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/public/ethics/mailbox/sessions").header("Host","etik.acik.com").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"receiptId\":\""+receiptId+"\",\"accessSecret\":\""+secret+"\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void caseUpdateReturnsNewVersionAndRejectsStaleWriter() throws Exception {
        mvc.perform(post("/api/v1/public/ethics/reports").header("Host","etik.acik.com").header("Idempotency-Key","version-intake")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"mode\":\"ANONYMOUS\",\"category\":\"OTHER\",\"subject\":\"Version\",\"description\":\"Sentetik yarış\",\"locale\":\"tr\",\"accessSecret\":\""+CLIENT_SECRET+"\",\"noticeVersion\":\"tr-test-pilot-v1\"}"))
                .andExpect(status().isCreated());
        MvcResult list=mvc.perform(get("/api/v1/ethics/cases").with(jwt().jwt(j->j.subject("staff-1").claim("org_id",ORG.toString())).authorities(new SimpleGrantedAuthority("SCOPE_ethics:case:manage"))))
                .andExpect(status().isOk()).andReturn();
        String caseId=mapper.readTree(list.getResponse().getContentAsString()).get(0).get("id").asText();
        var staff=jwt().jwt(j->j.subject("staff-1").claim("org_id",ORG.toString())).authorities(new SimpleGrantedAuthority("SCOPE_ethics:case:manage"));
        mvc.perform(patch("/api/v1/ethics/cases/{id}",caseId).with(staff).header("If-Match","\"0\"").contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"IN_REVIEW\"}"))
                .andExpect(status().isOk()).andExpect(header().string("ETag","\"1\""))
                .andExpect(jsonPath("$.version").value(1));
        mvc.perform(patch("/api/v1/ethics/cases/{id}",caseId).with(staff).header("If-Match","\"0\"").contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"CLOSED\"}"))
                .andExpect(status().isPreconditionFailed()).andExpect(jsonPath("$.error.code").value("CASE_VERSION_MISMATCH"));
    }
}
