package com.example.endpointadmin.tpmattest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.io.ByteArrayInputStream;
import java.security.MessageDigest;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Faz 22.3B gate-4d-1 — wire-level test of {@code POST /api/v1/agent/enrollments/tpm/nonce} via a
 * standalone MockMvc (controller + uniform-403 advice only — no app context / security / OpenFGA).
 * Happy path over the swtpm golden EK/AK; the fail-closed uniform-403 surface (no oracle) + 413.
 */
class TpmEnrollmentControllerTest {

    static final UUID TENANT = UUID.fromString("11111111-1111-1111-1111-111111111111");
    static final UUID OTHER_TENANT = UUID.fromString("22222222-2222-2222-2222-222222222222");

    private static JsonNode golden;
    private static String ekCertB64, ekPubB64, akPubB64, akNameB64;

    private MockMvc mvc;
    private StubScopeResolver scopeResolver;
    private final ObjectMapper json = new ObjectMapper().registerModule(new JavaTimeModule());

    /** Controllable scope resolver stub (set {@code next}/{@code error} per test). */
    static final class StubScopeResolver implements TpmEnrollmentScopeResolver {
        volatile Scope next;
        volatile RuntimeException error;
        @Override public Scope resolve(String token) {
            if (error != null) throw error;
            return next;
        }
    }

    @BeforeAll
    static void loadGolden() throws Exception {
        try (var in = TpmEnrollmentControllerTest.class.getResourceAsStream("/tpmattest/golden-rsa.json")) {
            golden = new ObjectMapper().readTree(in);
        }
        ekCertB64 = golden.get("ekCertDer").asText();
        ekPubB64 = golden.get("ekPub").asText();
        akPubB64 = golden.get("akPub").asText();
        akNameB64 = b64(HexFormat.of().parseHex(golden.get("akNameHex").asText()));
    }

    @BeforeEach
    void setUp() throws Exception {
        Clock clock = Clock.fixed(Instant.parse("2026-06-15T00:00:00Z"), ZoneOffset.UTC);
        TpmAttestProperties props = new TpmAttestProperties(true, Set.of(TENANT), Duration.ofMinutes(5),
                Duration.ofSeconds(30), List.of(), 3072, 256, Set.of("HIGH"));
        scopeResolver = new StubScopeResolver();
        scopeResolver.next = new TpmEnrollmentScopeResolver.Scope(TENANT, UUID.randomUUID(), null, "scope-ok");

        TpmEnrollmentController controller = new TpmEnrollmentController(
                props, scopeResolver, singletonProvider(goldenEkChainValidator()),
                new TpmMakeCredential(), new InMemoryTpmNonceStore(clock),
                new TpmEnrollmentRateLimiter(clock), clock);

        MappingJackson2HttpMessageConverter conv = new MappingJackson2HttpMessageConverter();
        conv.setObjectMapper(json);
        mvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new TpmEnrollmentExceptionAdvice())
                .setMessageConverters(conv)
                .build();
    }

    private Map<String, Object> body() {
        Map<String, Object> b = new HashMap<>();
        b.put("enrollmentToken", "test-enrollment-token");
        b.put("ekCert", ekCertB64);
        b.put("ekCertChain", List.of());
        b.put("ekPub", ekPubB64);
        b.put("akPub", akPubB64);
        b.put("akName", akNameB64);
        return b;
    }

    private ResultActions postNonce(Map<String, Object> b) throws Exception {
        return mvc.perform(post("/api/v1/agent/enrollments/tpm/nonce")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(b)));
    }

    @Test
    void nonce_happyPath_returnsChallenge() throws Exception {
        postNonce(body())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nonceId").isNotEmpty())
                .andExpect(jsonPath("$.nonce").isNotEmpty())
                .andExpect(jsonPath("$.credBlob").isNotEmpty())
                .andExpect(jsonPath("$.encSecret").isNotEmpty());
    }

    @Test
    void featureDisabledForTenant_uniform403() throws Exception {
        scopeResolver.next = new TpmEnrollmentScopeResolver.Scope(OTHER_TENANT, UUID.randomUUID(), null, "scope-off");
        postNonce(body())
                .andExpect(status().isForbidden())
                .andExpect(content().json("{\"status\":\"denied\"}")); // fixed body, no deny code
    }

    @Test
    void invalidToken_uniform403() throws Exception {
        scopeResolver.error = new TpmAttestException(TpmDenyCode.DEVICE_NOT_ELIGIBLE, "no pending enrollment");
        postNonce(body()).andExpect(status().isForbidden()).andExpect(content().json("{\"status\":\"denied\"}"));
    }

    @Test
    void ekCertKeyNotMatchingEkPub_uniform403() throws Exception {
        Map<String, Object> b = body();
        b.put("ekPub", akPubB64); // AK pub as "ekPub" → cert key != ekPub key → EK_UNTRUSTED
        postNonce(b).andExpect(status().isForbidden()).andExpect(content().json("{\"status\":\"denied\"}"));
    }

    @Test
    void akNameMismatch_uniform403() throws Exception {
        Map<String, Object> b = body();
        byte[] bad = HexFormat.of().parseHex(golden.get("akNameHex").asText());
        bad[bad.length - 1] ^= 0x01;
        b.put("akName", b64(bad));
        postNonce(b).andExpect(status().isForbidden()).andExpect(content().json("{\"status\":\"denied\"}"));
    }

    @Test
    void oversizedBody_413() throws Exception {
        Map<String, Object> b = body();
        b.put("ekCertChain", Collections.nCopies(16, "A".repeat(8000))); // total > MAX_BODY_BYTES
        postNonce(b).andExpect(status().isPayloadTooLarge());
    }

    // ───────────────────────────── helpers ─────────────────────────────

    private static TpmEkChainValidator goldenEkChainValidator() throws Exception {
        X509Certificate ca = (X509Certificate) CertificateFactory.getInstance("X.509")
                .generateCertificate(new ByteArrayInputStream(Base64.getDecoder().decode(golden.get("caCertDer").asText())));
        String pin = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(ca.getEncoded()));
        return new TpmEkChainValidator(Set.of(pin), List.of(ca));
    }

    private static <T> ObjectProvider<T> singletonProvider(T value) {
        return new ObjectProvider<>() {
            @Override public T getObject() { return value; }
            @Override public T getObject(Object... args) { return value; }
            @Override public T getIfAvailable() { return value; }
            @Override public T getIfUnique() { return value; }
        };
    }

    private static String b64(byte[] b) { return Base64.getEncoder().encodeToString(b); }
}
