package com.example.endpointadmin.tpmattest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.bouncycastle.pkcs.jcajce.JcaPKCS10CertificationRequestBuilder;
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
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Faz 22.3B gate-4d-2 — /attest orchestration (standalone MockMvc). The deep crypto verifiers are
 * golden-validated in their own slices (#654/#657/#659); here we drive the FLOW (order, V1-consume,
 * MUST#1 bind, fail-closed each step, the PENDING→IN_PROGRESS→FAILED state machine, uniform-403) with
 * a mocked nonce store + completion service + golden TPM inputs. The full happy path → Vault sign
 * needs a real TPM to PoP-sign the CSR (gate-5 nightly), so it is not asserted here.
 */
class TpmAttestEndpointTest {

    static final UUID TENANT = UUID.fromString("11111111-1111-1111-1111-111111111111");
    static final UUID OTHER_TENANT = UUID.fromString("22222222-2222-2222-2222-222222222222");
    static final UUID ENROLL_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");

    private static JsonNode golden;
    private static byte[] goldenNonce, goldenSecret, goldenAkName;

    private MockMvc mvc;
    private TpmEnrollmentControllerTest.StubScopeResolver scopeResolver;
    private TpmNonceStore nonceStore;
    private TpmEnrollmentCompletionService completion;
    private final ObjectMapper json = new ObjectMapper().registerModule(new JavaTimeModule());

    @BeforeAll
    static void loadGolden() throws Exception {
        try (var in = TpmAttestEndpointTest.class.getResourceAsStream("/tpmattest/golden-rsa.json")) {
            golden = new ObjectMapper().readTree(in);
        }
        goldenNonce = HexFormat.of().parseHex(golden.get("nonceHex").asText());
        goldenSecret = Base64.getDecoder().decode(golden.get("activationExpectedB64").asText());
        goldenAkName = HexFormat.of().parseHex(golden.get("akNameHex").asText());
    }

    @BeforeEach
    void setUp() throws Exception {
        Clock clock = Clock.fixed(Instant.parse("2026-06-15T00:00:00Z"), ZoneOffset.UTC);
        TpmAttestProperties props = new TpmAttestProperties(true, Set.of(TENANT), Duration.ofMinutes(5),
                Duration.ofSeconds(30), List.of(), 3072, 256, Set.of("HIGH"));
        scopeResolver = new TpmEnrollmentControllerTest.StubScopeResolver();
        scopeResolver.next = new TpmEnrollmentScopeResolver.Scope(TENANT, ENROLL_ID, null, "scope-ok");
        nonceStore = mock(TpmNonceStore.class);
        completion = mock(TpmEnrollmentCompletionService.class);

        TpmEnrollmentController controller = new TpmEnrollmentController(
                props, scopeResolver, emptyProvider(), new TpmMakeCredential(), nonceStore,
                new TpmEnrollmentRateLimiter(clock), clock, emptyProvider(), emptyProvider(), completion);

        MappingJackson2HttpMessageConverter conv = new MappingJackson2HttpMessageConverter();
        conv.setObjectMapper(json);
        mvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new TpmEnrollmentExceptionAdvice())
                .setMessageConverters(conv)
                .build();
    }

    /** Full golden envelope; tests override individual fields. */
    private Map<String, Object> body() {
        Map<String, Object> b = new HashMap<>();
        b.put("schema", TpmAttestEnvelope.SCHEMA_V2);
        b.put("enrollmentToken", "tok");
        b.put("deviceRef", "dev-1");
        b.put("nonceId", "n-1");
        b.put("ekCert", golden.get("ekCertDer").asText());
        b.put("ekCertChain", List.of());
        b.put("akPub", golden.get("akPub").asText());
        b.put("akName", b64(goldenAkName));
        b.put("activatedSecret", b64(goldenSecret));
        b.put("certifyInfo", golden.get("certifyAttest").asText());
        b.put("certifySig", golden.get("certifySig").asText());
        b.put("quote", golden.get("quoteAttest").asText());
        b.put("quoteSig", golden.get("quoteSig").asText());
        b.put("pcrs", Map.of());
        b.put("deviceKeyPub", golden.get("devkeyPub").asText());
        b.put("csrDer", freshCsrB64); // a CSR whose key != golden device key (binding will deny)
        return b;
    }

    private ResultActions postAttest(Map<String, Object> b) throws Exception {
        return mvc.perform(post("/api/v1/agent/enrollments/tpm/attest")
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(b)));
    }

    private void stubConsume(byte[] nonce, byte[] secret, byte[] akName) {
        when(nonceStore.consume(any(), any())).thenReturn(Optional.of(new TpmNonceStore.Consumed(nonce, secret, akName)));
    }

    @Test
    void featureDisabled_uniform403_noNonceConsumed() throws Exception {
        scopeResolver.next = new TpmEnrollmentScopeResolver.Scope(OTHER_TENANT, ENROLL_ID, null, "scope-off");
        postAttest(body()).andExpect(status().isForbidden()).andExpect(content().json("{\"status\":\"denied\"}"));
        verify(nonceStore, never()).consume(any(), any());
        verify(completion, never()).markInProgress(any());
    }

    @Test
    void nonceInvalid_uniform403_noStateChange() throws Exception {
        when(nonceStore.consume(any(), any())).thenReturn(Optional.empty());
        postAttest(body()).andExpect(status().isForbidden());
        verify(completion, never()).markInProgress(any()); // V1 fails before the state machine → enrollment untouched
    }

    @Test
    void akBindingMismatch_uniform403_markFailed() throws Exception {
        byte[] wrongAkName = goldenAkName.clone();
        wrongAkName[wrongAkName.length - 1] ^= 0x01;
        stubConsume(goldenNonce, goldenSecret, wrongAkName); // MUST#1: stored akName != presented golden AK
        postAttest(body()).andExpect(status().isForbidden()).andExpect(content().json("{\"status\":\"denied\"}"));
        verify(completion).markInProgress(ENROLL_ID);
        verify(completion).markFailed(ENROLL_ID);
        verify(completion, never()).markConsumed(any());
    }

    @Test
    void activationSecretMismatch_uniform403_markFailed() throws Exception {
        byte[] wrongSecret = new byte[16]; // != golden secret → V10 ACTIVATION_FAILED (after MUST#1 passes)
        stubConsume(goldenNonce, wrongSecret, goldenAkName);
        postAttest(body()).andExpect(status().isForbidden());
        verify(completion).markInProgress(ENROLL_ID);
        verify(completion).markFailed(ENROLL_ID);
    }

    @Test
    void deepFlow_passesV1throughV4_thenDeniesAtCsrBinding_markFailed() throws Exception {
        // Golden nonce/secret/akName ⇒ V1(stub)→MUST#1→V10→V5(quote over golden nonce)→(V6 off)→V4(certify)
        // all PASS against real golden crypto; then the CSR key != golden device key ⇒ KEY_NOT_TPM_BOUND.
        stubConsume(goldenNonce, goldenSecret, goldenAkName);
        postAttest(body()).andExpect(status().isForbidden()).andExpect(content().json("{\"status\":\"denied\"}"));
        verify(completion).markInProgress(ENROLL_ID);
        verify(completion).markFailed(ENROLL_ID);    // reached past markInProgress (V1-V4 passed)
        verify(completion, never()).markConsumed(any());
    }

    // ───────────────────────────── helpers ─────────────────────────────

    private static String freshCsrB64;
    static {
        try {
            KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
            kpg.initialize(3072);
            KeyPair kp = kpg.generateKeyPair();
            var builder = new JcaPKCS10CertificationRequestBuilder(new X500Name("CN=device"), kp.getPublic());
            ContentSigner signer = new JcaContentSignerBuilder("SHA256withRSA")
                    .setProvider(new BouncyCastleProvider()).build(kp.getPrivate());
            freshCsrB64 = Base64.getEncoder().encodeToString(builder.build(signer).getEncoded());
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private static <T> ObjectProvider<T> emptyProvider() {
        return new ObjectProvider<>() {
            @Override public T getObject() { throw new org.springframework.beans.factory.NoSuchBeanDefinitionException("none"); }
            @Override public T getObject(Object... args) { throw new org.springframework.beans.factory.NoSuchBeanDefinitionException("none"); }
            @Override public T getIfAvailable() { return null; }
            @Override public T getIfUnique() { return null; }
        };
    }

    private static String b64(byte[] b) { return Base64.getEncoder().encodeToString(b); }
}
