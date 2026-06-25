package com.example.endpointadmin.tpmattest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Faz 22.3B (ADR-0039) gate-4b — Vault PKI issuance client (verifier-passed CSR → signed
 * clientAuth cert). The CSR is already V9-validated (gate-4a-2.4) before this is called.
 * <b>Disabled-by-default</b>; wired at gate-4d.
 *
 * <p>Built on the JDK {@link HttpClient} (chosen over Spring RestClient for full hardening control —
 * a <b>bounded</b> response read, redirects OFF, and a pinned-CA-only {@link SSLContext}):
 * <ul>
 *   <li><b>TLS</b>: trusts ONLY the configured pinned Vault CA (no system-trust fallback);
 *       redirects disabled (no cross-host); connect + per-request read timeouts.</li>
 *   <li><b>AppRole token</b>: cached + single-flight (one login under a lock; no refresh stampede);
 *       proactive re-login before expiry (clock-skew buffer); on {@code 403} clear + re-login + a
 *       single retry, then fail-closed.</li>
 *   <li><b>Fail-closed</b>: any non-2xx, oversized body, missing field, or non-PEM/invalid certificate
 *       throws {@link VaultPkiException}; no partial or fallback issuance. The token / secretId are
 *       never logged or echoed in exceptions.</li>
 * </ul>
 * Real-Vault live signing is exercised by the gate-5 operator drill; this client is contract-validated
 * with WireMock + a pinned-CA SSLContext unit test.
 */
public final class VaultPkiClient {

    private static final Logger log = LoggerFactory.getLogger(VaultPkiClient.class);

    private final VaultPkiProperties props;
    private final HttpClient http;
    private final ObjectMapper mapper = new ObjectMapper();

    private final ReentrantLock tokenLock = new ReentrantLock();
    private volatile CachedToken cachedToken;

    private record CachedToken(String token, long expiresAtMillis) {}

    public VaultPkiClient(VaultPkiProperties props) {
        this(props, HttpClient.newBuilder()
                .sslContext(pinnedCaSslContext(props.caCertPem()))
                .followRedirects(HttpClient.Redirect.NEVER) // never follow a redirect to another host
                .connectTimeout(props.connectTimeout())
                .build());
    }

    /** Test seam: inject the {@link HttpClient} (a WireMock-pointed plain client in unit tests). */
    VaultPkiClient(VaultPkiProperties props, HttpClient http) {
        this.props = props;
        this.http = http;
    }

    /** Submit a (V9-validated) PKCS#10 CSR (PEM) to Vault PKI; return the issued certificate (PEM). */
    public String signCsr(String csrPem) {
        return signCsr(csrPem, null);
    }

    /**
     * Faz 22.6 #548 Phase 1.5 (Codex {@code 019eff93} P0-2) — sign a (V9-validated) CSR with a
     * SERVER-SUPPLIED {@code uri_sans} (e.g. {@code tpm:{ek_pub_sha256}}). The CSR itself carries NO
     * SAN ({@link TpmCsrPolicy} rejects an extensionRequest), so the issued cert's device identity
     * comes ONLY from this server-injected SAN — never caller input. The Vault role MUST permit it
     * ({@code allowed_uri_sans=tpm:*}); the caller then exact-matches the returned cert's SAN against
     * the L1-bound EK identity and fails closed on any mismatch.
     *
     * @param uriSan a single URI SAN to request, or {@code null}/blank for none (legacy 1-arg behavior)
     */
    public String signCsr(String csrPem, String uriSan) {
        if (csrPem == null || csrPem.isBlank()) {
            throw new VaultPkiException("CSR required");
        }
        StringBuilder bodyBuilder = new StringBuilder("{\"csr\":")
                .append(jsonString(csrPem)).append(",\"format\":\"pem\"");
        if (uriSan != null && !uriSan.isBlank()) {
            bodyBuilder.append(",\"uri_sans\":").append(jsonString(uriSan));
        }
        String body = bodyBuilder.append('}').toString();
        URI uri = uri("/v1/" + props.mount() + "/sign/" + props.role());

        HttpResponse<InputStream> resp = send(uri, body, currentToken());
        if (resp.statusCode() == 403) {
            // token may be expired/revoked — clear, re-login once, retry exactly once
            clearToken();
            resp = send(uri, body, currentToken());
        }
        if (resp.statusCode() / 100 != 2) {
            throw new VaultPkiException("Vault sign failed: HTTP " + resp.statusCode());
        }
        JsonNode root = readJson(resp);
        JsonNode cert = root.path("data").path("certificate");
        if (cert.isMissingNode() || !cert.isTextual() || cert.asText().isBlank()) {
            throw new VaultPkiException("Vault sign response missing data.certificate");
        }
        String pem = cert.asText();
        requireValidCertificate(pem); // fail-closed on non-PEM / unparseable
        return pem;
    }

    // ───────────────────────────── token lifecycle ─────────────────────────────

    private String currentToken() {
        CachedToken t = cachedToken;
        long now = System.currentTimeMillis();
        if (t != null && now < t.expiresAtMillis()) {
            return t.token();
        }
        tokenLock.lock(); // single-flight: only one thread logs in
        try {
            t = cachedToken;
            now = System.currentTimeMillis();
            if (t != null && now < t.expiresAtMillis()) {
                return t.token();
            }
            return login();
        } finally {
            tokenLock.unlock();
        }
    }

    private void clearToken() {
        tokenLock.lock();
        try {
            cachedToken = null;
        } finally {
            tokenLock.unlock();
        }
    }

    /** MUST hold tokenLock. */
    private String login() {
        String body = "{\"role_id\":" + jsonString(props.roleId())
                + ",\"secret_id\":" + jsonString(props.secretId()) + "}";
        HttpResponse<InputStream> resp = send(uri("/v1/auth/approle/login"), body, null);
        if (resp.statusCode() / 100 != 2) {
            throw new VaultPkiException("Vault AppRole login failed: HTTP " + resp.statusCode());
        }
        JsonNode auth = readJson(resp).path("auth");
        String token = auth.path("client_token").asText(null);
        if (token == null || token.isBlank()) {
            throw new VaultPkiException("Vault login response missing auth.client_token");
        }
        long leaseSec = auth.path("lease_duration").asLong(0);
        long skewSec = Math.max(0, props.tokenRenewSkew().toSeconds());
        // proactively expire the cache a skew-margin before the real lease end (min 1s validity)
        long ttlMillis = Math.max(1_000L, (leaseSec - skewSec) * 1_000L);
        cachedToken = new CachedToken(token, System.currentTimeMillis() + ttlMillis);
        return token;
    }

    // ───────────────────────────── transport ─────────────────────────────

    private HttpResponse<InputStream> send(URI uri, String jsonBody, String token) {
        HttpRequest.Builder b = HttpRequest.newBuilder(uri)
                .timeout(props.readTimeout())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody, StandardCharsets.UTF_8));
        if (token != null) {
            b.header("X-Vault-Token", token);
        }
        try {
            return http.send(b.build(), HttpResponse.BodyHandlers.ofInputStream());
        } catch (VaultPkiException e) {
            throw e;
        } catch (Exception e) {
            // redact: never include the request body / token in the surfaced message
            throw new VaultPkiException("Vault transport error: " + e.getClass().getSimpleName());
        }
    }

    /** Read the response with a HARD size cap (bounded stream) and parse JSON; fail-closed on overflow. */
    private JsonNode readJson(HttpResponse<InputStream> resp) {
        int cap = props.maxResponseBytes();
        try (InputStream in = resp.body()) {
            byte[] buf = in.readNBytes(cap + 1);
            if (buf.length > cap) {
                throw new VaultPkiException("Vault response exceeds " + cap + " bytes (capped)");
            }
            return mapper.readTree(buf);
        } catch (VaultPkiException e) {
            throw e;
        } catch (Exception e) {
            throw new VaultPkiException("Vault response read/parse error: " + e.getClass().getSimpleName());
        }
    }

    private void requireValidCertificate(String pem) {
        try {
            CertificateFactory cf = CertificateFactory.getInstance("X.509");
            X509Certificate cert = (X509Certificate) cf.generateCertificate(
                    new ByteArrayInputStream(pem.getBytes(StandardCharsets.UTF_8)));
            cert.checkValidity(); // not expired / not-yet-valid at issue time
        } catch (Exception e) {
            throw new VaultPkiException("issued certificate is not a valid X.509 PEM: " + e.getClass().getSimpleName());
        }
    }

    private URI uri(String path) {
        String base = props.baseUrl().endsWith("/")
                ? props.baseUrl().substring(0, props.baseUrl().length() - 1) : props.baseUrl();
        return URI.create(base + path);
    }

    /** A pinned-CA-only trust store: trusts the configured CA, nothing from the system trust store. */
    private static SSLContext pinnedCaSslContext(String caPem) {
        try {
            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(null, new javax.net.ssl.TrustManager[]{pinnedCaTrustManager(caPem)}, null);
            return ctx;
        } catch (Exception e) {
            throw new IllegalStateException("cannot build pinned-CA SSLContext for Vault: " + e.getClass().getSimpleName(), e);
        }
    }

    /**
     * The pinned-CA {@link javax.net.ssl.X509TrustManager}: trusts ONLY the configured CA (no system
     * fallback). Package-private so the trust decision (accept CA-signed, reject anything else) is
     * unit-tested directly without a live TLS handshake.
     */
    static javax.net.ssl.X509TrustManager pinnedCaTrustManager(String caPem) {
        try {
            CertificateFactory cf = CertificateFactory.getInstance("X.509");
            X509Certificate ca = (X509Certificate) cf.generateCertificate(
                    new ByteArrayInputStream(caPem.getBytes(StandardCharsets.UTF_8)));
            KeyStore ks = KeyStore.getInstance(KeyStore.getDefaultType());
            ks.load(null, null);
            ks.setCertificateEntry("vault-ca", ca);
            TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            tmf.init(ks);
            for (javax.net.ssl.TrustManager tm : tmf.getTrustManagers()) {
                if (tm instanceof javax.net.ssl.X509TrustManager x) {
                    return x;
                }
            }
            throw new IllegalStateException("no X509TrustManager produced");
        } catch (Exception e) {
            throw new IllegalStateException("cannot build pinned-CA trust manager for Vault: " + e.getClass().getSimpleName(), e);
        }
    }

    private static String jsonString(String raw) {
        StringBuilder sb = new StringBuilder(raw.length() + 2).append('"');
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
                }
            }
        }
        return sb.append('"').toString();
    }

    /** Fail-closed Vault issuance error. Messages are pre-redacted (never carry token/secretId/body). */
    public static final class VaultPkiException extends RuntimeException {
        public VaultPkiException(String message) { super(message); }
    }
}
