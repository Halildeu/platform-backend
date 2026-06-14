package com.example.endpointadmin.tpmattest;

import java.util.UUID;

/**
 * Faz 22.3B (ADR-0039) gate-4d — derives the server-side enrollment <b>scope</b> from the agent's
 * bootstrap (enrollment) token. The scope ({@code token_id|tenant|device}) is NOT caller input — it
 * is the result of validating the token against the enrollment record, so the nonce/secret the L1
 * leg issues are bound to a server-trusted identity (design §2; verifier V1 nonce-scope; V7 device
 * eligibility). Fail-closed: an invalid / expired / non-pending token → {@link TpmAttestException}
 * ({@link TpmDenyCode#DEVICE_NOT_ELIGIBLE}).
 */
public interface TpmEnrollmentScopeResolver {

    /** The validated, server-derived enrollment scope. {@code nonceScope} is the TpmNonceStore scope key. */
    record Scope(UUID tenantId, UUID enrollmentId, UUID deviceId, String nonceScope) {}

    Scope resolve(String enrollmentToken);
}
