package com.example.endpointadmin.tpmattest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

/**
 * Faz 22.3B (ADR-0039) gate-4d — the uniform deny surface for the TPM enrollment controller
 * (design §9 + Codex {@code 019ec723} carry-forward). EVERY verifier/issuance throwable
 * ({@link TpmAttestException}, {@link VaultPkiClient.VaultPkiException}, {@link IllegalArgumentException},
 * and any other {@link RuntimeException}) maps to ONE identical {@code 403} + fixed body — the specific
 * deny code lives ONLY in the append-only audit log, never on the wire, so the caller cannot tell which
 * check failed (no behavioral/enumeration oracle). Rate-limit → {@code 429}; oversized body → {@code 413}
 * (operational, not verify-oracles). Scoped to this controller so it never affects other endpoints.
 */
@RestControllerAdvice(assignableTypes = TpmEnrollmentController.class)
public class TpmEnrollmentExceptionAdvice {

    private static final Logger audit = LoggerFactory.getLogger("tpm-attest-audit");

    /** The single fixed deny body returned for ALL verify denies (no code, no detail). */
    private static final Map<String, String> DENY_BODY = Map.of("status", "denied");

    /** Throttle marker (volumetric rate-limit). */
    public static final class RateLimitedException extends RuntimeException {
        public RateLimitedException() { super("rate limited"); }
    }

    /** Request-body too large marker. */
    public static final class PayloadTooLargeException extends RuntimeException {
        public PayloadTooLargeException(String m) { super(m); }
    }

    @ExceptionHandler(TpmAttestException.class)
    public ResponseEntity<Map<String, String>> onDeny(TpmAttestException e) {
        // audit-only: the deny code + detail are recorded, NEVER returned on the wire.
        audit.info("tpm-enroll deny code={} detail={}", e.denyCode(), e.getMessage());
        return uniform403();
    }

    @ExceptionHandler(VaultPkiClient.VaultPkiException.class)
    public ResponseEntity<Map<String, String>> onVault(VaultPkiClient.VaultPkiException e) {
        audit.info("tpm-enroll deny code=ISSUANCE detail={}", e.getMessage());
        return uniform403();
    }

    @ExceptionHandler(RateLimitedException.class)
    public ResponseEntity<Map<String, String>> onRateLimited(RateLimitedException e) {
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body(Map.of("status", "rate_limited"));
    }

    @ExceptionHandler(PayloadTooLargeException.class)
    public ResponseEntity<Map<String, String>> onTooLarge(PayloadTooLargeException e) {
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).body(Map.of("status", "payload_too_large"));
    }

    /**
     * Catch-all (incl. {@link IllegalArgumentException} from parsers, bean-validation failures, and any
     * other {@link RuntimeException}). Fail-closed to the SAME uniform 403 — a malformed input can never
     * surface as a 5xx or a distinct status (Codex: that is itself an oracle).
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, String>> onAny(Exception e) {
        audit.info("tpm-enroll deny code=MALFORMED detail={}", e.getClass().getSimpleName());
        return uniform403();
    }

    private static ResponseEntity<Map<String, String>> uniform403() {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(DENY_BODY);
    }
}
