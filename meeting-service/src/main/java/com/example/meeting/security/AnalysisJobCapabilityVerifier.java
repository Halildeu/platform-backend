package com.example.meeting.security;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.crypto.MACVerifier;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.text.ParseException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

/** Verifies the transcript-service capability and returns its exact job tuple. */
@Component
public class AnalysisJobCapabilityVerifier {

    public static final String PERMISSION = "meeting:analysis-result:write";
    static final Duration MAX_TTL = Duration.ofMinutes(5);

    private final byte[] secret;
    private final String issuer;
    private final String audience;
    private final String clientId;
    private final Duration maxTtl;
    private final Clock clock;

    @Autowired
    public AnalysisJobCapabilityVerifier(
            @Value("${security.analysis-job-capability.hmac-secret:}") String encodedSecret,
            @Value("${security.analysis-job-capability.issuer:transcript-service}") String issuer,
            @Value("${security.analysis-job-capability.audience:meeting-service}") String audience,
            @Value("${security.analysis-job-capability.client-id:meeting-ai}") String clientId,
            @Value("${security.analysis-job-capability.max-ttl:PT5M}") Duration maxTtl) {
        this(encodedSecret, issuer, audience, clientId, maxTtl, Clock.systemUTC());
    }

    AnalysisJobCapabilityVerifier(
            String encodedSecret,
            String issuer,
            String audience,
            String clientId,
            Duration maxTtl,
            Clock clock) {
        this.secret = decodeSecret(encodedSecret);
        if (!StringUtils.hasText(issuer) || !StringUtils.hasText(audience) || !StringUtils.hasText(clientId)) {
            throw new IllegalArgumentException("Analysis job capability trust identity is required");
        }
        if (maxTtl == null || maxTtl.isZero() || maxTtl.isNegative()
                || maxTtl.compareTo(MAX_TTL) > 0) {
            throw new IllegalArgumentException("Analysis job capability max TTL must be at most 5 minutes");
        }
        this.issuer = issuer;
        this.audience = audience;
        this.clientId = clientId;
        this.maxTtl = maxTtl;
        this.clock = clock;
    }

    public JobBinding verify(String token) {
        if (secret.length == 0) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "JOB_CAPABILITY_UNAVAILABLE");
        }
        try {
            SignedJWT jwt = SignedJWT.parse(token);
            if (!JWSAlgorithm.HS256.equals(jwt.getHeader().getAlgorithm())
                    || !jwt.verify(new MACVerifier(secret))) {
                throw invalid();
            }
            JWTClaimsSet claims = jwt.getJWTClaimsSet();
            Instant now = clock.instant();
            Instant issuedAt = requiredInstant(claims.getIssueTime());
            Instant expiresAt = requiredInstant(claims.getExpirationTime());
            if (expiresAt.isBefore(now) || expiresAt.equals(now)
                    || issuedAt.isAfter(now.plusSeconds(30))
                    || !expiresAt.isAfter(issuedAt)
                    || Duration.between(issuedAt, expiresAt).compareTo(maxTtl) > 0
                    || !issuer.equals(claims.getIssuer())
                    || !claims.getAudience().equals(List.of(audience))
                    || !clientId.equals(claims.getSubject())
                    || !clientId.equals(claims.getStringClaim("client_id"))
                    || !PERMISSION.equals(claims.getStringClaim("perm"))) {
                throw invalid();
            }
            long finalizationVersion = claims.getLongClaim("finalization_version");
            if (finalizationVersion < 1) {
                throw invalid();
            }
            return new JobBinding(
                    requiredUuid(claims.getJWTID()),
                    requiredUuid(claims.getStringClaim("tenant_id")),
                    requiredUuid(claims.getStringClaim("meeting_id")),
                    requiredUuid(claims.getStringClaim("session_id")),
                    finalizationVersion,
                    Instant.parse(claims.getStringClaim("finalized_at")),
                    requiredHash(claims.getStringClaim("transcript_sha256")),
                    requiredUuid(claims.getStringClaim("analysis_run_id")),
                    requiredText(claims.getStringClaim("analysis_spec_version"), 64),
                    expiresAt);
        } catch (ResponseStatusException ex) {
            throw ex;
        } catch (ParseException | JOSEException | RuntimeException ex) {
            throw invalid();
        }
    }

    private static Instant requiredInstant(java.util.Date value) {
        if (value == null) {
            throw invalid();
        }
        return value.toInstant();
    }

    private static UUID requiredUuid(String value) {
        return UUID.fromString(requiredText(value, 64));
    }

    private static String requiredHash(String value) {
        String hash = requiredText(value, 64);
        if (!hash.matches("^[0-9a-f]{64}$")) {
            throw invalid();
        }
        return hash;
    }

    private static String requiredText(String value, int maxLength) {
        if (!StringUtils.hasText(value) || value.length() > maxLength) {
            throw invalid();
        }
        return value;
    }

    private static byte[] decodeSecret(String encodedSecret) {
        if (!StringUtils.hasText(encodedSecret)) {
            return new byte[0];
        }
        try {
            byte[] decoded = Base64.getDecoder().decode(encodedSecret.trim());
            if (decoded.length < 32) {
                throw new IllegalArgumentException("secret too short");
            }
            return decoded;
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("Analysis job capability secret must be base64-encoded and at least 256 bits", ex);
        }
    }

    private static ResponseStatusException invalid() {
        return new ResponseStatusException(HttpStatus.FORBIDDEN, "JOB_CAPABILITY_INVALID");
    }

    public record JobBinding(
            UUID capabilityId,
            UUID tenantId,
            UUID meetingId,
            UUID sessionId,
            long finalizationVersion,
            Instant finalizedAt,
            String transcriptSha256,
            UUID analysisRunId,
            String analysisSpecVersion,
            Instant expiresAt) { }
}
