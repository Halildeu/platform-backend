package com.example.endpointadmin.tpmattest;

import com.sun.net.httpserver.HttpServer;
import org.bouncycastle.asn1.x509.BasicConstraints;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.KeyUsage;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.net.ssl.X509TrustManager;
import java.io.IOException;
import java.io.OutputStream;
import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.security.auth.x500.X500Principal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Faz 22.3B gate-4b — Vault PKI client. HTTP contract via the JDK {@link HttpServer} (no WireMock
 * dep, injected plain {@link HttpClient}); pinned-CA trust decision via the {@link X509TrustManager}
 * directly. Real-Vault live signing = gate-5 operator drill.
 */
class VaultPkiClientTest {

    private HttpServer server;
    private int port;
    private final AtomicInteger loginCalls = new AtomicInteger();
    private final AtomicInteger signCalls = new AtomicInteger();
    private final AtomicReference<String> lastSignBody = new AtomicReference<>();
    private final AtomicReference<String> lastSignToken = new AtomicReference<>();
    private volatile int signStatusFirst = 200;
    private volatile String signBody;
    private volatile String loginBody = "{\"auth\":{\"client_token\":\"s.toktok\",\"lease_duration\":3600,\"renewable\":true}}";

    private static String validCertPem; // a currently-valid self-signed cert for the happy path

    @BeforeEach
    void setUp() throws Exception {
        if (validCertPem == null) {
            validCertPem = pem(selfSigned("CN=device", days(-1), days(1)));
        }
        signBody = "{\"data\":{\"certificate\":" + jsonStr(validCertPem) + ",\"serial_number\":\"de:ad\"}}";
        loginCalls.set(0); signCalls.set(0); signStatusFirst = 200;

        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/auth/approle/login", ex -> {
            loginCalls.incrementAndGet();
            respond(ex, 200, loginBody);
        });
        server.createContext("/v1/pki_int/sign/tpm-device", ex -> {
            int n = signCalls.incrementAndGet();
            lastSignToken.set(ex.getRequestHeaders().getFirst("X-Vault-Token"));
            lastSignBody.set(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            int status = (n == 1) ? signStatusFirst : 200;
            respond(ex, status, status / 100 == 2 ? signBody : "{\"errors\":[\"denied\"]}");
        });
        server.start();
        port = server.getAddress().getPort();
    }

    @AfterEach
    void tearDown() { if (server != null) server.stop(0); }

    private VaultPkiClient client(int maxBytes) {
        VaultPkiProperties props = new VaultPkiProperties(false, "http://127.0.0.1:" + port,
                "rid", "sid", "pki_int", "tpm-device", null,
                Duration.ofSeconds(2), Duration.ofSeconds(2), Duration.ofSeconds(30), maxBytes);
        return new VaultPkiClient(props, HttpClient.newHttpClient());
    }

    @Test
    void signCsr_happyPath_returnsCertAndSendsCorrectRequests() {
        String out = client(65536).signCsr("-----BEGIN CERTIFICATE REQUEST-----\nMII...\n-----END CERTIFICATE REQUEST-----");
        assertThat(out).contains("BEGIN CERTIFICATE");
        assertThat(loginCalls.get()).isEqualTo(1);
        assertThat(signCalls.get()).isEqualTo(1);
        assertThat(lastSignToken.get()).isEqualTo("s.toktok");          // X-Vault-Token forwarded
        assertThat(lastSignBody.get()).contains("\"csr\":").contains("\"format\":\"pem\"");
    }

    @Test
    void token_isCachedAcrossCalls_singleLogin() {
        VaultPkiClient c = client(65536);
        c.signCsr("csr1");
        c.signCsr("csr2");
        assertThat(loginCalls.get()).as("token cached → one login for two signs").isEqualTo(1);
        assertThat(signCalls.get()).isEqualTo(2);
    }

    @Test
    void sign403_clearsTokenRelogsInAndRetriesOnce() {
        signStatusFirst = 403;
        String out = client(65536).signCsr("csr");
        assertThat(out).contains("BEGIN CERTIFICATE");
        assertThat(loginCalls.get()).as("403 → clear + re-login").isEqualTo(2);
        assertThat(signCalls.get()).as("retried exactly once").isEqualTo(2);
    }

    @Test
    void sign500_failsClosed() {
        signBody = "{}"; signStatusFirst = 500;
        // both attempts 500 (n==1 →500; n>=2 →200 but we never retry on 500, only 403)
        assertThatThrownBy(() -> client(65536).signCsr("csr"))
                .isInstanceOf(VaultPkiClient.VaultPkiException.class)
                .hasMessageContaining("HTTP 500");
    }

    @Test
    void missingCertificateField_failsClosed() {
        signBody = "{\"data\":{\"serial_number\":\"x\"}}";
        assertThatThrownBy(() -> client(65536).signCsr("csr"))
                .isInstanceOf(VaultPkiClient.VaultPkiException.class)
                .hasMessageContaining("certificate");
    }

    @Test
    void nonPemCertificate_failsClosed() {
        signBody = "{\"data\":{\"certificate\":\"not-a-pem\"}}";
        assertThatThrownBy(() -> client(65536).signCsr("csr"))
                .isInstanceOf(VaultPkiClient.VaultPkiException.class)
                .hasMessageContaining("valid X.509");
    }

    @Test
    void oversizedResponse_failsClosedAtCap() {
        // a 64-byte cap with a multi-KB cert response → capped
        assertThatThrownBy(() -> client(64).signCsr("csr"))
                .isInstanceOf(VaultPkiClient.VaultPkiException.class)
                .hasMessageContaining("capped");
    }

    // ───────────────────────── pinned-CA trust decision ─────────────────────────

    @Test
    void pinnedCaTrustManager_acceptsCaSigned_rejectsOther() throws Exception {
        KeyPair caKp = rsa();
        X509Certificate ca = caCert("CN=Vault Test CA", caKp, days(-1), days(2));
        KeyPair leafKp = rsa();
        X509Certificate leaf = signedBy("CN=vault.local", leafKp, "CN=Vault Test CA", caKp, days(-1), days(1));

        X509TrustManager tm = VaultPkiClient.pinnedCaTrustManager(pem(ca));
        // a chain that ends at the pinned CA → trusted
        org.assertj.core.api.Assertions.assertThatCode(
                () -> tm.checkServerTrusted(new X509Certificate[]{leaf, ca}, "RSA")).doesNotThrowAnyException();
        // a cert NOT signed by the pinned CA → rejected (no system-trust fallback)
        X509Certificate other = selfSigned("CN=evil", days(-1), days(1));
        assertThatThrownBy(() -> tm.checkServerTrusted(new X509Certificate[]{other}, "RSA"))
                .isInstanceOf(java.security.cert.CertificateException.class);
    }

    // ───────────────────────────── helpers ─────────────────────────────

    private static void respond(com.sun.net.httpserver.HttpExchange ex, int status, String body) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(status, b.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(b); }
    }

    private static KeyPair rsa() throws Exception {
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
        kpg.initialize(2048);
        return kpg.generateKeyPair();
    }

    private static Date days(int d) { return Date.from(Instant.now().plus(Duration.ofDays(d))); }

    private static X509Certificate selfSigned(String dn, Date from, Date to) throws Exception {
        return selfSignedWith(dn, rsa(), from, to);
    }

    private static X509Certificate selfSignedWith(String dn, KeyPair kp, Date from, Date to) throws Exception {
        return signedBy(dn, kp, dn, kp, from, to);
    }

    /** A self-signed CA cert (BasicConstraints CA:true + keyCertSign) usable as a PKIX trust anchor. */
    private static X509Certificate caCert(String dn, KeyPair kp, Date from, Date to) throws Exception {
        var builder = new JcaX509v3CertificateBuilder(new X500Principal(dn),
                BigInteger.valueOf(System.nanoTime()), from, to, new X500Principal(dn), kp.getPublic());
        builder.addExtension(Extension.basicConstraints, true, new BasicConstraints(true));
        builder.addExtension(Extension.keyUsage, true, new KeyUsage(KeyUsage.keyCertSign | KeyUsage.cRLSign));
        ContentSigner signer = new JcaContentSignerBuilder("SHA256withRSA").build(kp.getPrivate());
        return new JcaX509CertificateConverter().getCertificate(builder.build(signer));
    }

    private static X509Certificate signedBy(String subjectDn, KeyPair subjectKp, String issuerDn, KeyPair issuerKp,
                                            Date from, Date to) throws Exception {
        var builder = new JcaX509v3CertificateBuilder(new X500Principal(issuerDn),
                BigInteger.valueOf(System.nanoTime()), from, to, new X500Principal(subjectDn), subjectKp.getPublic());
        ContentSigner signer = new JcaContentSignerBuilder("SHA256withRSA").build(issuerKp.getPrivate());
        return new JcaX509CertificateConverter().getCertificate(builder.build(signer));
    }

    private static String pem(X509Certificate cert) throws Exception {
        return "-----BEGIN CERTIFICATE-----\n"
                + Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(cert.getEncoded())
                + "\n-----END CERTIFICATE-----\n";
    }

    private static String jsonStr(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\"";
    }
}
