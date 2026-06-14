package com.example.endpointadmin.tpmattest;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.ByteArrayInputStream;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

/**
 * Faz 22.3B (ADR-0039) gate-4d — TPM enrollment L1: {@code POST /api/v1/agent/enrollments/tpm/nonce}.
 * <b>Disabled-by-default</b> (per-tenant {@code enabledForTenant}); no live issuance until an overlay
 * flips it on. Every failure is fail-closed via {@link TpmEnrollmentExceptionAdvice} (uniform 403,
 * deny code audit-only). L2 ({@code /attest}) is gate-4d-2.
 *
 * <p>Flow: volumetric rate-limit (per-IP) → resolve the server-side scope from the bootstrap token →
 * per-scope rate-limit → feature gate → V2 EK cert-chain (+ bind the EK cert key to {@code ekPub}) →
 * V11 AK restricted-signing + Name == {@code akName} → V12 EK/AK algorithm → issue a fresh nonce +
 * an in-process {@code TPM2_MakeCredential} challenge → store {nonce, server-secret, <b>akName</b>}
 * under the scope (the akName binds the activation-proven AK to the L2 quote/certify signer — MUST#1).
 */
@RestController
@RequestMapping("/api/v1/agent/enrollments/tpm")
public class TpmEnrollmentController {

    /** Conservative overall request-body cap (defense beyond per-field @Size). */
    static final int MAX_BODY_BYTES = 96 * 1024;
    private static final int NONCE_BYTES = 20;

    private final TpmAttestProperties properties;
    private final TpmEnrollmentScopeResolver scopeResolver;
    // V2 validator exists only when manufacturer roots are configured (enabled). ObjectProvider keeps
    // the controller boot-safe when the feature is off (no validator bean defined).
    private final ObjectProvider<TpmEkChainValidator> ekChainValidatorProvider;
    private final TpmMakeCredential makeCredential;
    private final TpmNonceStore nonceStore;
    private final TpmEnrollmentRateLimiter rateLimiter;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    public TpmEnrollmentController(TpmAttestProperties properties,
                                  TpmEnrollmentScopeResolver scopeResolver,
                                  ObjectProvider<TpmEkChainValidator> ekChainValidatorProvider,
                                  TpmMakeCredential makeCredential,
                                  TpmNonceStore nonceStore,
                                  TpmEnrollmentRateLimiter rateLimiter,
                                  Clock clock) {
        this.properties = properties;
        this.scopeResolver = scopeResolver;
        this.ekChainValidatorProvider = ekChainValidatorProvider;
        this.makeCredential = makeCredential;
        this.nonceStore = nonceStore;
        this.rateLimiter = rateLimiter;
        this.clock = clock;
    }

    @PostMapping("/nonce")
    public ResponseEntity<TpmAttestChallenge> nonce(@Valid @RequestBody TpmNonceRequest req,
                                                    HttpServletRequest http) {
        enforceBodySize(req);

        String ip = remoteAddress(http);
        if (!rateLimiter.allow("ip:" + ip)) {
            throw new TpmEnrollmentExceptionAdvice.RateLimitedException();
        }

        // Scope is SERVER-DERIVED from the bootstrap token (read-only) — never caller input.
        TpmEnrollmentScopeResolver.Scope scope = scopeResolver.resolve(req.enrollmentToken());
        if (!rateLimiter.allow("scope:" + scope.nonceScope())) {
            throw new TpmEnrollmentExceptionAdvice.RateLimitedException();
        }
        if (!properties.enabledForTenant(scope.tenantId())) {
            throw new TpmAttestException(TpmDenyCode.FEATURE_DISABLED, "tpm-attest disabled for tenant");
        }

        // Parse TPM material.
        X509Certificate ekCert = parseCert(req.ekCert());
        List<X509Certificate> chain = parseChain(req.ekCertChain());
        TpmPublicArea ekPub = TpmPublicArea.parse(decode(req.ekPub()), true);
        TpmPublicArea akPub = TpmPublicArea.parse(decode(req.akPub()), true);
        byte[] akName = decode(req.akName());

        // V2 — EK cert chains to a pinned manufacturer root; bind the cert's key to the presented ekPub.
        TpmEkChainValidator ekChainValidator = ekChainValidatorProvider.getIfAvailable();
        if (ekChainValidator == null) {
            // enabled-for-tenant but no manufacturer-root bundle configured → fail-closed.
            throw new TpmAttestException(TpmDenyCode.EK_UNTRUSTED, "manufacturer-root bundle not configured");
        }
        try {
            ekChainValidator.validate(ekCert, chain);
        } catch (TpmEkChainValidator.EkChainException e) {
            throw new TpmAttestException(TpmDenyCode.EK_UNTRUSTED, "EK chain invalid");
        }
        if (!ekCert.getPublicKey().equals(ekPub.toPublicKey())) {
            throw new TpmAttestException(TpmDenyCode.EK_UNTRUSTED, "EK cert key != presented ekPub key");
        }

        // V11 — AK is a restricted signing key and its recomputed Name equals the presented akName.
        if (!akPub.isRestrictedSigningKey()) {
            throw new TpmAttestException(TpmDenyCode.AK_NOT_RESTRICTED, "AK not a restricted signing key");
        }
        if (!TpmsAttest.constantTimeEquals(akPub.computeName(), akName)) {
            throw new TpmAttestException(TpmDenyCode.AK_NOT_RESTRICTED, "akName != recomputed AK Name");
        }

        // V12 — EK + AK algorithm whitelist (EK/AK floor RSA-2048+/P-256+).
        TpmAlgorithmPolicy.requireKeyMeetsPolicy(ekCert.getPublicKey(), TpmAlgorithmPolicy.Role.EK);
        TpmAlgorithmPolicy.requireKeyMeetsPolicy(akPub.toPublicKey(), TpmAlgorithmPolicy.Role.AK);

        // Issue freshness nonce + the MakeCredential challenge (server-secret only the device's TPM can recover).
        byte[] nonce = new byte[NONCE_BYTES];
        random.nextBytes(nonce);
        String nonceId = UUID.randomUUID().toString();
        PublicKey ekRsa = ekPub.toPublicKey();
        TpmMakeCredential.Challenge challenge =
                makeCredential.issueChallenge(ekRsa, ekPub.nameAlg(), akName);

        Instant exp = clock.instant().plus(properties.nonceTtl());
        nonceStore.issue(nonceId, scope.nonceScope(), nonce, challenge.secret(), akName, exp);

        TpmAttestChallenge body = new TpmAttestChallenge(
                nonceId,
                Base64.getEncoder().encodeToString(nonce),
                exp,
                Base64.getEncoder().encodeToString(challenge.credentialBlob()),
                Base64.getEncoder().encodeToString(challenge.encSecret()));
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .header(HttpHeaders.PRAGMA, "no-cache")
                .body(body);
    }

    // ───────────────────────────── helpers ─────────────────────────────

    private void enforceBodySize(TpmNonceRequest req) {
        long total = len(req.enrollmentToken()) + len(req.ekCert()) + len(req.ekPub())
                + len(req.akPub()) + len(req.akName());
        if (req.ekCertChain() != null) {
            for (String c : req.ekCertChain()) total += len(c);
        }
        if (total > MAX_BODY_BYTES) {
            throw new TpmEnrollmentExceptionAdvice.PayloadTooLargeException("enrollment body exceeds cap");
        }
    }

    private static int len(String s) { return s == null ? 0 : s.length(); }

    private static byte[] decode(String b64) {
        return Base64.getDecoder().decode(b64);
    }

    private static X509Certificate parseCert(String b64Der) {
        try {
            CertificateFactory cf = CertificateFactory.getInstance("X.509");
            return (X509Certificate) cf.generateCertificate(new ByteArrayInputStream(decode(b64Der)));
        } catch (Exception e) {
            throw new TpmAttestException(TpmDenyCode.EK_UNTRUSTED, "EK cert parse error");
        }
    }

    private static List<X509Certificate> parseChain(List<String> chain) {
        List<X509Certificate> out = new ArrayList<>();
        if (chain != null) {
            for (String c : chain) out.add(parseCert(c));
        }
        return out;
    }

    private static String remoteAddress(HttpServletRequest request) {
        String fwd = request.getHeader("X-Forwarded-For");
        if (fwd != null && !fwd.isBlank()) {
            return fwd.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
