package com.example.endpointadmin.tpmattest;

import com.example.endpointadmin.config.ConditionalOnPrimaryEndpointPlane;
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
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
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
@ConditionalOnPrimaryEndpointPlane
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
    // L2 (/attest) collaborators — PCR policy + Vault issuance exist only when configured
    // (ObjectProvider keeps the controller boot-safe when off).
    private final ObjectProvider<TpmPcrPolicy> pcrPolicyProvider;
    private final ObjectProvider<VaultPkiClient> vaultPkiClientProvider;
    private final TpmEnrollmentCompletionService completionService;
    private final SecureRandom random = new SecureRandom();

    public TpmEnrollmentController(TpmAttestProperties properties,
                                  TpmEnrollmentScopeResolver scopeResolver,
                                  ObjectProvider<TpmEkChainValidator> ekChainValidatorProvider,
                                  TpmMakeCredential makeCredential,
                                  TpmNonceStore nonceStore,
                                  TpmEnrollmentRateLimiter rateLimiter,
                                  Clock clock,
                                  ObjectProvider<TpmPcrPolicy> pcrPolicyProvider,
                                  ObjectProvider<VaultPkiClient> vaultPkiClientProvider,
                                  TpmEnrollmentCompletionService completionService) {
        this.properties = properties;
        this.scopeResolver = scopeResolver;
        this.ekChainValidatorProvider = ekChainValidatorProvider;
        this.makeCredential = makeCredential;
        this.nonceStore = nonceStore;
        this.rateLimiter = rateLimiter;
        this.clock = clock;
        this.pcrPolicyProvider = pcrPolicyProvider;
        this.vaultPkiClientProvider = vaultPkiClientProvider;
        this.completionService = completionService;
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

    @PostMapping("/attest")
    public ResponseEntity<TpmAttestResponse> attest(@Valid @RequestBody TpmAttestEnvelope env,
                                                    HttpServletRequest http) {
        enforceBodySize(env);
        String ip = remoteAddress(http);
        if (!rateLimiter.allow("ip:" + ip)) {
            throw new TpmEnrollmentExceptionAdvice.RateLimitedException();
        }
        // Re-derive the SAME server-side scope from the bootstrap token (requires PENDING).
        TpmEnrollmentScopeResolver.Scope scope = scopeResolver.resolve(env.enrollmentToken());
        // MUST#2 — bound failed-attest floods per scope (a valid-scope verify-fail still burns a nonce).
        if (!rateLimiter.allow("attest:" + scope.nonceScope())) {
            throw new TpmEnrollmentExceptionAdvice.RateLimitedException();
        }
        if (!properties.enabledForTenant(scope.tenantId())) {
            throw new TpmAttestException(TpmDenyCode.FEATURE_DISABLED, "tpm-attest disabled for tenant");
        }

        // V1 — consume the scope-bound nonce exactly once (anti-replay; scope-mismatch does not burn).
        TpmNonceStore.Consumed consumed = nonceStore.consume(env.nonceId(), scope.nonceScope())
                .orElseThrow(() -> new TpmAttestException(TpmDenyCode.NONCE_INVALID,
                        "nonce missing / expired / scope-mismatch / already used"));

        // The enrollment is now TPM_IN_PROGRESS (a re-/nonce on a PENDING lookup can't double-issue);
        // ANY failure past this point → TPM_FAILED (never fail-open, never back to PENDING).
        completionService.markInProgress(scope.enrollmentId());
        try {
            TpmPublicArea ak = TpmPublicArea.parse(decode(env.akPub()), true);
            // MUST#1 — bind the L1-validated AK to the L2 quote/certify signer.
            if (!TpmsAttest.constantTimeEquals(ak.computeName(), consumed.akName())) {
                throw new TpmAttestException(TpmDenyCode.AK_BINDING_FAILED, "akName != L1-bound AK");
            }
            // V10 — credential activation (the EK↔AK↔one-TPM proof; recovered secret == issued).
            TpmMakeCredential.verifyRecoveredSecret(consumed.serverSecret(), decode(env.activatedSecret()));
            // V5 — quote signed by the AK over the issued nonce.
            TpmAttestationVerifier.verifyQuote(ak, decode(env.quote()), decode(env.quoteSig()), consumed.nonce());
            // V6 — PCR policy (enforced only when an operator has configured it).
            TpmPcrPolicy pcrPolicy = pcrPolicyProvider.getIfAvailable();
            if (pcrPolicy != null) {
                pcrPolicy.verify(TpmsAttest.parse(decode(env.quote())));
            }
            // V4 — the device key is the TPM-resident key the AK certified.
            TpmPublicArea deviceKey = TpmPublicArea.parse(decode(env.deviceKeyPub()), true);
            TpmAttestationVerifier.verifyCertify(ak, decode(env.certifyInfo()), decode(env.certifySig()), deviceKey);
            // Bind the attested device key to the CSR being signed (attested key == issued key).
            requireCsrKeyMatchesDeviceKey(decode(env.csrDer()), deviceKey);
            // V9 — CSR key policy (RSA-3072+/EC-P256+, clientAuth-only, proof-of-possession).
            TpmCsrPolicy.verify(decode(env.csrDer()));

            // Issue via Vault PKI (only when configured; absent-while-enabled → fail-closed).
            VaultPkiClient vault = vaultPkiClientProvider.getIfAvailable();
            if (vault == null) {
                throw new TpmAttestException(TpmDenyCode.FEATURE_DISABLED, "vault issuance not configured");
            }
            String certificatePem = vault.signCsr(csrDerToPem(decode(env.csrDer())));

            // Faz 22.6 #548 step-4 — persist the V10-proven device binding ATOMICALLY with CONSUMED, so the
            // canonical device-key session verifier can later bind AK↔EK against this enrollment record. All
            // inputs are the values just verified above: akName is the L1-bound TPM Name; AK pub / EK cert /
            // device-key SPKI are SHA-256 digests. A null scope.deviceId() → no binding row (handled downstream).
            TpmEnrollmentCompletionService.TpmBinding binding = new TpmEnrollmentCompletionService.TpmBinding(
                    scope.tenantId(), scope.deviceId(), consumed.akName(),
                    sha256Hex(decode(env.akPub())),
                    sha256Hex(decode(env.ekCert())),
                    sha256Hex(deviceKey.toPublicKey().getEncoded()));
            completionService.markConsumed(scope.enrollmentId(), binding);
            return ResponseEntity.ok()
                    .cacheControl(CacheControl.noStore())
                    .header(HttpHeaders.PRAGMA, "no-cache")
                    .body(new TpmAttestResponse(certificatePem));
        } catch (RuntimeException e) {
            completionService.markFailed(scope.enrollmentId());
            throw e; // mapped to the uniform 403 (deny code audit-only) by the advice
        }
    }

    // ───────────────────────────── helpers ─────────────────────────────

    private void enforceBodySize(TpmAttestEnvelope env) {
        long total = len(env.enrollmentToken()) + len(env.ekCert()) + len(env.akPub()) + len(env.akName())
                + len(env.activatedSecret()) + len(env.certifyInfo()) + len(env.certifySig())
                + len(env.quote()) + len(env.quoteSig()) + len(env.deviceKeyPub()) + len(env.csrDer());
        if (env.ekCertChain() != null) {
            for (String c : env.ekCertChain()) total += len(c);
        }
        if (total > MAX_BODY_BYTES) {
            throw new TpmEnrollmentExceptionAdvice.PayloadTooLargeException("attest body exceeds cap");
        }
    }

    private static String csrDerToPem(byte[] der) {
        return "-----BEGIN CERTIFICATE REQUEST-----\n"
                + Base64.getMimeEncoder(64, "\n".getBytes(java.nio.charset.StandardCharsets.US_ASCII)).encodeToString(der)
                + "\n-----END CERTIFICATE REQUEST-----\n";
    }

    private static void requireCsrKeyMatchesDeviceKey(byte[] csrDer, TpmPublicArea deviceKey) {
        try {
            var csr = new org.bouncycastle.pkcs.PKCS10CertificationRequest(csrDer);
            PublicKey csrKey = new org.bouncycastle.pkcs.jcajce.JcaPKCS10CertificationRequest(csr)
                    .setProvider(new org.bouncycastle.jce.provider.BouncyCastleProvider()).getPublicKey();
            if (!csrKey.equals(deviceKey.toPublicKey())) {
                throw new TpmAttestException(TpmDenyCode.KEY_NOT_TPM_BOUND, "CSR public key != attested device key");
            }
        } catch (TpmAttestException e) {
            throw e;
        } catch (Exception e) {
            throw new TpmAttestException(TpmDenyCode.KEY_NOT_TPM_BOUND, "CSR parse/key error");
        }
    }

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

    /** Lowercase-hex SHA-256 — the stable digest the device-binding record stores for AK pub / EK cert / SPKI. */
    private static String sha256Hex(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
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
