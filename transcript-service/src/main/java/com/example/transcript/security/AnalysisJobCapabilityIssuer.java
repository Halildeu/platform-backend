package com.example.transcript.security;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

/** Issues a short-lived capability bound to one exact transcript analysis job. */
@Component
public class AnalysisJobCapabilityIssuer {

    public static final String PERMISSION = "meeting:analysis-result:write";
    static final Duration MAX_TTL = Duration.ofMinutes(5);

    private final byte[] secret;
    private final Duration ttl;
    private final String issuer;
    private final String audience;
    private final String clientId;
    private final Clock clock;

    @Autowired
    public AnalysisJobCapabilityIssuer(
            @Value("${security.analysis-job-capability.hmac-secret:}") String encodedSecret,
            @Value("${security.analysis-job-capability.ttl:PT5M}") Duration ttl,
            @Value("${security.analysis-job-capability.issuer:transcript-service}") String issuer,
            @Value("${security.analysis-job-capability.audience:meeting-service}") String audience,
            @Value("${security.analysis-job-capability.client-id:meeting-ai}") String clientId) {
        this(encodedSecret, ttl, issuer, audience, clientId, Clock.systemUTC());
    }

    AnalysisJobCapabilityIssuer(
            String encodedSecret,
            Duration ttl,
            String issuer,
            String audience,
            String clientId,
            Clock clock) {
        this.secret = decodeSecret(encodedSecret);
        if (ttl == null || ttl.isZero() || ttl.isNegative() || ttl.compareTo(MAX_TTL) > 0) {
            throw new IllegalArgumentException("Analysis job capability TTL must be between 1 second and 5 minutes");
        }
        if (!StringUtils.hasText(issuer) || !StringUtils.hasText(audience) || !StringUtils.hasText(clientId)) {
            throw new IllegalArgumentException("Analysis job capability issuer, audience and client-id are required");
        }
        this.ttl = ttl;
        this.issuer = issuer;
        this.audience = audience;
        this.clientId = clientId;
        this.clock = clock;
    }

    public IssuedCapability issue(JobBinding binding) {
        if (secret.length == 0) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "JOB_CAPABILITY_UNAVAILABLE");
        }
        Instant issuedAt = clock.instant();
        Instant expiresAt = issuedAt.plus(ttl);
        UUID capabilityId = UUID.randomUUID();
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .issuer(issuer)
                .audience(audience)
                .subject(clientId)
                .claim("client_id", clientId)
                .claim("perm", PERMISSION)
                .jwtID(capabilityId.toString())
                .issueTime(Date.from(issuedAt))
                .expirationTime(Date.from(expiresAt))
                .claim("tenant_id", binding.tenantId().toString())
                .claim("meeting_id", binding.meetingId().toString())
                .claim("session_id", binding.sessionId().toString())
                .claim("finalization_version", binding.finalizationVersion())
                .claim("finalized_at", binding.finalizedAt().toString())
                .claim("transcript_sha256", binding.transcriptSha256())
                .claim("analysis_run_id", binding.analysisRunId().toString())
                .claim("analysis_spec_version", binding.analysisSpecVersion())
                .build();
        SignedJWT jwt = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.HS256).type(JOSEObjectType.JWT).build(), claims);
        try {
            jwt.sign(new MACSigner(secret));
        } catch (JOSEException ex) {
            throw new IllegalStateException("Analysis job capability signing failed", ex);
        }
        return new IssuedCapability(jwt.serialize(), capabilityId, expiresAt);
    }

    private static byte[] decodeSecret(String encodedSecret) {
        if (!StringUtils.hasText(encodedSecret)) {
            return new byte[0];
        }
        try {
            byte[] decoded = Base64.getDecoder().decode(encodedSecret.trim());
            if (decoded.length < 32) {
                throw new IllegalArgumentException("Analysis job capability secret must be at least 256 bits");
            }
            return decoded;
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("Analysis job capability secret must be base64-encoded and at least 256 bits", ex);
        }
    }

    public record JobBinding(
            UUID tenantId,
            UUID meetingId,
            UUID sessionId,
            long finalizationVersion,
            Instant finalizedAt,
            String transcriptSha256,
            UUID analysisRunId,
            String analysisSpecVersion) { }

    public record IssuedCapability(String token, UUID capabilityId, Instant expiresAt) { }
}
