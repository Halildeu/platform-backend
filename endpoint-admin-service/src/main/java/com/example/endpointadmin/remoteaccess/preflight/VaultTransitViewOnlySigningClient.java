package com.example.endpointadmin.remoteaccess.preflight;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.Arrays;
import java.util.Base64;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Bounded, redirect-free and pinned-CA Vault Transit Ed25519 client.
 * Credential material is obtained from a file sink for each request, never
 * cached, logged, included in exceptions or persisted with signed evidence.
 */
public final class VaultTransitViewOnlySigningClient implements ViewOnlyTransitSigningClient {
    private static final Pattern NAME = Pattern.compile("[a-z0-9][a-z0-9_-]{0,63}");
    private static final int MAX_INPUT_BYTES = 262_144;

    private final URI signUri;
    private final URI keyMetadataUri;
    private final int keyVersion;
    private final Duration requestTimeout;
    private final int maximumResponseBytes;
    private final ViewOnlyVaultTokenSource tokenSource;
    private final HttpClient http;
    private final ObjectMapper mapper;

    public VaultTransitViewOnlySigningClient(ViewOnlyAuthorityProperties properties,
                                             ViewOnlyVaultTokenSource tokenSource) {
        this(properties, tokenSource, productionHttp(properties), new ObjectMapper());
    }

    VaultTransitViewOnlySigningClient(ViewOnlyAuthorityProperties properties,
                                      ViewOnlyVaultTokenSource tokenSource,
                                      HttpClient http,
                                      ObjectMapper mapper) {
        Objects.requireNonNull(properties, "properties").validateActivation();
        this.tokenSource = Objects.requireNonNull(tokenSource, "tokenSource");
        this.http = Objects.requireNonNull(http, "http");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.keyVersion = properties.getVaultTransitKeyVersion();
        this.requestTimeout = Duration.ofSeconds(properties.getVaultRequestTimeoutSeconds());
        this.maximumResponseBytes = properties.getVaultMaximumResponseBytes();
        this.signUri = signUri(properties.getVaultAddress(), properties.getVaultTransitMount(),
                properties.getVaultTransitKey());
        this.keyMetadataUri = keyMetadataUri(properties.getVaultAddress(), properties.getVaultTransitMount(),
                properties.getVaultTransitKey());
    }

    @Override
    public void probeReady() {
        char[] token = tokenSource.readToken();
        if (token == null || token.length == 0) {
            throw unavailable("Vault token source returned no credential", null);
        }
        try {
            HttpRequest request = HttpRequest.newBuilder(keyMetadataUri)
                    .timeout(requestTimeout)
                    .header("X-Vault-Token", new String(token))
                    .GET()
                    .build();
            JsonNode root = readSuccessJson(send(request, "metadata probe"), "metadata probe");
            JsonNode data = root.path("data");
            JsonNode pinnedKey = data.path("keys").path(Integer.toString(keyVersion));
            if (!"ed25519".equals(data.path("type").asText())
                    || !pinnedKey.isObject()
                    || !pinnedKey.path("public_key").isTextual()
                    || pinnedKey.path("public_key").asText().isBlank()) {
                throw unavailable("Vault Transit key type or pinned version is unavailable", null);
            }
        } finally {
            Arrays.fill(token, '\0');
        }
    }

    @Override
    public byte[] sign(byte[] preAuthenticationEncoding) {
        if (preAuthenticationEncoding == null || preAuthenticationEncoding.length == 0
                || preAuthenticationEncoding.length > MAX_INPUT_BYTES) {
            throw unavailable("Vault Transit input is empty or exceeds its hard bound", null);
        }

        char[] token = tokenSource.readToken();
        if (token == null || token.length == 0) {
            throw unavailable("Vault token source returned no credential", null);
        }
        try {
            String body = "{\"input\":\""
                    + Base64.getEncoder().encodeToString(preAuthenticationEncoding)
                    + "\",\"key_version\":" + keyVersion + "}";
            HttpRequest request = HttpRequest.newBuilder(signUri)
                    .timeout(requestTimeout)
                    .header("Content-Type", "application/json")
                    .header("X-Vault-Token", new String(token))
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                    .build();
            JsonNode root = readSuccessJson(send(request, "signing"), "signing");
            JsonNode signatureNode = root.path("data").path("signature");
            if (!signatureNode.isTextual()) {
                throw unavailable("Vault Transit response is missing data.signature", null);
            }
            String prefix = "vault:v" + keyVersion + ":";
            String encoded = signatureNode.textValue();
            if (!encoded.startsWith(prefix) || encoded.length() <= prefix.length()) {
                throw unavailable("Vault Transit signature version does not match the pinned key version", null);
            }
            byte[] signature;
            try {
                signature = Base64.getDecoder().decode(encoded.substring(prefix.length()));
            } catch (IllegalArgumentException invalidBase64) {
                throw unavailable("Vault Transit signature is not valid base64", invalidBase64);
            }
            if (signature.length != 64) {
                Arrays.fill(signature, (byte) 0);
                throw unavailable("Vault Transit did not return one 64-byte Ed25519 signature", null);
            }
            return signature;
        } finally {
            Arrays.fill(token, '\0');
        }
    }

    private HttpResponse<InputStream> send(HttpRequest request, String operation) {
        try {
            return http.send(request, HttpResponse.BodyHandlers.ofInputStream());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw unavailable("Vault Transit " + operation + " was interrupted", interrupted);
        } catch (Exception transportFailure) {
            throw unavailable("Vault Transit " + operation + " transport failed closed", transportFailure);
        }
    }

    private JsonNode readSuccessJson(HttpResponse<InputStream> response, String operation) {
        if (response.statusCode() / 100 != 2) {
            closeQuietly(response.body());
            throw unavailable("Vault Transit " + operation + " returned HTTP " + response.statusCode(), null);
        }
        return readBounded(response.body());
    }

    private JsonNode readBounded(InputStream body) {
        try (InputStream in = body) {
            byte[] bytes = in.readNBytes(maximumResponseBytes + 1);
            if (bytes.length > maximumResponseBytes) {
                throw unavailable("Vault Transit response exceeds its hard size bound", null);
            }
            return mapper.readTree(bytes);
        } catch (ViewOnlyAuthorityException known) {
            throw known;
        } catch (Exception parseFailure) {
            throw unavailable("Vault Transit response is not valid bounded JSON", parseFailure);
        }
    }

    private static HttpClient productionHttp(ViewOnlyAuthorityProperties properties) {
        return HttpClient.newBuilder()
                .sslContext(pinnedCaSslContext(Path.of(properties.getVaultCaCertificateFile())))
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(Duration.ofSeconds(properties.getVaultConnectTimeoutSeconds()))
                .build();
    }

    private static URI signUri(String address, String mount, String key) {
        if (!NAME.matcher(mount).matches() || !NAME.matcher(key).matches()) {
            throw new IllegalStateException("VIEW_ONLY authority activation denied: invalid Transit mount or key name");
        }
        String normalized = address.endsWith("/") ? address.substring(0, address.length() - 1) : address;
        return URI.create(normalized + "/v1/" + mount + "/sign/" + key);
    }

    private static URI keyMetadataUri(String address, String mount, String key) {
        if (!NAME.matcher(mount).matches() || !NAME.matcher(key).matches()) {
            throw new IllegalStateException("VIEW_ONLY authority activation denied: invalid Transit mount or key name");
        }
        String normalized = address.endsWith("/") ? address.substring(0, address.length() - 1) : address;
        return URI.create(normalized + "/v1/" + mount + "/keys/" + key);
    }

    static SSLContext pinnedCaSslContext(Path certificateFile) {
        try {
            byte[] pem = Files.readAllBytes(certificateFile.toAbsolutePath().normalize());
            if (pem.length == 0 || pem.length > 131_072) {
                throw new IllegalStateException("Vault CA certificate is absent or outside its hard size bound");
            }
            CertificateFactory factory = CertificateFactory.getInstance("X.509");
            X509Certificate certificate = (X509Certificate) factory.generateCertificate(new ByteArrayInputStream(pem));
            Arrays.fill(pem, (byte) 0);
            KeyStore store = KeyStore.getInstance(KeyStore.getDefaultType());
            store.load(null, null);
            store.setCertificateEntry("view-only-vault-ca", certificate);
            TrustManagerFactory trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            trust.init(store);
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(null, trust.getTrustManagers(), null);
            return context;
        } catch (Exception failure) {
            throw new IllegalStateException("VIEW_ONLY authority activation denied: pinned Vault CA is invalid", failure);
        }
    }

    private static void closeQuietly(InputStream stream) {
        try {
            stream.close();
        } catch (Exception ignored) {
            // Response content is deliberately discarded and never surfaced.
        }
    }

    private static ViewOnlyAuthorityException unavailable(String message, Throwable cause) {
        return new ViewOnlyAuthorityException(ViewOnlyAuthorityError.SIGNING_UNAVAILABLE, message, cause);
    }
}
