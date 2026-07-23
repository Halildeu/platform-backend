package com.example.ethics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * ES-306 evidence — the public intake sanitizer rejects HTML tag payloads
 * (XSS defense-in-depth) and SSRF-shaped URLs (metadata / private-net) with
 * stable machine-readable codes so the frontend and the pilot response plan
 * can act on them without content-sniffing.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class EthicsInputSanitizerContractTest {
    private static final String CLIENT_SECRET = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ_abcdef";

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;

    private static String payloadWith(String subject, String description) {
        return "{\"mode\":\"ANONYMOUS\",\"category\":\"OTHER\",\"subject\":\"" + subject.replace("\"", "\\\"")
                + "\",\"description\":\"" + description.replace("\"", "\\\"") + "\",\"locale\":\"tr\","
                + "\"accessSecret\":\"" + CLIENT_SECRET + "\",\"noticeVersion\":\"tr-test-pilot-v1\"}";
    }

    @Test
    void htmlTagInSubjectIsRejectedWithSpecificCode() throws Exception {
        MvcResult result = mvc.perform(post("/api/v1/public/ethics/reports")
                        .header("Host", "etik.acik.com")
                        .header("Idempotency-Key", "html-subject")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payloadWith("<script>alert(1)</script>", "Sade anlatım")))
                .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(422);
        assertThat(mapper.readTree(result.getResponse().getContentAsString()).at("/error/code").asText())
                .isEqualTo("INPUT_HTML_NOT_ALLOWED");
    }

    @Test
    void metadataUrlInDescriptionIsRejectedWithSpecificCode() throws Exception {
        MvcResult result = mvc.perform(post("/api/v1/public/ethics/reports")
                        .header("Host", "etik.acik.com")
                        .header("Idempotency-Key", "ssrf-description")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payloadWith("Sentetik konu", "Bkz http://169.254.169.254/latest/meta-data/")))
                .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(422);
        assertThat(mapper.readTree(result.getResponse().getContentAsString()).at("/error/code").asText())
                .isEqualTo("INPUT_URL_BLOCKED");
    }

    @Test
    void fileSchemePayloadInReporterReplyIsRejected() throws Exception {
        // Reporter mailbox path also runs through the sanitizer; the reply
        // is rejected before the mailbox session is even required.
        MvcResult result = mvc.perform(post("/api/v1/public/ethics/mailbox/messages")
                        .header("Host", "etik.acik.com")
                        .header("Idempotency-Key", "file-scheme-body")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"body\":\"bkz file:///etc/passwd\"}"))
                .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(422);
        assertThat(mapper.readTree(result.getResponse().getContentAsString()).at("/error/code").asText())
                .isEqualTo("INPUT_URL_BLOCKED");
    }

    @Test
    void plainTextReportPasses() throws Exception {
        MvcResult result = mvc.perform(post("/api/v1/public/ethics/reports")
                        .header("Host", "etik.acik.com")
                        .header("Idempotency-Key", "plain-text-report")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payloadWith("Sentetik konu", "Sentetik anlatım metin.")))
                .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(201);
    }
}
