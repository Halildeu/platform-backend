package com.example.ethics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.ethics.evidence.EvidenceObjectStore;
import com.example.ethics.evidence.EvidencePipelineWorker;
import com.example.ethics.evidence.EvidenceProcessor;
import com.example.ethics.model.EvidenceAttachment;
import com.example.ethics.repository.EvidenceAttachmentRepository;
import com.example.ethics.repository.EvidenceDerivationRepository;
import com.example.ethics.repository.ReporterAccessGrantRepository;
import com.example.ethics.security.PublicCredentialBoundaryFilter;
import com.example.ethics.service.SecretHasher;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
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

@SpringBootTest(properties = {
        "ethics.evidence.enabled=true",
        "ethics.evidence.manifest-signing-key=synthetic-test-signing-key-not-a-secret-0001",
        "ethics.evidence.s3.access-key=synthetic",
        "ethics.evidence.s3.secret-key=synthetic",
        "ethics.evidence.processor.mode=disabled",
        "ethics.evidence.pipeline.enabled=false",
        "ethics.evidence.pipeline.retry-delay=1ms"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import({
        EvidenceCustodyIntegrationTest.TestJwtConfiguration.class,
        EvidenceCustodyIntegrationTest.EvidenceTestConfiguration.class
})
class EvidenceCustodyIntegrationTest {
    private static final UUID ORG =
            UUID.fromString("00000000-0000-0000-0000-000000000035");
    private static final String ACCESS_SECRET =
            "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ_evidence";
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired SecretHasher hashes;
    @Autowired ReporterAccessGrantRepository grants;
    @Autowired EvidenceAttachmentRepository attachments;
    @Autowired EvidenceDerivationRepository derivations;
    @Autowired EvidencePipelineWorker worker;
    @Autowired FakeEvidenceProcessor processor;
    @Autowired InMemoryEvidenceObjectStore objects;
    @MockitoBean com.example.ethics.security.EthicsAuthorization authorization;
    @MockitoBean com.example.ethics.security.EthicsEntitlementVerifier entitlements;

    @BeforeEach
    void setUp() {
        derivations.deleteAll();
        attachments.deleteAll();
        processor.mode.set(FakeEvidenceProcessor.Mode.CLEAN);
        objects.clear();
        when(authorization.can(any(), anyString(), any())).thenReturn(true);
        org.mockito.Mockito.doNothing().when(authorization)
                .require(any(), anyString(), any());
        when(entitlements.hasManageEntitlement(anyString())).thenReturn(true);
    }

    @Test
    void cleanUploadCreatesSealedOriginalAndOnlyPublishesSanitizedDerivative() throws Exception {
        ReportSession report = createReportAndOpenMailbox();
        byte[] original = "Reporter metadata\r\nmust not survive".getBytes(StandardCharsets.UTF_8);
        Declaration declaration = declare(report.mailbox(), original, "text/plain");

        mvc.perform(put("/api/v1/public/ethics/evidence/uploads")
                        .header("Host", "etik.acik.com")
                        .header("X-Etik-Upload-Capability", declaration.capability())
                        .contentType(MediaType.APPLICATION_OCTET_STREAM)
                        .content(original))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.state").value("ORIGINAL_SEALED"))
                .andExpect(jsonPath("$.failureCode").doesNotExist());

        mvc.perform(get("/api/v1/ethics/cases/{caseId}/attachments/{attachmentId}/derivative",
                        report.caseId(), declaration.attachmentId())
                        .with(staff()))
                .andExpect(status().isNotFound());

        EvidencePipelineWorker.CycleResult cycle = worker.runCycle();
        assertThat(cycle.available()).isEqualTo(1);
        assertThat(cycle.rejected()).isZero();

        byte[] expected = "SANITIZED\nReporter metadata\nmust not survive"
                .getBytes(StandardCharsets.UTF_8);
        mvc.perform(get("/api/v1/ethics/cases/{caseId}/attachments/{attachmentId}/derivative",
                        report.caseId(), declaration.attachmentId())
                        .with(staff()))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("Content-Security-Policy", "default-src 'none'; sandbox"))
                .andExpect(header().string("Content-Disposition",
                        "attachment; filename=\"sanitized-evidence\""))
                .andExpect(result -> assertThat(result.getResponse().getContentAsByteArray())
                        .isEqualTo(expected)
                        .isNotEqualTo(original));

        mvc.perform(get("/api/v1/ethics/cases/{caseId}/attachments/{attachmentId}/original",
                        report.caseId(), declaration.attachmentId())
                        .with(staff()))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/ethics/cases/{caseId}/attachments/{attachmentId}/derivative",
                        report.caseId(), declaration.attachmentId())
                        .with(jwt().jwt(j -> j.subject("wrong-org")
                                        .claim("org_id", UUID.randomUUID().toString()))
                                .authorities(new SimpleGrantedAuthority(
                                        "SCOPE_ethics:case:manage"))))
                .andExpect(status().isNotFound());

        mvc.perform(get("/api/v1/public/ethics/mailbox/attachments")
                        .header("Host", "etik.acik.com")
                        .cookie(report.mailbox()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].state").value("AVAILABLE"))
                .andExpect(jsonPath("$[0].failureCode").doesNotExist())
                .andExpect(jsonPath("$[0].attachmentId").value(declaration.attachmentId().toString()))
                .andExpect(jsonPath("$[0].quarantineKey").doesNotExist())
                .andExpect(jsonPath("$[0].sealedKey").doesNotExist())
                .andExpect(jsonPath("$[0].derivativeKey").doesNotExist());

        assertThat(objects.sealed.get(declaration.attachmentId())).isEqualTo(original);
        assertThat(objects.derivative.get(declaration.attachmentId())).isEqualTo(expected);
        assertThat(derivations.countByAttachmentId(declaration.attachmentId())).isEqualTo(1);

        mvc.perform(put("/api/v1/public/ethics/evidence/uploads")
                        .header("Host", "etik.acik.com")
                        .header("X-Etik-Upload-Capability", declaration.capability())
                        .contentType(MediaType.APPLICATION_OCTET_STREAM)
                        .content(original))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("EVIDENCE_UPLOAD_ALREADY_CONSUMED"));
    }

    @Test
    void malwareNeverBecomesStaffVisible() throws Exception {
        processor.mode.set(FakeEvidenceProcessor.Mode.MALICIOUS);
        ReportSession report = createReportAndOpenMailbox();
        byte[] original = "synthetic-malware-sentinel".getBytes(StandardCharsets.UTF_8);
        Declaration declaration = declare(report.mailbox(), original, "text/plain");
        upload(declaration, original);

        EvidencePipelineWorker.CycleResult cycle = worker.runCycle();
        assertThat(cycle.rejected()).isEqualTo(1);
        EvidenceAttachment row = attachments.findById(declaration.attachmentId()).orElseThrow();
        assertThat(row.getState()).isEqualTo("MALICIOUS_QUARANTINED");
        assertThat(row.getFailureCode()).isEqualTo("EVIDENCE_MALWARE_DETECTED");
        assertThat(objects.derivative).doesNotContainKey(declaration.attachmentId());

        mvc.perform(get("/api/v1/ethics/cases/{caseId}/attachments/{attachmentId}/derivative",
                        report.caseId(), declaration.attachmentId())
                        .with(staff()))
                .andExpect(status().isNotFound());
    }

    @Test
    void processorOutageLeavesEvidencePendingButTextReportAndMailboxFunctional() throws Exception {
        processor.mode.set(FakeEvidenceProcessor.Mode.UNAVAILABLE);
        ReportSession report = createReportAndOpenMailbox();
        byte[] original = "safe evidence".getBytes(StandardCharsets.UTF_8);
        Declaration declaration = declare(report.mailbox(), original, "text/plain");
        upload(declaration, original);

        EvidencePipelineWorker.CycleResult cycle = worker.runCycle();
        assertThat(cycle.pending()).isEqualTo(1);
        assertThat(attachments.findById(declaration.attachmentId()).orElseThrow().getState())
                .isEqualTo("SCAN_PENDING");

        mvc.perform(post("/api/v1/public/ethics/mailbox/messages")
                        .header("Host", "etik.acik.com")
                        .cookie(report.mailbox())
                        .header("Idempotency-Key", "outage-message-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"body\":\"Attachment beklerken metin akışı sürer\"}"))
                .andExpect(status().isCreated());
        mvc.perform(get("/api/v1/public/ethics/mailbox/messages")
                        .header("Host", "etik.acik.com")
                        .cookie(report.mailbox()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.messages[0].body")
                        .value("Attachment beklerken metin akışı sürer"));
    }

    @Test
    void sizeMismatchConsumesAttemptAsRejectedAndNeverStoresBytes() throws Exception {
        ReportSession report = createReportAndOpenMailbox();
        byte[] original = "declared".getBytes(StandardCharsets.UTF_8);
        Declaration declaration = declare(report.mailbox(), original, "text/plain");
        byte[] differentLength = "different-size".getBytes(StandardCharsets.UTF_8);

        mvc.perform(put("/api/v1/public/ethics/evidence/uploads")
                        .header("Host", "etik.acik.com")
                        .header("X-Etik-Upload-Capability", declaration.capability())
                        .contentType(MediaType.APPLICATION_OCTET_STREAM)
                        .content(differentLength))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("EVIDENCE_SIZE_MISMATCH"));

        EvidenceAttachment row = attachments.findById(declaration.attachmentId()).orElseThrow();
        assertThat(row.getState()).isEqualTo("REJECTED_INTEGRITY");
        assertThat(objects.quarantine).doesNotContainKey(declaration.attachmentId());
    }

    @Test
    void capabilityIsHostBoundAndDigestMismatchFailsClosed() throws Exception {
        ReportSession report = createReportAndOpenMailbox();
        byte[] original = "same-size-safe".getBytes(StandardCharsets.UTF_8);
        Declaration declaration = declare(report.mailbox(), original, "text/plain");

        mvc.perform(put("/api/v1/public/ethics/evidence/uploads")
                        .header("Host", "speakup.acik.com")
                        .header("X-Etik-Upload-Capability", declaration.capability())
                        .contentType(MediaType.APPLICATION_OCTET_STREAM)
                        .content(original))
                .andExpect(status().isNotFound());
        byte[] wrong = "same-size-evil".getBytes(StandardCharsets.UTF_8);
        assertThat(wrong).hasSameSizeAs(original);
        mvc.perform(put("/api/v1/public/ethics/evidence/uploads")
                        .header("Host", "etik.acik.com")
                        .header("X-Etik-Upload-Capability", declaration.capability())
                        .contentType(MediaType.APPLICATION_OCTET_STREAM)
                        .content(wrong))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error.code").value("EVIDENCE_UPLOAD_NOT_ACCEPTED"));
        EvidenceAttachment row = attachments.findById(declaration.attachmentId()).orElseThrow();
        assertThat(row.getState()).isEqualTo("REJECTED_INTEGRITY");
        assertThat(row.getFailureCode()).isEqualTo("EVIDENCE_DIGEST_MISMATCH");
        assertThat(objects.sealed).doesNotContainKey(declaration.attachmentId());
        assertThat(objects.derivative).doesNotContainKey(declaration.attachmentId());
    }

    private ReportSession createReportAndOpenMailbox() throws Exception {
        String key = "evidence-report-" + UUID.randomUUID();
        String body = mapper.writeValueAsString(Map.of(
                "mode", "ANONYMOUS",
                "category", "OTHER",
                "subject", "Sentetik evidence custody",
                "description", "Gerçek PII içermeyen test anlatımı",
                "locale", "tr",
                "accessSecret", ACCESS_SECRET,
                "noticeVersion", "tr-test-pilot-v1"));
        MvcResult created = mvc.perform(post("/api/v1/public/ethics/reports")
                        .header("Host", "etik.acik.com")
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn();
        UUID receiptId = UUID.fromString(
                mapper.readTree(created.getResponse().getContentAsString())
                        .get("receiptId").asText());
        UUID caseId = grants.findById(receiptId).orElseThrow().getCaseId();
        MvcResult login = mvc.perform(post("/api/v1/public/ethics/mailbox/sessions")
                        .header("Host", "etik.acik.com")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of(
                                "receiptId", receiptId,
                                "accessSecret", ACCESS_SECRET))))
                .andExpect(status().isOk())
                .andReturn();
        String setCookie = login.getResponse().getHeader("Set-Cookie");
        String token = setCookie.substring(
                (PublicCredentialBoundaryFilter.MAILBOX_COOKIE + "=").length(),
                setCookie.indexOf(';'));
        return new ReportSession(caseId,
                new Cookie(PublicCredentialBoundaryFilter.MAILBOX_COOKIE, token));
    }

    private Declaration declare(Cookie mailbox, byte[] content, String mediaType) throws Exception {
        MvcResult result = mvc.perform(post("/api/v1/public/ethics/mailbox/attachments")
                        .header("Host", "etik.acik.com")
                        .cookie(mailbox)
                        .header("Idempotency-Key", "evidence-declare-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of(
                                "mediaType", mediaType,
                                "size", content.length,
                                "sha256", hashes.sha256(content)))))
                .andExpect(status().isCreated())
                .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")))
                .andExpect(jsonPath("$.uploadPath")
                        .value("/api/v1/public/ethics/evidence/uploads"))
                .andExpect(jsonPath("$.uploadCapability").isNotEmpty())
                .andExpect(jsonPath("$.quarantineKey").doesNotExist())
                .andReturn();
        JsonNode json = mapper.readTree(result.getResponse().getContentAsString());
        return new Declaration(
                UUID.fromString(json.get("attachmentId").asText()),
                json.get("uploadCapability").asText());
    }

    private void upload(Declaration declaration, byte[] content) throws Exception {
        mvc.perform(put("/api/v1/public/ethics/evidence/uploads")
                        .header("Host", "etik.acik.com")
                        .header("X-Etik-Upload-Capability", declaration.capability())
                        .contentType(MediaType.APPLICATION_OCTET_STREAM)
                        .content(content))
                .andExpect(status().isAccepted());
    }

    private static org.springframework.test.web.servlet.request.RequestPostProcessor staff() {
        return jwt().jwt(j -> j.subject("staff-evidence")
                        .claim("org_id", ORG.toString()))
                .authorities(new SimpleGrantedAuthority("SCOPE_ethics:case:manage"));
    }

    record ReportSession(UUID caseId, Cookie mailbox) {}
    record Declaration(UUID attachmentId, String capability) {}

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

    @TestConfiguration
    static class EvidenceTestConfiguration {
        @Bean @Primary InMemoryEvidenceObjectStore evidenceObjectStore(SecretHasher hashes) {
            return new InMemoryEvidenceObjectStore(hashes);
        }
        @Bean @Primary FakeEvidenceProcessor evidenceProcessor() {
            return new FakeEvidenceProcessor();
        }
    }

    static class InMemoryEvidenceObjectStore implements EvidenceObjectStore {
        final Map<UUID, byte[]> quarantine = new ConcurrentHashMap<>();
        final Map<UUID, byte[]> sealed = new ConcurrentHashMap<>();
        final Map<UUID, byte[]> derivative = new ConcurrentHashMap<>();
        private final SecretHasher hashes;
        InMemoryEvidenceObjectStore(SecretHasher hashes) { this.hashes = hashes; }
        void clear() { quarantine.clear(); sealed.clear(); derivative.clear(); }

        @Override
        public ObjectReceipt putQuarantine(
                EvidenceAttachment attachment, InputStream input, long contentLength) {
            try {
                byte[] body = input.readAllBytes();
                if (body.length != contentLength
                        || !hashes.sha256(body).equals(attachment.getDeclaredSha256())) {
                    throw new StoreException("EVIDENCE_DIGEST_MISMATCH");
                }
                if (quarantine.putIfAbsent(attachment.getId(), body) != null) {
                    throw new StoreException("EVIDENCE_OBJECT_ALREADY_EXISTS");
                }
                return new ObjectReceipt("q-v1", body.length, hashes.sha256(body));
            } catch (StoreException error) {
                throw error;
            } catch (Exception error) {
                throw new StoreException("EVIDENCE_STORAGE_UNAVAILABLE", error);
            }
        }
        @Override
        public ObjectReceipt sealOriginal(EvidenceAttachment attachment) {
            byte[] body = quarantine.get(attachment.getId());
            if (body == null) throw new StoreException("EVIDENCE_SEAL_UNAVAILABLE");
            sealed.putIfAbsent(attachment.getId(), body.clone());
            return new ObjectReceipt("s-v1", body.length, hashes.sha256(body));
        }
        @Override public byte[] readQuarantine(EvidenceAttachment a) {
            byte[] body = quarantine.get(a.getId());
            if (body == null) throw new StoreException("EVIDENCE_STORAGE_UNAVAILABLE");
            return body.clone();
        }
        @Override
        public ObjectReceipt putDerivative(
                EvidenceAttachment attachment, byte[] content, String sha256, String mediaType) {
            derivative.putIfAbsent(attachment.getId(), content.clone());
            return new ObjectReceipt("d-v1", content.length, hashes.sha256(content));
        }
        @Override public byte[] readDerivative(EvidenceAttachment a) {
            byte[] body = derivative.get(a.getId());
            if (body == null) throw new StoreException("EVIDENCE_DERIVATIVE_UNAVAILABLE");
            return body.clone();
        }
        @Override public void deleteQuarantine(EvidenceAttachment a) {
            quarantine.remove(a.getId());
        }
    }

    static class FakeEvidenceProcessor implements EvidenceProcessor {
        enum Mode { CLEAN, MALICIOUS, UNAVAILABLE }
        final AtomicReference<Mode> mode = new AtomicReference<>(Mode.CLEAN);
        @Override
        public ProcessedEvidence process(byte[] original, String declaredMediaType) {
            if (mode.get() == Mode.MALICIOUS) {
                throw new ProcessingException(
                        ProcessingException.Outcome.MALICIOUS,
                        "EVIDENCE_MALWARE_DETECTED");
            }
            if (mode.get() == Mode.UNAVAILABLE) {
                throw new ProcessingException(
                        ProcessingException.Outcome.UNAVAILABLE,
                        "EVIDENCE_SCANNER_UNAVAILABLE");
            }
            String normalized = new String(original, StandardCharsets.UTF_8)
                    .replace("\r\n", "\n").replace('\r', '\n');
            byte[] clean = ("SANITIZED\n" + normalized).getBytes(StandardCharsets.UTF_8);
            String digest = "sha256:" + "a".repeat(64);
            return new ProcessedEvidence(
                    clean,
                    "text/plain; charset=utf-8",
                    "text/plain; charset=utf-8",
                    digest,
                    "sha256:" + "b".repeat(64),
                    "sha256:" + "c".repeat(64),
                    "synthetic-rules-v1",
                    "synthetic-transform-v1");
        }
    }
}
