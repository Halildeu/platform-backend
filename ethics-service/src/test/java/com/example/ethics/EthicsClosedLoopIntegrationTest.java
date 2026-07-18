package com.example.ethics;

import static org.hamcrest.Matchers.*;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.example.ethics.security.PublicCredentialBoundaryFilter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import java.util.UUID;
import java.util.List;
import java.util.concurrent.*;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(EthicsClosedLoopIntegrationTest.TestJwtConfiguration.class)
class EthicsClosedLoopIntegrationTest {
    private static final UUID ORG=UUID.fromString("00000000-0000-0000-0000-000000000035");
    @Autowired MockMvc mvc; @Autowired ObjectMapper mapper;

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
        String payload="{\"mode\":\"ANONYMOUS\",\"category\":\"WORKPLACE_CONDUCT\",\"subject\":\"Sentetik bildirim\",\"description\":\"Sentetik test anlatımı\",\"locale\":\"tr\"}";
        MvcResult created=mvc.perform(post("/api/v1/public/ethics/reports")
                        .header("Host","etik.acik.com").header("Idempotency-Key","intake-1")
                        .contentType(MediaType.APPLICATION_JSON).content(payload))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.accessSecret",not(blankOrNullString())))
                .andReturn();
        JsonNode receipt=mapper.readTree(created.getResponse().getContentAsString());
        String receiptId=receipt.get("receiptId").asText(); String secret=receipt.get("accessSecret").asText();

        mvc.perform(post("/api/v1/public/ethics/reports").header("Host","etik.acik.com").header("Idempotency-Key","intake-1").contentType(MediaType.APPLICATION_JSON).content(payload))
                .andExpect(status().isOk()).andExpect(jsonPath("$.idempotentReplay").value(true)).andExpect(jsonPath("$.accessSecret").doesNotExist());

        MvcResult list=mvc.perform(get("/api/v1/ethics/cases").with(jwt().jwt(j->j.subject("staff-1").claim("org_id",ORG.toString())).authorities(new SimpleGrantedAuthority("SCOPE_ethics:case:manage"))))
                .andExpect(status().isOk()).andExpect(jsonPath("$",not(empty()))).andReturn();
        String caseId=mapper.readTree(list.getResponse().getContentAsString()).get(0).get("id").asText();
        mvc.perform(post("/api/v1/ethics/cases/{id}/messages",caseId).with(jwt().jwt(j->j.subject("staff-1").claim("org_id",ORG.toString())).authorities(new SimpleGrantedAuthority("SCOPE_ethics:case:manage")))
                        .header("Idempotency-Key","staff-reply-1").contentType(MediaType.APPLICATION_JSON).content("{\"body\":\"Sentetik yetkili yanıtı\"}"))
                .andExpect(status().isCreated());

        MvcResult login=mvc.perform(post("/api/v1/public/ethics/mailbox/sessions").contentType(MediaType.APPLICATION_JSON).content("{\"receiptId\":\""+receiptId+"\",\"accessSecret\":\""+secret+"\"}"))
                .andExpect(status().isOk()).andExpect(header().string("Set-Cookie",containsString("__Host-etik_mailbox="))).andExpect(header().string("Set-Cookie",not(containsString("Domain=")))).andReturn();
        String cookieHeader=login.getResponse().getHeader("Set-Cookie"); String token=cookieHeader.substring(cookieHeader.indexOf('=')+1,cookieHeader.indexOf(';'));
        Cookie mailbox=new Cookie(PublicCredentialBoundaryFilter.MAILBOX_COOKIE,token);
        mvc.perform(get("/api/v1/public/ethics/mailbox/messages").cookie(mailbox))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].body").value("Sentetik yetkili yanıtı"));
        mvc.perform(post("/api/v1/public/ethics/mailbox/messages").cookie(mailbox).header("Idempotency-Key","reporter-reply-1").contentType(MediaType.APPLICATION_JSON).content("{\"body\":\"Sentetik reporter yanıtı\"}"))
                .andExpect(status().isCreated());
    }

    @Test
    void credentialAndOrganizationBoundariesFailClosed() throws Exception {
        mvc.perform(post("/api/v1/public/ethics/reports").header("Authorization","Bearer suite-token").header("Host","speakup.acik.com").header("Idempotency-Key","deny-1").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("CREDENTIAL_CONFUSION"));
        mvc.perform(get("/api/v1/ethics/cases")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/ethics/cases").with(jwt().jwt(j->j.subject("staff-1").claim("org_id",UUID.randomUUID().toString())).authorities(new SimpleGrantedAuthority("SCOPE_ethics:case:manage"))))
                .andExpect(status().isOk()).andExpect(jsonPath("$",hasSize(0)));
        mvc.perform(post("/api/v1/public/ethics/reports").header("Host","etik.acik.com").header("Idempotency-Key","named-disabled")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"mode\":\"NAMED\",\"category\":\"OTHER\",\"subject\":\"x\",\"description\":\"y\",\"locale\":\"tr\"}"))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void concurrentIntakeIsOneDurableReportAndOneSecretDisclosure() throws Exception {
        String payload="{\"mode\":\"ANONYMOUS\",\"category\":\"OTHER\",\"subject\":\"Race\",\"description\":\"Same request\",\"locale\":\"tr\"}";
        ExecutorService pool=Executors.newFixedThreadPool(2); CountDownLatch start=new CountDownLatch(1);
        Callable<MvcResult> request=()->{start.await();return mvc.perform(post("/api/v1/public/ethics/reports")
                    .header("Host","speakup.acik.com").header("Idempotency-Key","race-intake-1")
                    .contentType(MediaType.APPLICATION_JSON).content(payload)).andReturn();};
        Future<MvcResult> first=pool.submit(request); Future<MvcResult> second=pool.submit(request); start.countDown();
        List<JsonNode> results=List.of(mapper.readTree(first.get(15,TimeUnit.SECONDS).getResponse().getContentAsString()),mapper.readTree(second.get(15,TimeUnit.SECONDS).getResponse().getContentAsString()));
        pool.shutdownNow();
        assertThat(results.get(0).get("receiptId").asText(),equalTo(results.get(1).get("receiptId").asText()));
        assertThat(results.stream().filter(node->node.hasNonNull("accessSecret")).count(),equalTo(1L));
    }

    @Test
    void failedMailboxAttemptsPersistAndLockEvenWhenRequestIsDenied() throws Exception {
        MvcResult created=mvc.perform(post("/api/v1/public/ethics/reports").header("Host","etik.acik.com").header("Idempotency-Key","lockout-intake")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"mode\":\"ANONYMOUS\",\"category\":\"OTHER\",\"subject\":\"Lock\",\"description\":\"Lockout\",\"locale\":\"tr\"}"))
                .andExpect(status().isCreated()).andReturn();
        JsonNode receipt=mapper.readTree(created.getResponse().getContentAsString()); String receiptId=receipt.get("receiptId").asText(); String secret=receipt.get("accessSecret").asText();
        for(int i=0;i<5;i++) mvc.perform(post("/api/v1/public/ethics/mailbox/sessions").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"receiptId\":\""+receiptId+"\",\"accessSecret\":\"wrong-"+i+"\"}"))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/public/ethics/mailbox/sessions").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"receiptId\":\""+receiptId+"\",\"accessSecret\":\""+secret+"\"}"))
                .andExpect(status().isNotFound());
    }
}
